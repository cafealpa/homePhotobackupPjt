package com.homephoto.server.worker

import com.homephoto.server.db.Assets
import com.homephoto.server.db.Jobs
import com.homephoto.server.service.AssetIngestService
import com.homephoto.server.service.ThumbnailService
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class ThumbnailWorker(private val thumbnailService: ThumbnailService) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class Claimed(val jobId: Long, val hash: String, val relPath: String, val mediaType: String, val attempts: Int)

    @Scheduled(fixedDelay = 3000)
    fun tick() {
        var done = 0
        var failed = 0
        try {
            while (true) {
                val job = claimNext() ?: break
                try {
                    thumbnailService.generate(job.hash, job.relPath, job.mediaType)
                    complete(job.jobId, "DONE", null, job.attempts)
                    done++
                } catch (e: Exception) {
                    val isFinal = job.attempts + 1 >= MAX_ATTEMPTS
                    log.warn("썸네일 작업 ${job.jobId} 실패 (시도 ${job.attempts + 1}/$MAX_ATTEMPTS${if (isFinal) ", 포기" else ""}): ${e.message}")
                    complete(job.jobId, if (isFinal) "FAILED" else "PENDING", e.message?.take(500), job.attempts)
                    failed++
                }
            }
        } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            // 대량 업로드 중 일시적 DB 잠금 — 작업은 롤백되어 남아 있으므로 다음 틱에서 재시도
            log.warn("썸네일 워커 일시정지 (DB 잠금): ${e.message?.lineSequence()?.first()}")
        }
        if (done > 0 || failed > 0) {
            val pending = transaction {
                Jobs.selectAll().where { (Jobs.jobType eq "THUMBNAIL") and (Jobs.status eq "PENDING") }.count()
            }
            log.info("썸네일: {}건 생성{} — 대기 {}건", done, if (failed > 0) ", ${failed}건 실패" else "", pending)
        }
    }

    private fun claimNext(): Claimed? = transaction {
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

        // 워커는 단일 스레드라 RUNNING은 방금 클레임한 1건뿐이다
        // (비정상 종료로 남은 RUNNING은 시작 시 PENDING으로 리셋됨)
        Jobs.innerJoin(Assets)
            .selectAll()
            .where { (Jobs.jobType eq "THUMBNAIL") and (Jobs.status eq "RUNNING") }
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                Claimed(
                    jobId = row[Jobs.id],
                    hash = row[Assets.hash],
                    relPath = row[Assets.originalPath],
                    mediaType = row[Assets.mediaType],
                    attempts = row[Jobs.attempts],
                )
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

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
