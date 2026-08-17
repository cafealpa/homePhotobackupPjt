package com.homephoto.server.service

import com.homephoto.server.api.ImportStatusDto
import com.homephoto.server.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 서버 로컬 디스크의 기존 사진을 일괄 임포트한다.
 *
 * 2TB급 백필을 전제로 만들었다 — 스캔/진행률/남은 시간/중지가 있고,
 * 같은 볼륨이면 복사 대신 이동(rename)을 골라 디스크를 두 배로 쓰지 않을 수 있다.
 * 한 번에 한 작업만 돈다. 도중에 서버가 죽어도 해시 중복 판정 덕에 다시 돌리면 이어서 된다.
 */
@Service
class ImportService(
    private val ingestService: AssetIngestService,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    /** 경과 시간은 읽는 시점에 계산한다 (초당 한 번 폴링하는 UI가 멈춘 것처럼 보이지 않도록) */
    @Volatile private var startedAtMs = 0L
    @Volatile private var finalElapsedMs = 0L

    @Volatile private var snapshot = IDLE

    fun status(): ImportStatusDto {
        val s = snapshot
        val elapsed = if (s.running) System.currentTimeMillis() - startedAtMs else finalElapsedMs
        return s.copy(elapsedMs = elapsed, etaMs = estimateEtaMs(s, elapsed))
    }

    /**
     * 남은 시간 추정. 파일마다 크기가 크게 다르므로 바이트 기준을 우선한다.
     * 스캔 단계에선 전체 개수를 모르므로 추정하지 않는다.
     */
    private fun estimateEtaMs(s: ImportStatusDto, elapsedMs: Long): Long? {
        if (!s.running || s.phase != PHASE_IMPORTING || elapsedMs < 3000) return null
        val (done, total) = if (s.totalBytes > 0) s.processedBytes to s.totalBytes else s.processed.toLong() to s.total.toLong()
        if (done <= 0 || total <= 0 || done >= total) return null
        return elapsedMs * (total - done) / done
    }

    /** @return false면 이미 실행 중 */
    fun start(sourcePath: String, mode: String): Boolean {
        val importMode = Mode.parse(mode)
        val root = resolveSource(sourcePath)
        if (!running.compareAndSet(false, true)) return false

        cancelRequested.set(false)
        startedAtMs = System.currentTimeMillis()
        snapshot = IDLE.copy(
            running = true, phase = PHASE_SCANNING,
            mode = importMode.name, sourcePath = root.toString(),
        )

        thread(name = "import-worker", isDaemon = true) {
            try {
                run(root, importMode)
            } catch (e: Exception) {
                log.error("임포트 중단: ${e.message}", e)
                snapshot = snapshot.copy(phase = PHASE_ERROR, message = "임포트가 중단됐습니다: ${e.message}")
            } finally {
                finalElapsedMs = System.currentTimeMillis() - startedAtMs
                snapshot = snapshot.copy(running = false, currentFile = null)
                running.set(false)
            }
        }
        return true
    }

    /** 실행 중인 임포트에 중지를 요청한다. 처리 중인 파일 하나를 끝내고 멈춘다. */
    fun stop(): ImportStatusDto {
        if (running.get()) {
            cancelRequested.set(true)
            log.info("임포트 중지 요청됨")
        }
        return status()
    }

    // ── 실행 ────────────────────────────────────────────

    private fun run(root: Path, mode: Mode) {
        val files = scan(root)
        val totalBytes = files.sumOf { it.size }
        val freeBytes = runCatching { Files.getFileStore(props.storageRoot).usableSpace }.getOrDefault(0L)

        snapshot = snapshot.copy(
            total = files.size, totalBytes = totalBytes, freeBytes = freeBytes,
            scannedFiles = files.size, currentFile = null,
        )
        log.info("임포트 스캔 완료: {}개 파일 {} — {} 모드", files.size, formatBytes(totalBytes), mode.name)

        if (cancelRequested.get()) {
            finish(PHASE_CANCELLED, "스캔 중 중지했습니다.")
            return
        }
        if (files.isEmpty()) {
            finish(PHASE_DONE, "가져올 수 있는 사진·동영상이 없습니다.")
            return
        }
        if (mode == Mode.SCAN) {
            val fits = freeBytes <= 0 || totalBytes <= freeBytes - SPACE_MARGIN_BYTES
            finish(
                PHASE_DONE,
                "미리 확인 완료 — 가져올 사진·동영상 %,d개, %s. ".format(files.size, formatBytes(totalBytes)) +
                    if (fits) "복사해도 저장소 여유 공간(${formatBytes(freeBytes)})으로 충분합니다."
                    else "복사하기엔 저장소 여유 공간(${formatBytes(freeBytes)})이 모자랍니다 — 같은 드라이브라면 '이동'을 쓰세요.",
            )
            return
        }
        // 복사는 원본만큼 공간을 더 먹는다. 다 채우고 중간에 실패하는 것보다 시작 전에 막는 게 낫다.
        if (mode == Mode.COPY && freeBytes > 0 && totalBytes > freeBytes - SPACE_MARGIN_BYTES) {
            finish(
                PHASE_ERROR,
                "저장소 여유 공간이 부족합니다 — 필요 ${formatBytes(totalBytes)}, 남은 공간 ${formatBytes(freeBytes)}. " +
                    "원본과 저장소가 같은 디스크라면 '이동'을 쓰면 추가 공간 없이 옮겨집니다.",
            )
            return
        }

        snapshot = snapshot.copy(phase = PHASE_IMPORTING)
        for (file in files) {
            if (cancelRequested.get()) {
                finish(
                    PHASE_CANCELLED,
                    "중지했습니다 — %,d/%,d개까지 처리했습니다. 같은 폴더로 다시 시작하면 나머지만 이어서 진행됩니다."
                        .format(snapshot.processed, snapshot.total),
                )
                return
            }
            snapshot = snapshot.copy(currentFile = file.path.fileName.toString())
            try {
                val result = ingestService.ingest(
                    file.path, file.path.fileName.toString(),
                    expectedHash = null, fileMtime = file.mtime,
                    deviceId = IMPORT_DEVICE_ID, deviceName = "서버 임포트",
                    moveSource = mode == Mode.MOVE,
                )
                snapshot = if (result.created) snapshot.copy(imported = snapshot.imported + 1)
                else snapshot.copy(duplicates = snapshot.duplicates + 1)
            } catch (e: Exception) {
                log.warn("임포트 실패 $file: ${e.message}")
                snapshot = snapshot.copy(failed = snapshot.failed + 1, lastError = "${file.path.fileName}: ${e.message}")
            }
            snapshot = snapshot.copy(
                processed = snapshot.processed + 1,
                processedBytes = snapshot.processedBytes + file.size,
            )
            if (snapshot.processed % PROGRESS_LOG_EVERY == 0) {
                val s = snapshot
                log.info(
                    "임포트 진행: {}/{} ({}) — 신규 {}, 중복 {}, 실패 {}",
                    s.processed, s.total, formatBytes(s.processedBytes), s.imported, s.duplicates, s.failed,
                )
            }
        }

        val s = snapshot
        finish(
            PHASE_DONE,
            "완료 — 새로 들여온 사진 %,d장, 이미 있던 사진 %,d장, 실패 %,d건. 썸네일·얼굴·장면 분석은 백그라운드에서 이어집니다."
                .format(s.imported, s.duplicates, s.failed),
        )
        log.info("임포트 완료: imported={} duplicates={} failed={}", s.imported, s.duplicates, s.failed)
    }

    private fun finish(phase: String, message: String) {
        snapshot = snapshot.copy(phase = phase, message = message, currentFile = null)
    }

    // ── 스캔 ────────────────────────────────────────────

    private data class Scanned(val path: Path, val size: Long, val mtime: java.time.Instant)

    /**
     * 대상 파일을 미리 전부 훑는다. 진행률과 남은 시간을 보여주려면 전체 개수·용량을 먼저 알아야 한다.
     * 걷는 도중 attrs를 그대로 받아 두므로 임포트 루프에서 파일마다 다시 stat하지 않는다.
     */
    private fun scan(root: Path): List<Scanned> {
        val supported = AssetIngestService.PHOTO_EXTENSIONS + AssetIngestService.VIDEO_EXTENSIONS
        val storage = runCatching { props.storageRoot.toRealPath() }.getOrNull()
        val found = ArrayList<Scanned>()
        var bytes = 0L

        Files.walkFileTree(root, object : FileVisitor<Path> {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cancelRequested.get()) return FileVisitResult.TERMINATE
                // 저장소가 원본 폴더 안에 있으면 이미 들여온 사진을 다시 훑게 된다
                if (storage != null && runCatching { dir.toRealPath() == storage }.getOrDefault(false)) {
                    log.info("스캔 제외 (저장소 폴더): {}", dir)
                    return FileVisitResult.SKIP_SUBTREE
                }
                // 사용자가 직접 지정한 시작 폴더는 이름이 뭐든 건너뛰지 않는다 (예: D:\.사진)
                val name = dir.fileName?.toString()
                if (dir != root && name != null && (name in SKIP_DIRS || name.startsWith("."))) {
                    return FileVisitResult.SKIP_SUBTREE
                }
                snapshot = snapshot.copy(currentFile = dir.toString())
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (cancelRequested.get()) return FileVisitResult.TERMINATE
                if (!attrs.isRegularFile || attrs.size() == 0L) return FileVisitResult.CONTINUE
                val ext = file.fileName.toString().substringAfterLast('.', "").lowercase()
                if (ext !in supported) return FileVisitResult.CONTINUE

                found.add(Scanned(file, attrs.size(), attrs.lastModifiedTime().toInstant()))
                bytes += attrs.size()
                if (found.size % SCAN_PROGRESS_EVERY == 0) {
                    snapshot = snapshot.copy(scannedFiles = found.size, totalBytes = bytes)
                }
                return FileVisitResult.CONTINUE
            }

            // 권한 없는 폴더 하나 때문에 전체가 멈추면 안 된다
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                log.warn("스캔 건너뜀: {} ({})", file, exc.message)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = FileVisitResult.CONTINUE
        })
        return found
    }

    /** 시작 전에 막을 수 있는 실수는 여기서 다 막는다. 던지는 메시지는 그대로 화면에 뜬다. */
    private fun resolveSource(sourcePath: String): Path {
        require(sourcePath.isNotBlank()) { "가져올 폴더 경로를 입력하세요." }
        val root = runCatching { Path.of(sourcePath).toAbsolutePath().normalize() }
            .getOrElse { throw IllegalArgumentException("경로 형식이 올바르지 않습니다: $sourcePath") }
        require(Files.isDirectory(root)) { "폴더를 찾을 수 없습니다: $root" }

        val storage = runCatching { props.storageRoot.toRealPath() }.getOrNull()
        val real = runCatching { root.toRealPath() }.getOrDefault(root)
        // 저장소 안을 가리키면 이미 들여온 사진을 자기 자신에게 다시 넣는 꼴이 된다
        require(storage == null || !real.startsWith(storage)) {
            "저장소 폴더($storage) 안쪽은 가져올 수 없습니다. 원본이 있는 다른 폴더를 지정하세요."
        }
        return root
    }

    private enum class Mode {
        /** 개수·용량만 세고 아무것도 들여오지 않는다 */
        SCAN,

        /** 원본을 남겨 두고 저장소로 복사 (기본) */
        COPY,

        /** 원본을 저장소로 이동 — 같은 볼륨이면 rename이라 즉시 끝나고 공간도 안 든다 */
        MOVE,
        ;

        companion object {
            fun parse(value: String): Mode = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("알 수 없는 임포트 방식: $value")
        }
    }

    companion object {
        const val IMPORT_DEVICE_ID = "server-import"

        const val PHASE_IDLE = "IDLE"
        const val PHASE_SCANNING = "SCANNING"
        const val PHASE_IMPORTING = "IMPORTING"
        const val PHASE_DONE = "DONE"
        const val PHASE_CANCELLED = "CANCELLED"
        const val PHASE_ERROR = "ERROR"

        private const val SCAN_PROGRESS_EVERY = 200
        private const val PROGRESS_LOG_EVERY = 200

        /** 복사 모드에서 남겨 둘 최소 여유 공간 (DB·썸네일·OS가 쓸 자리) */
        private const val SPACE_MARGIN_BYTES = 5L * 1024 * 1024 * 1024

        /** NAS·OS가 만드는 캐시/휴지통 폴더 — 훑어 봐야 나오는 게 없다 */
        private val SKIP_DIRS = setOf(
            "@eaDir", "#recycle", "\$RECYCLE.BIN", "System Volume Information",
            "node_modules", ".thumbnails",
        )

        private val IDLE = ImportStatusDto(
            running = false, phase = PHASE_IDLE, mode = null, sourcePath = null,
            total = 0, processed = 0, imported = 0, duplicates = 0, failed = 0,
            scannedFiles = 0, totalBytes = 0, processedBytes = 0, freeBytes = 0,
            elapsedMs = 0, etaMs = null, currentFile = null, lastError = null, message = null,
        )

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1L shl 40 -> "%.2fTB".format(bytes.toDouble() / (1L shl 40))
            bytes >= 1L shl 30 -> "%.1fGB".format(bytes.toDouble() / (1L shl 30))
            bytes >= 1L shl 20 -> "%.0fMB".format(bytes.toDouble() / (1L shl 20))
            else -> "%.0fKB".format(bytes.toDouble() / 1024)
        }
    }
}
