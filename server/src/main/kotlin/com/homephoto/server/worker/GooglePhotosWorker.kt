package com.homephoto.server.worker

import com.homephoto.server.config.AppProperties
import com.homephoto.server.publication.GooglePhotosPublicationProcessor
import com.homephoto.server.publication.GooglePhotosPublicationQueue
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 원본/썸네일/ML 워커의 스레드를 점유하지 않는 단일 계정 게시 워커. */
@Component
class GooglePhotosWorker(private val props: AppProperties, private val queue: GooglePhotosPublicationQueue,
                         private val processor: GooglePhotosPublicationProcessor) {
    private val running = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "google-photos-worker").apply { isDaemon = true } }
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var recoveryNeeded = false

    @Scheduled(fixedDelay = 3000)
    fun tick() {
        if (!props.googlePhotos.enabled || !running.compareAndSet(false, true)) return
        executor.submit {
            try {
                if (recoveryNeeded) { queue.recover(); recoveryNeeded = false }
                repeat(10) {
                    if (!props.googlePhotos.enabled) return@submit
                    val job = queue.claim() ?: return@submit
                    processor.process(job)
                }
            } catch (error: Exception) {
                recoveryNeeded = true
                log.warn("Google Photos 워커 처리 중단 ({}). 다음 실행에서 영속 상태를 복구합니다.", error.javaClass.simpleName)
            } finally { running.set(false) }
        }
    }

    @PreDestroy fun shutdown() { executor.shutdown(); runCatching { executor.awaitTermination(10, TimeUnit.SECONDS) } }
}
