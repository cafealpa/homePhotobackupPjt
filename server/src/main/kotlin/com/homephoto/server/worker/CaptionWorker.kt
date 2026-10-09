package com.homephoto.server.worker

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Captions
import com.homephoto.server.service.AssetIngestService
import com.homephoto.server.service.CaptionService
import com.homephoto.server.service.CaptionUnavailableException
import com.homephoto.server.service.JobQueueService
import com.homephoto.server.service.ThumbnailService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime

/**
 * 전용 스레드에서 CAPTION 작업 처리. 사진마다 결과/완료 상태를 원자적으로 저장한다.
 * 외부 분석 서버 장애는 시도 횟수 소모 없이 PENDING으로 되돌리고 재시도한다.
 */
@Component
class CaptionWorker(
    private val props: AppProperties,
    private val captionService: CaptionService,
    private val thumbnailService: ThumbnailService,
    private val queue: JobQueueService,
    private val activity: com.homephoto.server.service.ServerActivity = com.homephoto.server.service.ServerActivity(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val running = java.util.concurrent.atomic.AtomicBoolean()
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "caption-worker").apply { isDaemon = true }
    }
    @Volatile private var pausedUntil: Instant = Instant.MIN
    @Volatile private var error: String? = null
    @Volatile private var currentAssetId: Long? = null
    data class Status(val enabled: Boolean, val running: Boolean, val currentAssetId: Long?,
                      val error: String?, val retryAt: String?, val provider: String, val model: String)
    fun status(): Status {
        val cfg = props.caption
        return Status(cfg.enabled, running.get(), currentAssetId, captionService.configurationError() ?: error,
            pausedUntil.takeIf { it.isAfter(Instant.now()) }?.toString(), cfg.provider,
            if (cfg.provider == "GEMINI") cfg.geminiModel else cfg.model)
    }

    @Scheduled(fixedDelay = 3000)
    fun tick() {
        if (!props.caption.enabled || activity.blocked("CAPTION") || activity.retryWaiting("CAPTION") || Instant.now().isBefore(pausedUntil) ||
            !running.compareAndSet(false, true)) return
        executor.submit {
            if (!activity.enter("CAPTION")) { running.set(false); return@submit }
            try {
                captionService.configurationError()?.let { throw CaptionUnavailableException(it) }
                error = null
                repeat(10) {
                    if (!props.caption.enabled || activity.blocked("CAPTION")) return@submit
                    val job = queue.claim("CAPTION") ?: return@submit
                    currentAssetId = job.assetId
                    try {
                        process(job)
                    } catch (e: CaptionUnavailableException) {
                        queue.release(job.jobId, "CAPTION")
                        throw e
                    } catch (e: Exception) {
                        queue.fail(job.jobId, "CAPTION", e.message)
                        log.warn("장면 분석 실패 asset={}: {}", job.assetId, e.message)
                    } finally { currentAssetId = null }
                }
            } catch (e: Exception) {
                if (com.homephoto.server.service.ProcessMonitor.cancelled()) return@submit
                error = e.message ?: e.javaClass.simpleName
                pausedUntil = Instant.now().plusSeconds((e as? CaptionUnavailableException)?.retrySeconds ?: BACKOFF_SECONDS)
                activity.issue("CAPTION",error ?: "작업 연결 실패",pausedUntil.toEpochMilli())
                log.warn("장면 분석 대기: {}", error)
            } finally { running.set(false); activity.leave("CAPTION") }
        }
    }
    fun retryNow() { pausedUntil=Instant.MIN; error=null; activity.clearIssue("CAPTION") }
    @jakarta.annotation.PreDestroy fun close() { executor.shutdown() }

    private fun process(job: JobQueueService.Claimed): Boolean {
        // 뷰어용 1600px 썸네일을 준비한다. 클라이언트는 전송 직전 메모리에서 768px로 줄인다.
        val thumb = thumbnailService.thumbPath(job.hash, VLM_IMAGE_SIZE)
        if (!Files.exists(thumb)) {
            thumbnailService.generate(job.hash, job.relPath, job.mediaType)
        }
        val result = captionService.analyze(thumb)
        val nowIso = LocalDateTime.now().format(AssetIngestService.ISO)
        val completed = queue.complete(job.jobId, "CAPTION") {
            // 재처리(모델 교체) 대비: 기존 캡션을 지우고 새로 넣는다 (멱등)
            Captions.deleteWhere { assetId eq job.assetId }
            Captions.insert {
                it[assetId] = job.assetId
                it[caption] = result.caption
                it[tags] = result.tags
                it[model] = result.model
                it[createdAt] = nowIso
            }
            result.documentClassification?.let { com.homephoto.server.document.DocumentRepository.detected(job.assetId, it) }
        }
        if (completed) log.debug("캡션 저장: asset #{} — {}", job.assetId, result.caption.take(80))
        return completed
    }

    companion object {
        const val MAX_ATTEMPTS = JobQueueService.MAX_ATTEMPTS
        const val BACKOFF_SECONDS = 60L
        const val VLM_IMAGE_SIZE = 1600
    }
}
