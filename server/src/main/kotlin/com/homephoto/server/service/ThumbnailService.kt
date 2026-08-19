package com.homephoto.server.service

import com.homephoto.server.config.AppProperties
import net.coobird.thumbnailator.Thumbnails
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * 썸네일 생성. JPEG/PNG 등 ImageIO 지원 포맷은 순수 Java(Thumbnailator)로,
 * HEIC·동영상은 ffmpeg로 처리한다. ffmpeg가 없으면 해당 파일은 실패 처리되고
 * 설치 후 재시도하면 된다.
 */
@Service
class ThumbnailService(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun generate(hash: String, originalRelPath: String, mediaType: String) {
        val original = props.storageRoot.resolve(originalRelPath)
        require(Files.exists(original)) { "original not found: $originalRelPath" }
        val ext = originalRelPath.substringAfterLast('.', "").lowercase()

        for (size in SIZES) {
            val out = thumbPath(hash, size)
            if (Files.exists(out)) continue
            Files.createDirectories(out.parent)
            when {
                mediaType == "PHOTO" && ext in IMAGEIO_EXTENSIONS -> {
                    Thumbnails.of(original.toFile())
                        .size(size, size)
                        .outputFormat("jpg")
                        .outputQuality(0.85)
                        .toFile(out.toFile())
                }
                else -> ffmpegThumbnail(original, out, size, isVideo = mediaType == "VIDEO")
            }
        }
    }

    /**
     * 썸네일 경로: thumbs/ab/cd/{hash}_{size}.jpg — 해시 앞 4자리로 2단계 샤딩(256×256 폴더).
     * 한 폴더에 수십만 파일이 쌓이면 NTFS 디렉터리 조회·백신 검사가 느려져 그리드 로딩에 그대로 반영된다.
     * 옛 평면 경로(thumbs/{hash}_{size}.jpg)에 파일이 남아 있으면 이 자리에서 옮긴다
     * (시작 시 일괄 마이그레이션이 끝나기 전에 들어온 요청 대비).
     */
    fun thumbPath(hash: String, size: Int): Path {
        val dir = props.thumbsDir
        val sharded = shardedPath(dir, hash, size)
        if (!Files.exists(sharded)) {
            // 후보: 지금 폴더의 옛 평면 경로, 그리고 (폴더를 옮긴 뒤라면) 이전 폴더들의 샤딩/평면 경로
            val candidates = sequence {
                yield(dir.resolve("${hash}_$size.jpg"))
                for (src in oldDirs) {
                    yield(shardedPath(src, hash, size))
                    yield(src.resolve("${hash}_$size.jpg"))
                }
            }
            val found = candidates.firstOrNull { Files.exists(it) }
            if (found != null) runCatching { moveFile(found, sharded) }
        }
        return sharded
    }

    private fun shardedPath(base: Path, hash: String, size: Int): Path =
        base.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve("${hash}_$size.jpg")

    /**
     * 파일 이동. 같은 볼륨이면 rename 한 번. 다른 볼륨(HDD→SSD)이면 `.part`로 복사한 뒤 제자리 rename —
     * 그리드가 복사 중인 반쪽짜리 파일을 읽는 일이 없게 한다. 목적지에 이미 있으면 원본만 지운다.
     */
    private fun moveFile(src: Path, dst: Path) {
        if (src == dst) return
        Files.createDirectories(dst.parent)
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            val part = dst.resolveSibling(dst.name + ".part")
            Files.copy(src, part, StandardCopyOption.REPLACE_EXISTING)
            if (Files.exists(dst)) Files.delete(part)
            else Files.move(part, dst, StandardCopyOption.ATOMIC_MOVE)
            Files.deleteIfExists(src)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            Files.deleteIfExists(src)
        }
    }

    // ── 썸네일 폴더 이전 (설정의 '썸네일 폴더' 변경) ─────────────────────────────
    //
    // 폴더를 바꾸면 새 파일은 곧장 새 폴더에 생기고, 옛 폴더에 남은 파일은 백그라운드 스레드가
    // 하나씩 옮긴다. 옮기는 동안에도 [thumbPath]가 옛 폴더를 들춰보므로 그리드는 끊기지 않는다.
    // 옛 폴더 목록은 새 폴더의 [MIGRATE_MARKER] 파일에 적어 두어, 도중에 서버를 껐다 켜도 이어서 옮긴다.

    /** 아직 파일이 남아 있을 수 있는 이전 썸네일 폴더들 */
    private val oldDirs = CopyOnWriteArrayList<Path>()
    private val migrating = AtomicBoolean(false)
    /** 폴더가 바뀔 때마다 올린다 — 돌고 있는 이전 스레드가 이를 보고 멈춘다 */
    private val migrationGen = AtomicInteger()
    private val migratedCount = AtomicLong()
    private val migrateFailed = AtomicLong()
    @Volatile private var migrateTotal: Long = -1

    data class MigrationStatus(
        val running: Boolean,
        /** 아직 비우지 못한 이전 폴더들 */
        val sources: List<String>,
        val moved: Long,
        val failed: Long,
        /** 현재 폴더에서 옮길 파일 수 (세기 전이면 -1) */
        val total: Long,
    )

    fun migrationStatus() = MigrationStatus(
        running = migrating.get(),
        sources = oldDirs.map { it.toString() },
        moved = migratedCount.get(),
        failed = migrateFailed.get(),
        total = migrateTotal,
    )

    /** 설정에서 썸네일 폴더가 [oldDir] → 현재 [AppProperties.thumbsDir]로 바뀌었을 때 호출 */
    fun relocate(oldDir: Path) {
        val newDir = props.thumbsDir.toAbsolutePath().normalize()
        val old = oldDir.toAbsolutePath().normalize()
        if (old == newDir) return
        Files.createDirectories(newDir)
        oldDirs.remove(newDir)           // 예전에 떠났던 폴더로 되돌아온 경우
        if (old !in oldDirs) oldDirs.add(0, old)
        saveMarker(newDir)
        log.info("썸네일 폴더 변경: {} → {} — 기존 파일을 백그라운드로 옮깁니다", old, newDir)
        migrationGen.incrementAndGet()
        ensureMigrationRunning()
    }

    private fun markerFile(dir: Path) = dir.resolve(MIGRATE_MARKER)

    private fun saveMarker(dir: Path) = runCatching {
        if (oldDirs.isEmpty()) Files.deleteIfExists(markerFile(dir))
        else Files.writeString(markerFile(dir), oldDirs.joinToString("\n") + "\n")
    }.onFailure { log.warn("썸네일 이전 표시 파일 기록 실패: {}", it.message) }

    private fun loadMarker() {
        val marker = markerFile(props.thumbsDir)
        if (!Files.isRegularFile(marker)) return
        runCatching {
            Files.readAllLines(marker).map { it.trim() }.filter { it.isNotEmpty() }
                .map { Path.of(it).toAbsolutePath().normalize() }
                .filter { Files.isDirectory(it) && it != props.thumbsDir.toAbsolutePath().normalize() }
        }.onSuccess { dirs ->
            if (dirs.isNotEmpty()) {
                oldDirs.addAll(dirs)
                log.info("썸네일 폴더 이전을 이어서 진행합니다: {}", dirs)
            }
        }
    }

    private fun ensureMigrationRunning() {
        if (!migrating.compareAndSet(false, true)) return
        Thread({
            var gen = migrationGen.get()
            try {
                do {
                    gen = migrationGen.get()
                    runMigration(gen)
                } while (gen != migrationGen.get()) // 도는 사이 폴더가 또 바뀌었으면 새 목적지로 다시
            } finally {
                migrating.set(false)
                // 루프를 빠져나온 직후 폴더가 바뀌었는데 relocate()의 CAS가 실패했을 수 있다
                if (gen != migrationGen.get()) ensureMigrationRunning()
            }
        }, "thumb-relocation").apply { isDaemon = true }.start()
    }

    private fun hasThumbFiles(dir: Path): Boolean = runCatching {
        Files.walk(dir).use { it.anyMatch { f -> f.isRegularFile() && THUMB_NAME.matches(f.name) } }
    }.getOrDefault(false)

    /** 옛 폴더들을 차례로 비운다. [gen]이 바뀌면(목적지가 또 바뀜) 중간에 멈춘다. */
    private fun runMigration(gen: Int) {
        val target = props.thumbsDir.toAbsolutePath().normalize()
        for (src in oldDirs.toList()) {
            if (migrationGen.get() != gen) return
            if (!Files.isDirectory(src)) { oldDirs.remove(src); saveMarker(target); continue }
            val started = System.currentTimeMillis()
            migratedCount.set(0); migrateFailed.set(0); migrateTotal = -1
            migrateTotal = runCatching {
                Files.walk(src).use { it.filter { f -> f.isRegularFile() && THUMB_NAME.matches(f.name) }.count() }
            }.getOrDefault(-1L)
            log.info("썸네일 이전 시작: {} → {} ({}개)", src, target, migrateTotal)
            var aborted = false
            try {
                Files.walk(src).use { stream ->
                    for (file in stream) {
                        if (migrationGen.get() != gen) { aborted = true; break }
                        if (!file.isRegularFile()) continue
                        val m = THUMB_NAME.matchEntire(file.name) ?: continue
                        val (hash, size) = m.destructured
                        try {
                            moveFile(file, shardedPath(target, hash, size.toInt()))
                            val n = migratedCount.incrementAndGet()
                            if (n % 5000 == 0L) log.info("썸네일 이전 진행: {}/{}", n, migrateTotal)
                        } catch (e: java.nio.file.NoSuchFileException) {
                            // thumbPath()가 먼저 옮겨 갔다 — 정상
                        } catch (e: Exception) {
                            migrateFailed.incrementAndGet()
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("썸네일 이전 중단 ({}): {}", src, e.message)
                aborted = true
            }
            if (aborted) return
            removeEmptyDirs(src)
            if (!hasThumbFiles(src)) {
                oldDirs.remove(src)
                saveMarker(target)
            }
            log.info("썸네일 이전 완료: {} → {} — {}개 이동{} ({}초){}", src, target, migratedCount.get(),
                if (migrateFailed.get() > 0) ", ${migrateFailed.get()}개 실패" else "",
                (System.currentTimeMillis() - started) / 1000,
                if (src in oldDirs) " — 일부가 남아 다음에 다시 시도합니다" else "")
        }
    }

    /** 비워진 샤딩 하위 폴더 정리 (루트 자체는 남긴다) */
    private fun removeEmptyDirs(root: Path) = runCatching {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).filter { it != root && Files.isDirectory(it) }.forEach { d ->
                runCatching { Files.delete(d) } // 비어 있지 않으면 실패 — 무시
            }
        }
    }

    /**
     * 시작 후 백그라운드에서 평면 폴더에 남은 옛 썸네일을 샤딩 폴더로 옮긴다 (같은 볼륨이라 rename 한 번).
     * 이미 옮겨졌으면 할 일이 없어 순식간에 끝난다.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        migrateLegacyThumbs()
        // 지난번 폴더 이전이 끝나기 전에 서버가 꺼졌으면 이어서
        loadMarker()
        if (oldDirs.isNotEmpty()) { migrationGen.incrementAndGet(); ensureMigrationRunning() }
    }

    fun migrateLegacyThumbs() {
        if (!Files.isDirectory(props.thumbsDir)) return
        Thread({
            var moved = 0
            var failed = 0
            val started = System.currentTimeMillis()
            try {
                Files.list(props.thumbsDir).use { stream ->
                    stream.filter { it.isRegularFile() }.forEach { file ->
                        val m = THUMB_NAME.matchEntire(file.name) ?: return@forEach
                        val (hash, size) = m.destructured
                        val target = shardedPath(props.thumbsDir, hash, size.toInt())
                        try {
                            Files.createDirectories(target.parent)
                            Files.move(file, target, StandardCopyOption.ATOMIC_MOVE)
                            moved++
                        } catch (e: java.nio.file.FileAlreadyExistsException) {
                            runCatching { Files.delete(file) } // 이미 샤딩 경로에 있으면 옛 파일만 정리
                        } catch (e: Exception) {
                            failed++
                        }
                    }
                }
            } catch (e: Exception) {
                log.warn("썸네일 폴더 샤딩 마이그레이션 중단: {}", e.message)
            }
            if (moved > 0 || failed > 0) {
                log.info("썸네일 폴더 샤딩 마이그레이션: {}개 이동{} ({}초)", moved,
                    if (failed > 0) ", ${failed}개 실패" else "", (System.currentTimeMillis() - started) / 1000)
            }
        }, "thumb-shard-migration").apply { isDaemon = true }.start()
    }

    private fun ffmpegThumbnail(input: Path, output: Path, size: Int, isVideo: Boolean) {
        val command = buildList {
            add(props.ffmpegPath)
            add("-y")
            add("-i"); add(input.toString())
            if (isVideo) { add("-frames:v"); add("1") }
            add("-vf"); add("scale=min($size\\,iw):-2")
            add(output.toString())
        }
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            throw IllegalStateException("ffmpeg not available (${props.ffmpegPath}) — HEIC/동영상 썸네일에 필요", e)
        }
        val log = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("ffmpeg timeout: $input")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("ffmpeg failed (exit ${process.exitValue()}): ${log.takeLast(500)}")
        }
    }

    companion object {
        val SIZES = listOf(400, 1600)
        val IMAGEIO_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp")
        /** 썸네일 파일명 ({hash}_{size}.jpg) — 평면·샤딩 어디에 있든 이름은 같다 */
        private val THUMB_NAME = Regex("""([0-9a-f]{64})_(\d+)\.jpg""")
        /** 새 썸네일 폴더에 두는 "아직 파일이 남은 이전 폴더" 목록 파일 */
        const val MIGRATE_MARKER = ".migrate-from"
    }
}
