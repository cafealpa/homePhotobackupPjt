package com.homephoto.server.worker

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.*
import com.homephoto.server.service.*
import com.homephoto.server.storage.StorageAdapter
import jakarta.annotation.PreDestroy
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 추론은 전용 단일 스레드, ONNX 내부 병렬도는 2로 제한해 HTTP/백업을 방해하지 않는다. */
@Component
class FaceWorker(private val props: AppProperties, private val engine: FaceEngine, private val queue: JobQueueService,
                 private val originals: StorageAdapter, private val grouping: FaceGroupingService, private val activity: ServerActivity) {
    private val running = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "face-worker").apply { isDaemon = true } }
    @Volatile private var error: String? = null
    @Volatile private var retryAfter = 0L
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    data class Status(val enabled: Boolean, val running: Boolean, val error: String?, val modelDir: String,
                      val pending: Long, val completed: Long, val failed: Long, val faces: Long)
    fun status(): Status = transaction {
        fun count(status: String) = (Jobs innerJoin Assets).selectAll().where {
            (Jobs.jobType eq "FACE") and (Jobs.status eq status) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull()
        }.count()
        Status(props.face.enabled, running.get(), error, engine.modelDirectory().toAbsolutePath().toString(),
            count("PENDING") + count("RUNNING"), count("DONE"), count("FAILED"), Faces.selectAll().count())
    }
    @Scheduled(fixedDelay = 2000) fun tick() {
        if (!props.face.enabled || activity.blocked("FACE") || activity.retryWaiting("FACE") || System.currentTimeMillis() < retryAfter || !running.compareAndSet(false, true)) return
        executor.submit {
            if (!activity.enter("FACE")) { running.set(false); return@submit }
            try {
                // 모델 오류는 작업을 가져오기 전에 검사: 모든 사진을 FAILED로 만들지 않는다.
                engine.prepare()
                error = null
                grouping.assignPending()
                repeat(10) {
                    if (!props.face.enabled || activity.blocked("FACE")) return@submit
                    val job = queue.claim("FACE") ?: return@submit
                    try {
                        ProcessMonitor.stage("얼굴 검출·임베딩")
                        ProcessMonitor.checkpoint()
                        val faces = originals.withReadableFile(job.relPath) { path -> ProcessMonitor.cpu { engine.analyze(path) } }
                        queue.complete(job.jobId, "FACE") { asset ->
                            Faces.deleteWhere { assetId eq asset }
                            faces.forEach { face -> Faces.insert {
                                it[assetId] = asset; it[bboxX] = face.x; it[bboxY] = face.y; it[bboxW] = face.w; it[bboxH] = face.h
                                it[embedding] = ExposedBlob(FaceGroupingService.encode(face.vector))
                            } }
                        }
                    } catch (e: java.io.IOException) {
                        queue.release(job.jobId, "FACE")
                        throw e
                    } catch (e: Exception) {
                        queue.fail(job.jobId, "FACE", e.message)
                        log.warn("얼굴 분석 실패 asset={}: {}", job.assetId, e.message)
                    }
                    // 매 사진 완료 후 표시 가능. 전체 큐가 비기를 기다리지 않는다.
                    grouping.assignPending()
                }
            } catch (e: Exception) {
                if (com.homephoto.server.service.ProcessMonitor.cancelled()) return@submit
                error = e.message ?: e.javaClass.simpleName
                retryAfter = System.currentTimeMillis() + 60_000
                activity.issue("FACE",error ?: "작업 연결 실패",retryAfter)
                log.warn("얼굴 워커 대기: {}", error)
            } finally { running.set(false); activity.leave("FACE") }
        }
    }
    fun retryNow() { retryAfter=0; error=null; activity.clearIssue("FACE") }
    @PreDestroy fun close() { executor.shutdown() }
}
