package com.homephoto.server.search

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Faces
import com.homephoto.server.service.PhotoDateRange
import com.homephoto.server.service.ThumbnailService
import com.homephoto.server.service.ServerActivity
import jakarta.annotation.PreDestroy
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/** API name retained for clients; all work now runs inside the JVM. */
@Service
class PhotoSearchProcess(private val props: PhotoSearchProperties, private val app: AppProperties,
                         private val thumbnails: ThumbnailService, private val activity: ServerActivity) : SearchBackend {
    data class Status(val state: String, val message: String, val canStart: Boolean = false,
        val canStop: Boolean = false, val indexedPhotos: Long = 0, val indexedFaces: Long = 0,
        val processed: Long = 0, val failed: Long = 0, val lastError: String? = null,
        val model: String = SiglipEncoder.ID)
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "photo-vector-index").apply { isDaemon = true } }
    private val busy = AtomicBoolean()
    private val resources = Any()
    @Volatile private var paused = false
    @Volatile private var closed = false
    @Volatile private var nextScan = 0L
    @Volatile private var state = if (props.enabled) "starting" else "disabled"
    @Volatile private var indexedPhotos = 0L
    @Volatile private var indexedFaces = 0L
    @Volatile private var processed = 0L
    @Volatile private var failed = 0L
    @Volatile private var lastError: String? = null
    private var photos: LocalVectorIndex? = null
    private var faces: LocalVectorIndex? = null
    private var encoder: PhotoEncoder? = null
    internal var encoderFactory: (Path) -> PhotoEncoder = ::SiglipEncoder
    private val root get() = if (props.indexDir.isBlank()) app.storageRoot.resolve("vector-search") else Path.of(props.indexDir)
    private fun stopped() = closed || paused || activity.blocked("PHOTO_INDEX")

    fun status(): Status {
        val current = if (closed) "stopped" else if (!props.enabled) "disabled" else if (paused) {
            if (busy.get()) "stopping" else "stopped"
        } else state
        val message = when (current) {
            "disabled" -> "검색이 꺼져 있어요. homephoto.search.enabled 설정을 켜고 서버를 재시작해 주세요."
            "stopped" -> "자동 인덱싱을 중지했어요. 준비된 인덱스는 계속 검색할 수 있어요."
            "stopping" -> "현재 사진 처리를 마친 뒤 인덱싱을 중지해요."
            "starting" -> "JVM 검색 모델과 인덱스를 준비하고 있어요."
            "indexing" -> "사진과 얼굴 검색 인덱스를 갱신하고 있어요."
            "model_missing" -> "사진 검색 모델이 없어요. prepare-search-model.ps1로 준비해 주세요. 얼굴 인덱싱은 계속 동작해요."
            "failed" -> "검색 인덱싱에 실패했어요. 오류를 확인해 주세요. 잠시 후 자동 재시도해요."
            "partial" -> "일부 항목을 처리하지 못했어요. 다음 순회에서 다시 시도해요."
            else -> "검색 준비가 됐어요. 새 사진과 얼굴은 자동으로 반영해요."
        }
        return Status(current, message, props.enabled && !closed && (paused || current in setOf("failed", "model_missing", "partial")) && !busy.get(),
            props.enabled && !closed && !paused, indexedPhotos, indexedFaces, processed, failed, lastError)
    }
    @Synchronized fun start(): Status {
        if (!props.enabled || closed) throw ResponseStatusException(HttpStatus.CONFLICT, status().message)
        check(activity.draining || !activity.blocked("PHOTO_INDEX")) { "모니터링 화면에서 사진 검색 인덱싱을 시작해 주세요." }
        paused = false; nextScan = 0
        tick()
        return status()
    }
    @Synchronized fun stop(): Status { paused = true; return status() }

    @Scheduled(fixedDelay = 1000, initialDelay = 15000)
    @Synchronized fun tick() {
        if (!props.enabled || stopped() || System.currentTimeMillis() < nextScan || !busy.compareAndSet(false, true)) return
        executor.submit {
            if (!activity.enter("PHOTO_INDEX")) { busy.set(false); return@submit }
            try {
                com.homephoto.server.service.ProcessMonitor.stage("모델 준비·검색 인덱스 갱신")
                scan()
                if(!activity.blocked("PHOTO_INDEX")) activity.success("PHOTO_INDEX","검색 인덱스 갱신 완료")
            }
            catch (e: Exception) { fail(e) }
            catch (e: LinkageError) { fail(e) }
            finally {
                nextScan = System.currentTimeMillis() + if (state == "failed") 60000 else 300000
                try { if (closed) closeResources() }
                finally { activity.leave("PHOTO_INDEX"); busy.set(false) }
            }
        }
    }
    private fun fail(e: Throwable) {
        activity.issue("PHOTO_INDEX","검색 모델 또는 인덱스 준비 실패",System.currentTimeMillis()+60000)
        state = "failed"
        lastError = "검색 모델 또는 인덱스를 준비하지 못했어요 (${e.javaClass.simpleName}). 서버 로그를 확인해 주세요."
        log.warn("JVM 검색 인덱싱 실패", e)
    }
    internal fun scan() {
        if (stopped() || !props.enabled) return
        try { scanOnce() }
        finally {
            // Publish the last partial batch when pausing or draining, without pruning an incomplete scan.
            synchronized(resources) {
                faces?.let { it.commit(); indexedFaces = it.count() }
                photos?.let { it.commit(); indexedPhotos = it.count() }
            }
        }
    }
    private fun scanOnce() {
        processed = 0; failed = 0; lastError = null; state = "indexing"
        synchronized(resources) { if (faces == null) faces = LocalVectorIndex(root.resolve(FACE_VECTOR_MODEL), 512) }
        scanFaces()
        if (stopped()) return
        if (encoder == null) {
            val model = Path.of(props.modelDir)
            if (!SiglipEncoder.HASHES.keys.all { Files.isRegularFile(model.resolve(it)) }) { state = "model_missing"; return }
            state = "starting"
            val candidate = encoderFactory(model)
            try {
                candidate.prepare()
                synchronized(resources) {
                    if (stopped()) { candidate.close(); return }
                    photos = LocalVectorIndex(root.resolve(SiglipEncoder.ID), SiglipEncoder.DIMENSION)
                    encoder = candidate
                }
            } catch (e: Throwable) { candidate.close(); throw e }
        }
        state = "indexing"
        scanPhotos()
        if (!stopped()) state = if (failed > 0) "partial" else "ready"
    }
    private fun active(row: ResultRow) = row[Assets.deletedAt] == null && row[Assets.purgedAt] == null &&
        row[Assets.sourceTag] == null && row[Assets.mediaType] == "PHOTO"
    private fun itemFailed(kind: String, id: Long, e: Exception) {
        failed++
        lastError = "$kind #$id 처리 실패 (${e.javaClass.simpleName}). 다음 순회에서 재시도해요."
        log.warn("JVM 검색 {} #{} 처리 실패", kind, id, e)
    }
    private fun scanFaces() {
        data class Item(val id: Long, val asset: Long, val bytes: ByteArray?)
        var after = 0L
        val seen = HashSet<Long>()
        while (!stopped()) {
            val page = transaction {
                Faces.innerJoin(Assets).selectAll().where { Faces.id greater after }.orderBy(Faces.id to SortOrder.ASC).limit(100).map {
                    Item(it[Faces.id], it[Faces.assetId], if (active(it) && !it[Faces.hidden]) it[Faces.embedding].bytes else null)
                }
            }
            for (item in page) {
                if (stopped()) return
                seen.add(item.id)
                synchronized(resources) {
                    val store = faces!!
                    try {
                        if (item.bytes == null) store.delete(item.id)
                        else {
                            val hash = faceFingerprint(item.bytes)
                            val old = store.lookup(item.id)
                            if (old?.fingerprint != hash || old.assetId != item.asset) store.put(item.id, item.asset, hash, 0, faceVector(item.bytes))
                        }
                    } catch (e: IllegalArgumentException) { store.delete(item.id); itemFailed("얼굴", item.id, e) }
                }
                processed++
            }
            synchronized(resources) { faces!!.commit(); indexedFaces = faces!!.count() }
            if (page.size < 100) {
                if (!stopped()) synchronized(resources) { faces!!.retain(seen); faces!!.commit(); indexedFaces = faces!!.count() }
                return
            }
            after = page.last().id
        }
    }
    private fun scanPhotos() {
        data class Item(val id: Long, val hash: String, val takenAt: String?, val active: Boolean)
        var after = 0L
        val seen = HashSet<Long>()
        while (!stopped()) {
            val page = transaction {
                Assets.selectAll().where { Assets.id greater after }.orderBy(Assets.id to SortOrder.ASC).limit(100).map {
                    Item(it[Assets.id], it[Assets.hash], it[Assets.takenAt], active(it))
                }
            }
            for (item in page) {
                if (stopped()) return
                seen.add(item.id)
                try {
                    synchronized(resources) {
                        val store = photos!!
                        if (!item.active) store.delete(item.id)
                        else {
                            val day = item.takenAt?.take(10)?.let { LocalDate.parse(it).toEpochDay() } ?: Long.MIN_VALUE
                            val old = store.lookup(item.id)
                            if (old?.fingerprint != item.hash) {
                                val image = ImageIO.read(thumbnails.thumbPath(item.hash, 400).toFile())
                                    ?: throw IllegalStateException("썸네일을 읽을 수 없습니다.")
                                store.put(item.id, item.id, item.hash, day, encoder!!.image(image))
                            } else if (old.day != day) store.put(item.id, item.id, item.hash, day, old.vector)
                        }
                    }
                } catch (e: Exception) { itemFailed("사진", item.id, e) }
                processed++
                if (processed % 16L == 0L) synchronized(resources) { photos!!.commit(); indexedPhotos = photos!!.count() }
            }
            synchronized(resources) { photos!!.commit(); indexedPhotos = photos!!.count() }
            if (page.size < 100) {
                if (!stopped()) synchronized(resources) { photos!!.retain(seen); photos!!.commit(); indexedPhotos = photos!!.count() }
                return
            }
            after = page.last().id
        }
    }
    override fun photos(text: String, range: PhotoDateRange?): SearchBackend.Result = synchronized(resources) {
        if (!props.enabled || closed) throw PhotoSearchUnavailable()
        val store = photos ?: throw PhotoSearchUnavailable()
        try { SearchBackend.Result(store.search(encoder!!.text(text), from = range?.start?.toEpochDay(), to = range?.end?.toEpochDay()), store.count()) }
        catch (e: Exception) { throw PhotoSearchUnavailable() }
    }
    override fun faces(embedding: ByteArray, sourceAsset: Long): SearchBackend.Result = synchronized(resources) {
        if (!props.enabled || closed) throw PhotoSearchUnavailable()
        val store = faces ?: throw PhotoSearchUnavailable()
        SearchBackend.Result(store.search(faceVector(embedding), excludeAsset = sourceAsset), store.count())
    }
    @PreDestroy fun shutdown() {
        synchronized(this) { closed = true; executor.shutdown() }
        if (executor.awaitTermination(30, TimeUnit.SECONDS)) closeResources()
        else { executor.shutdownNow(); log.warn("검색 작업 종료를 기다리는 중입니다. 작업이 끝나면 모델과 인덱스를 닫습니다.") }
    }
    private fun closeResources() = synchronized(resources) {
        photos?.close(); faces?.close(); encoder?.close(); photos = null; faces = null; encoder = null
    }
}
