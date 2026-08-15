package com.homephoto.server.worker

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Captions
import com.homephoto.server.db.Jobs
import com.homephoto.server.service.AssetIngestService
import com.homephoto.server.service.CaptionService
import com.homephoto.server.service.CaptionUnavailableException
import com.homephoto.server.service.ThumbnailService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime

/**
 * CAPTION 작업 처리. 1600px 썸네일을 GB10 VLM에 보내 캡션/태그를 받아 저장한다.
 * GB10이 꺼져 있으면(연결 실패) 작업을 PENDING으로 되돌리고 — attempts 소모 없음 —
 * 잠시 쉬었다가 재개한다 (외부 의존 격리 원칙).
 */
@Component
class CaptionWorker(
    private val props: AppProperties,
    private val captionService: CaptionService,
    private val thumbnailService: ThumbnailService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** VLM 연결 실패 시 이 시각까지 클레임을 쉰다 (단일 스레드 워커라 동기화 불필요) */
    private var pausedUntil: Instant = Instant.MIN
    private var wasUnavailable = false

    data class Claimed(val jobId: Long, val assetId: Long, val hash: String, val relPath: String, val mediaType: String, val attempts: Int)

    @Scheduled(fixedDelay = 5000)
    fun tick() {
        if (!props.caption.enabled) return
        if (Instant.now().isBefore(pausedUntil)) return
        var done = 0
        try {
            while (true) {
                val job = claimNext() ?: break
                try {
                    process(job)
                    done++
                } catch (e: CaptionUnavailableException) {
                    // 서버 문제가 아니라 GB10이 꺼져 있는 것 — 작업을 되돌리고 백오프
                    release(job.jobId)
                    pausedUntil = Instant.now().plusSeconds(BACKOFF_SECONDS)
                    if (!wasUnavailable) log.warn("VLM 서버 응답 없음 — {}초 후 재시도: {}", BACKOFF_SECONDS, e.message)
                    wasUnavailable = true
                    return
                } catch (e: Exception) {
                    val isFinal = job.attempts + 1 >= MAX_ATTEMPTS
                    log.warn("캡션 작업 ${job.jobId} 실패 (시도 ${job.attempts + 1}/$MAX_ATTEMPTS${if (isFinal) ", 포기" else ""}): ${e.message}")
                    complete(job.jobId, if (isFinal) "FAILED" else "PENDING", e.message?.take(500), job.attempts)
                }
            }
        } catch (e: org.jetbrains.exposed.exceptions.ExposedSQLException) {
            // 대량 업로드 중 일시적 DB 잠금 — 다음 틱에서 재시도
            log.warn("캡션 워커 일시정지 (DB 잠금): ${e.message?.lineSequence()?.first()}")
        }
        if (done > 0) {
            if (wasUnavailable) log.info("VLM 서버 재연결됨")
            wasUnavailable = false
            val pending = transaction {
                Jobs.selectAll().where { (Jobs.jobType eq "CAPTION") and (Jobs.status eq "PENDING") }.count()
            }
            log.info("장면 분석: {}건 완료 — 대기 {}건", done, pending)
        }
    }

    private fun process(job: Claimed) {
        // VLM에는 원본 대신 1600px 썸네일을 보낸다. 아직 없으면 먼저 만든다 (멱등).
        val thumb = thumbnailService.thumbPath(job.hash, VLM_IMAGE_SIZE)
        if (!Files.exists(thumb)) {
            thumbnailService.generate(job.hash, job.relPath, job.mediaType)
        }
        val result = captionService.analyze(thumb)
        val nowIso = LocalDateTime.now().format(AssetIngestService.ISO)
        transaction {
            // 재처리(모델 교체) 대비: 기존 캡션을 지우고 새로 넣는다 (멱등)
            Captions.deleteWhere { assetId eq job.assetId }
            Captions.insert {
                it[assetId] = job.assetId
                it[caption] = result.caption
                it[tags] = result.tags
                it[model] = result.model
                it[createdAt] = nowIso
            }
            Jobs.update({ Jobs.id eq job.jobId }) {
                it[status] = "DONE"
                it[attempts] = job.attempts + 1
                it[lastError] = null
                it[updatedAt] = nowIso
            }
        }
        log.debug("캡션 저장: asset #{} — {}", job.assetId, result.caption.take(80))
    }

    private fun claimNext(): Claimed? = transaction {
        // 쓰기 문장을 트랜잭션 첫 문장으로 (SQLITE_BUSY 규율 — ThumbnailWorker 참고)
        exec(
            """
            UPDATE jobs SET status = 'RUNNING', updated_at = '${now()}'
            WHERE id = (
              SELECT id FROM jobs
              WHERE job_type = 'CAPTION' AND status = 'PENDING'
              ORDER BY priority DESC
              LIMIT 1
            )
            """.trimIndent()
        )

        Jobs.innerJoin(Assets)
            .selectAll()
            .where { (Jobs.jobType eq "CAPTION") and (Jobs.status eq "RUNNING") }
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                Claimed(
                    jobId = row[Jobs.id],
                    assetId = row[Assets.id],
                    hash = row[Assets.hash],
                    relPath = row[Assets.originalPath],
                    mediaType = row[Assets.mediaType],
                    attempts = row[Jobs.attempts],
                )
            }
    }

    /** VLM 연결 실패 시: attempts를 늘리지 않고 PENDING으로 되돌린다. */
    private fun release(jobId: Long) {
        transaction {
            Jobs.update({ Jobs.id eq jobId }) {
                it[status] = "PENDING"
                it[updatedAt] = now()
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

    companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_SECONDS = 60L
        const val VLM_IMAGE_SIZE = 1600
    }
}
