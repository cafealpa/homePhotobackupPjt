package com.homephoto.server.service

import com.homephoto.server.config.AppProperties
import net.coobird.thumbnailator.Thumbnails
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 썸네일 생성. JPEG/PNG 등 ImageIO 지원 포맷은 순수 Java(Thumbnailator)로,
 * HEIC·동영상은 ffmpeg로 처리한다. ffmpeg가 없으면 해당 파일은 실패 처리되고
 * 설치 후 재시도하면 된다.
 */
@Service
class ThumbnailService(private val props: AppProperties) {

    fun generate(hash: String, originalRelPath: String, mediaType: String) {
        val original = props.storageRoot.resolve(originalRelPath)
        require(Files.exists(original)) { "original not found: $originalRelPath" }
        val ext = originalRelPath.substringAfterLast('.', "").lowercase()

        for (size in SIZES) {
            val out = props.thumbsDir.resolve("${hash}_$size.jpg")
            if (Files.exists(out)) continue
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

    fun thumbPath(hash: String, size: Int): Path = props.thumbsDir.resolve("${hash}_$size.jpg")

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
    }
}
