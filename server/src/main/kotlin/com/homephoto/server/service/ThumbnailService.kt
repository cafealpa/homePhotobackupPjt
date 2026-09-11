package com.homephoto.server.service

import com.homephoto.server.config.AppProperties
import net.coobird.thumbnailator.Thumbnails
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

/**
 * 썸네일 생성. JPEG/PNG 등 ImageIO 지원 포맷은 순수 Java(Thumbnailator)로,
 * HEIC·동영상은 ffmpeg로 처리한다. ffmpeg가 없으면 해당 파일은 실패 처리되고
 * 설치 후 재시도하면 된다.
 */
@Service
class ThumbnailService(
    private val props: AppProperties,
    private val storage: ThumbnailStorage,
    private val locks: AssetLocks,
    private val processRunner: MediaProcessRunner,
) {

    fun generate(hash: String, originalRelPath: String, mediaType: String) = locks.withHash(hash) {
        val original = props.storageRoot.resolve(originalRelPath)
        require(Files.exists(original)) { "original not found: $originalRelPath" }
        val ext = originalRelPath.substringAfterLast('.', "").lowercase()

        for (size in SIZES) {
            val out = thumbPath(hash, size)
            if (Files.exists(out)) continue
            Files.createDirectories(out.parent)
            AtomicFiles.write(out) { temp ->
                when {
                    mediaType == "PHOTO" && ext in IMAGEIO_EXTENSIONS -> {
                        Thumbnails.of(original.toFile())
                            .size(size, size)
                            .outputFormat("jpg")
                            .outputQuality(0.85)
                            .toFile(temp.toFile())
                    }
                    else -> ffmpegThumbnail(original, temp, size, isVideo = mediaType == "VIDEO")
                }
            }
        }
    }

    fun thumbPath(hash: String, size: Int): Path = storage.thumbPath(hash, size)
    fun migrationStatus() = storage.migrationStatus()
    fun relocate(oldDir: Path) = storage.relocate(oldDir)

    private fun ffmpegThumbnail(input: Path, output: Path, size: Int, isVideo: Boolean) {
        val command = buildList {
            add(props.ffmpegPath)
            add("-y")
            add("-i"); add(input.toString())
            if (isVideo) { add("-frames:v"); add("1") }
            add("-vf"); add("scale=min($size\\,iw):-2")
            add(output.toString())
        }
        processRunner.run(command)
    }

    companion object {
        val SIZES = listOf(400, 1600)
        val IMAGEIO_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp")
    }
}
