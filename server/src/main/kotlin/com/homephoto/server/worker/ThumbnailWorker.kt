package com.homephoto.server.worker

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Jobs
import com.homephoto.server.service.AssetIngestService
import com.homephoto.server.service.ThumbnailService
import jakarta.annotation.PreDestroy
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 썸네일 생성 워커.
 *
 * 이미지 디코딩이 CPU를 오래 잡아먹으므로(장당 0.7~1.3초) 여러 스레드로 병렬 처리한다.
 * DB 작업 클레임만 [claimLock]으로 직렬화하고, 무거운 생성 작업은 스레드별로 동시에 돈다.
 * 스레드 수는 `homephoto.thumbnail-threads` (0 = 코어 수에서 자동 산정).
 */
@Component
class ThumbnailWorker(
    private val thumbnailService: ThumbnailService,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 동시에 돌릴 생성 스레드 수. 0(자동)이면 코어의 절반, 최대 4 — 나머지는 웹 응답·임포트 몫으로 남긴다 */
    private val threads: Int = props.thumbnailThreads.takeIf { it > 0 }
        ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    private val executor = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "thumbnail-worker").apply { isDaemon = true }
    }

    /** 이전 틱이 아직 돌고 있으면 새 틱은 건너뛴다 (스케줄러 스레드가 쌓이지 않게) */
    private val running = AtomicBoolean(false)

    /**
     * 지금 이 프로세스가 처리 중인 job id.
     * 클레임은 "PENDING 한 건을 RUNNING으로 바꾸고, RUNNING 중 아직 내 것이 아닌 행을 집는다"로 동작하는데,
     * 여러 스레드가 동시에 RUNNING 행을 만들기 때문에 이미 잡은 것을 제외할 목록이 필요하다.
     * (비정상 종료로 남은 RUNNING은 시작 시 DataInitializer가 PENDING으로 되돌린다)
     */
    private val inFlight: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val claimLock = Any()

    data class Claimed(val jobId: Long, val hash: String, val relPath: String, val mediaType: String, val attempts: Int)

    @Scheduled(fixedDelay = 3000)
    fun tick() {
        if (!running.compareAndSet(false, true)) return // 앞 틱이 아직 큐를 비우는 중
        val done = AtomicInteger()
        val failed = AtomicInteger()
        try {
            // 스레드마다 "큐가 빌 때까지 한 건씩 집어 처리"를 돌린다
            val futures = (1..threads).map { executor.submit { drainQueue(done, failed) } }
            futures.forEach { it.get() }
        } catch (e: Exception) {
            log.warn("썸네일 워커 틱 실패: {}", e.message)
        } finally {
            running.set(false)
        }
        if (done.get() > 0 || failed.get() > 0) {
            val pending = runCatching {
                transaction {
                    Jobs.selectAll().where { (Jobs.jobType eq "THUMBNAIL") and (Jobs.status eq "PENDING") }.count()
                }
            }.getOrDefault(-1L)
            log.info(
                "썸네일: {}건 생성{} — 대기 {}건 ({}스레드)",
                done.get(), if (failed.get() > 0) ", ${failed.get()}건 실패" else "", pending, threads,
            )
        }
    }

    /** 큐가 빌 때까지 한 건씩 클레임해 처리한다. 스레드 하나가 담당하는 루프. */
    private fun drainQueue(done: AtomicInteger, failed: AtomicInteger) {
        try {
            while (true) {
                val job = claimNext() ?: break
                try {
                    thumbnailService.generate(job.hash, job.relPath, job.mediaType)
                    complete(job.jobId, "DONE", null, job.attempts)
                    done.incrementAndGet()
                } catch (e: Exception) {
                    val isFinal = job.attempts + 1 >= MAX_ATTEMPTS
                    log.warn("썸네일 작업 ${job.jobId} 실패 (시도 ${job.attempts + 1}/$MAX_ATTEMPTS${if (isFinal) ", 포기" else ""}): ${e.message}")
                    complete(job.jobId, if (isFinal) "FAILED" else "PENDING", e.message?.take(500), job.attempts)
                    failed.incrementAndGet()
                } finally {
                    inFlight.remove(job.jobId)
                }
            }
        } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            // 대량 업로드 중 일시적 DB 잠금 — 작업은 롤백되어 남아 있으므로 다음 틱에서 재시도
            log.warn("썸네일 워커 일시정지 (DB 잠금): ${e.message?.lineSequence()?.first()}")
        }
    }

    /**
     * 다음 작업 한 건을 클레임한다. 여러 스레드가 같은 행을 잡지 않도록 통째로 직렬화한다
     * (클레임은 짧은 DB 연산이고, 실제로 오래 걸리는 생성 작업은 락 밖에서 병렬로 돈다).
     */
    private fun claimNext(): Claimed? = synchronized(claimLock) {
        transaction {
            // 쓰기(UPDATE)를 트랜잭션의 첫 문장으로 둔다. SELECT로 시작하면 읽기→쓰기 잠금 승격이
            // 필요해지는데, 그 사이 다른 쓰기가 끼어들면 busy_timeout 대기 없이 즉시 SQLITE_BUSY가
            // 난다. 쓰기로 시작하면 잠금 경합 시 busy_timeout만큼 정상적으로 대기한다.
            exec(
                """
                UPDATE jobs SET status = 'RUNNING', updated_at = '${now()}'
                WHERE id = (
                  SELECT id FROM jobs
                  WHERE job_type = 'THUMBNAIL' AND status = 'PENDING'
                  ORDER BY priority DESC
                  LIMIT 1
                )
                """.trimIndent()
            )

            // 방금 RUNNING이 된 행 = RUNNING 중 아직 아무 스레드도 안 잡은 것.
            // (다른 스레드가 처리 중인 행은 inFlight에 있으므로 제외된다)
            val claimedIds = inFlight.toList()
            Jobs.innerJoin(Assets)
                .selectAll()
                .where {
                    (Jobs.jobType eq "THUMBNAIL") and (Jobs.status eq "RUNNING") and
                        (if (claimedIds.isEmpty()) Op.TRUE else Jobs.id notInList claimedIds)
                }
                .limit(1)
                .firstOrNull()
                ?.let { row ->
                    val jobId = row[Jobs.id]
                    inFlight.add(jobId)
                    Claimed(
                        jobId = jobId,
                        hash = row[Assets.hash],
                        relPath = row[Assets.originalPath],
                        mediaType = row[Assets.mediaType],
                        attempts = row[Jobs.attempts],
                    )
                }
        }
    }

    private fun complete(jobId: Long, newStatus: String, error: String?, prevAttempts: Int) {
        transaction {
            Jobs.update({ Jobs.id eq jobId }) {
                it[status] = newStatus
                it[attempts] = prevAttempts + 1
                it[lastError] = error
                it[updatedAt] = now()
            }
        }
    }

    private fun now(): String = LocalDateTime.now().format(AssetIngestService.ISO)

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        runCatching { executor.awaitTermination(10, TimeUnit.SECONDS) }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
