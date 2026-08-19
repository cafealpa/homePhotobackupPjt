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
import java.util.concurrent.TimeUnit
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
        val sharded = shardedPath(hash, size)
        if (!Files.exists(sharded)) {
            val legacy = props.thumbsDir.resolve("${hash}_$size.jpg")
            if (Files.exists(legacy)) runCatching {
                Files.createDirectories(sharded.parent)
                Files.move(legacy, sharded, StandardCopyOption.ATOMIC_MOVE)
            }
        }
        return sharded
    }

    private fun shardedPath(hash: String, size: Int): Path =
        props.thumbsDir.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve("${hash}_$size.jpg")

    /**
     * 시작 후 백그라운드에서 평면 폴더에 남은 옛 썸네일을 샤딩 폴더로 옮긴다 (같은 볼륨이라 rename 한 번).
     * 이미 옮겨졌으면 할 일이 없어 순식간에 끝난다.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun migrateLegacyThumbs() {
        if (!Files.isDirectory(props.thumbsDir)) return
        Thread({
            var moved = 0
            var failed = 0
            val started = System.currentTimeMillis()
            try {
                Files.list(props.thumbsDir).use { stream ->
                    stream.filter { it.isRegularFile() }.forEach { file ->
                        val m = LEGACY_NAME.matchEntire(file.name) ?: return@forEach
                        val (hash, size) = m.destructured
                        val target = shardedPath(hash, size.toInt())
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
        private val LEGACY_NAME = Regex("""([0-9a-f]{64})_(\d+)\.jpg""")
    }
}
