package com.homephoto.server.document

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.filters.Flip
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

internal object DocumentImage {
    /** Decode subsampled pixels first, so large phone images do not consume full-resolution heap. */
    fun jpeg(path: Path): ByteArray {
        ImageIO.createImageInputStream(path.toFile()).use { input ->
            requireNotNull(input) { "문서 이미지를 열 수 없습니다." }
            val readers=ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "OCR은 JPEG/PNG 등 ImageIO 지원 이미지가 필요합니다." }
            val reader=readers.next()
            try {
                reader.input=input
                val width=reader.getWidth(0); val height=reader.getHeight(0)
                require(width>0 && height>0 && width.toLong()*height<=200_000_000)
                val sample=((maxOf(width,height)+4095)/4096).coerceAtLeast(1)
                val param=reader.defaultReadParam.apply { setSourceSubsampling(sample,sample,0,0) }
                val decoded=reader.read(0,param)
                try {
                    val orientation=runCatching { ImageMetadataReader.readMetadata(path.toFile())
                        .getFirstDirectoryOfType(ExifIFD0Directory::class.java)?.getInteger(ExifIFD0Directory.TAG_ORIENTATION) }.getOrNull() ?: 1
                    val image=Thumbnails.of(decoded).scale(minOf(1.0,2048.0/maxOf(decoded.width,decoded.height)))
                    when(orientation) {
                        2 -> image.addFilter(Flip.HORIZONTAL)
                        3 -> image.rotate(180.0)
                        4 -> image.addFilter(Flip.VERTICAL)
                        5 -> image.rotate(90.0).addFilter(Flip.HORIZONTAL)
                        6 -> image.rotate(90.0)
                        7 -> image.rotate(90.0).addFilter(Flip.VERTICAL)
                        8 -> image.rotate(270.0)
                    }
                    return ByteArrayOutputStream().use { out -> image.outputFormat("jpg").outputQuality(0.9).toOutputStream(out); out.toByteArray() }
                } finally { decoded.flush() }
            } finally { reader.dispose() }
        }
    }
}
