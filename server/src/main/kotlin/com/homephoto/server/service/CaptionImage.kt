package com.homephoto.server.service

import net.coobird.thumbnailator.Thumbnails
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

/** 뷰어용 썸네일을 변경하지 않고 분석 요청에 넣을 JPEG만 메모리에서 만든다. */
internal object CaptionImage {
    const val MAX_EDGE = 768

    fun jpeg(source: Path): ByteArray {
        val image = requireNotNull(ImageIO.read(source.toFile())) { "장면 분석 이미지를 읽을 수 없습니다." }
        try {
            val scale = (MAX_EDGE.toDouble() / maxOf(image.width, image.height)).coerceAtMost(1.0)
            return ByteArrayOutputStream().use { output ->
                Thumbnails.of(image).scale(scale).outputFormat("jpg").outputQuality(0.85).toOutputStream(output)
                output.toByteArray()
            }
        } finally { image.flush() }
    }
}
