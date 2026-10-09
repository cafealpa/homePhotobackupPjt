package com.homephoto.server.document

import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.*
import com.homephoto.server.storage.StorageAdapter
import jakarta.annotation.PreDestroy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Component
class DocumentWorker(private val props: AppProperties, private val queue: JobQueueService,
    private val originals: StorageAdapter, private val activity: ServerActivity, private val processRunner: MediaProcessRunner) {
    private val client = GeminiCaptionClient()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "document-worker").apply { isDaemon=true } }
    private val busy = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var currentAssetId: Long? = null
    @Volatile private var error: String? = null
    @Volatile private var retryAt = 0L
    fun status() = mapOf("enabled" to DocumentRepository.enabled(), "running" to busy.get(),
        "currentAssetId" to currentAssetId, "error" to error, "retryAt" to retryAt.takeIf { it > System.currentTimeMillis() },
        "provider" to "GEMINI", "model" to props.caption.geminiModel)

    @Scheduled(fixedDelay=3000, initialDelay=15000)
    fun tick() {
        if(closed || activity.blocked("DOCUMENT") || !DocumentRepository.enabled() || System.currentTimeMillis()<retryAt || !busy.compareAndSet(false,true)) return
        executor.submit {
            if(!activity.enter("DOCUMENT")) { busy.set(false); return@submit }
            try {
                val cfg=props.caption
                client.key(cfg)
                val job=queue.claim("DOCUMENT") ?: return@submit
                currentAssetId=job.assetId
                var stage="문서 이미지 준비"
                try {
                    val jpeg=originals.withReadableFile(job.relPath) { source ->
                        if(source.fileName.toString().substringAfterLast('.').lowercase() in ThumbnailService.IMAGEIO_EXTENSIONS) DocumentImage.jpeg(source)
                        else {
                            java.nio.file.Files.createDirectories(props.uploadTmpDir)
                            val temp=java.nio.file.Files.createTempFile(props.uploadTmpDir,"document-ocr-",".jpg")
                            try {
                                processRunner.run(listOf(props.ffmpegPath,"-y","-i",source.toString(),"-frames:v","1","-vf",
                                    "scale=w=min(2048\\,iw):h=min(2048\\,ih):force_original_aspect_ratio=decrease",temp.toString()))
                                DocumentImage.jpeg(temp)
                            } finally { java.nio.file.Files.deleteIfExists(temp) }
                        }
                    }
                    ProcessMonitor.checkpoint()
                    stage="Gemini OCR 응답 검증"
                    val result=DocumentAnalysis.parse(client.generate(jpeg,cfg,DocumentAnalysis.PROMPT,DocumentAnalysis.schema,
                        "MEDIA_RESOLUTION_HIGH",16384))
                    stage="문서 결과 저장"
                    queue.complete(job.jobId,"DOCUMENT") { DocumentRepository.save(job.assetId,result,"gemini:${cfg.geminiModel}") }
                    error=null
                } catch(e: CaptionUnavailableException) {
                    queue.release(job.jobId,"DOCUMENT")
                    throw e
                } catch(e: Exception) {
                    // Model output / OCR contents never enter operational logs or last_error.
                    queue.fail(job.jobId,"DOCUMENT","$stage 실패. 원본·분석 연결 설정을 확인한 뒤 재시도하세요.")
                    if(!ProcessMonitor.cancelled()) error="사진 #${job.assetId} 문서 분석 실패"
                }
            } catch(e: Exception) {
                if(ProcessMonitor.cancelled()) return@submit
                error=if(e is CaptionUnavailableException) e.message else "문서 분석 연결 또는 저장소를 확인하세요."
                retryAt=System.currentTimeMillis()+(e as? CaptionUnavailableException)?.retrySeconds?.times(1000).let { it ?: 60000L }
                activity.issue("DOCUMENT",error ?: "문서 분석 연결 실패",retryAt)
            } finally { currentAssetId=null; activity.leave("DOCUMENT"); busy.set(false) }
        }
    }
    fun retryNow() { retryAt=0 }
    @PreDestroy fun close() { closed=true; executor.shutdown(); executor.awaitTermination(30,TimeUnit.SECONDS) }
}
