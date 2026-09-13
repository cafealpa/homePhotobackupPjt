package com.homephoto.server.worker

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Jobs
import com.homephoto.server.service.JobQueueService
import com.homephoto.server.service.ThumbnailService
import jakarta.annotation.PreDestroy
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 썸네일 생성 워커.
 *
 * 이미지 디코딩이 CPU를 오래 잡아먹으므로(장당 0.7~1.3초) 여러 스레드로 병렬 처리한다.
 * DB 작업은 공통 큐에서 원자적으로 선점하고, 무거운 생성 작업은 스레드별로 동시에 돈다.
 * 스레드 수는 `homephoto.thumbnail-threads` (0 = 코어 수에서 자동 산정).
 */
@Component
class ThumbnailWorker(
    private val thumbnailService: ThumbnailService,
    private val props: AppProperties,
    private val queue: JobQueueService,
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
    private val activeJobs = ConcurrentHashMap<Long, Long>()

    @Scheduled(fixedDelay = 3000)
    fun tick() {
        if (!running.compareAndSet(false, true)) return // 앞 틱이 아직 큐를 비우는 중
        val done = AtomicInteger()
        val failed = AtomicInteger()
        val started = AtomicBoolean(false)
        val tickStart = System.nanoTime()
        try {
            // 스레드마다 "큐가 빌 때까지 한 건씩 집어 처리"를 돌린다
            val futures = (1..threads).map { executor.submit { drainQueue(done, failed, started) } }
            for (future in futures) {
                while (true) {
                    try {
                        future.get(30, TimeUnit.SECONDS)
                        break
                    } catch (_: java.util.concurrent.TimeoutException) {
                        log.info("썸네일 처리 중: 경과={}초 완료={} 실패={} 실행 중 작업(id:초)={} — 목록이 비면 큐 조회/DB 대기 확인",
                            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - tickStart), done.get(), failed.get(),
                            activeJobs.entries.joinToString { (id, time) -> "$id:${TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - time)}" })
                    }
                }
            }
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
    private fun drainQueue(done: AtomicInteger, failed: AtomicInteger, started: AtomicBoolean) {
        try {
            while (true) {
                val job = queue.claim("THUMBNAIL") ?: break
                if (started.compareAndSet(false, true)) {
                    log.info("썸네일 처리 시작: 병렬={} 첫 작업={} asset={} 형식={}", threads, job.jobId, job.assetId, job.mediaType)
                }
                val jobStart = System.nanoTime()
                activeJobs[job.jobId] = jobStart
                try {
                    thumbnailService.generate(job.hash, job.relPath, job.mediaType)
                    if (queue.complete(job.jobId, "THUMBNAIL")) done.incrementAndGet()
                } catch (e: Exception) {
                    val isFinal = job.attempts + 1 >= MAX_ATTEMPTS
                    log.warn("썸네일 작업 ${job.jobId} 실패 (시도 ${job.attempts + 1}/$MAX_ATTEMPTS${if (isFinal) ", 포기" else ""}): ${e.message}")
                    queue.fail(job.jobId, "THUMBNAIL", e.message)
                    failed.incrementAndGet()
                } finally {
                    activeJobs.remove(job.jobId)
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - jobStart)
                    if (elapsed >= 5000) log.info("느린 썸네일 작업 종료: job={} asset={} 소요={}ms (성공 여부는 완료/실패 집계 참조)", job.jobId, job.assetId, elapsed)
                }
            }
        } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            // 대량 업로드 중 일시적 DB 잠금 — 작업은 롤백되어 남아 있으므로 다음 틱에서 재시도
            log.warn("썸네일 워커 일시정지 (DB 잠금): ${e.message?.lineSequence()?.first()}")
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        runCatching { executor.awaitTermination(10, TimeUnit.SECONDS) }
    }

    companion object {
        const val MAX_ATTEMPTS = JobQueueService.MAX_ATTEMPTS
    }
}
