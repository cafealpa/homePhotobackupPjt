package com.homephoto.server.service

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.jpeg.JpegDirectory
import com.drew.metadata.mp4.Mp4Directory
import com.drew.metadata.png.PngDirectory
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date
import java.util.TimeZone

@Component
class ExifService {

    data class Extracted(
        val takenAt: LocalDateTime? = null,
        val width: Int? = null,
        val height: Int? = null,
        val cameraMake: String? = null,
        val cameraModel: String? = null,
        val gpsLat: Double? = null,
        val gpsLon: Double? = null,
    )

    /** 실패해도 예외를 던지지 않는다 — 메타데이터는 어디까지나 best-effort. */
    fun extract(file: Path): Extracted {
        val metadata: Metadata = try {
            ImageMetadataReader.readMetadata(file.toFile())
        } catch (e: Exception) {
            return Extracted()
        }

        val sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val gps = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)

        val takenAt: Date? = runCatching { sub?.getDateOriginal(TimeZone.getDefault()) }.getOrNull()
            ?: runCatching {
                metadata.getFirstDirectoryOfType(Mp4Directory::class.java)?.getDate(Mp4Directory.TAG_CREATION_TIME)
            }.getOrNull()

        val (width, height) = dimensions(metadata, sub)
        // (0,0)은 GPS 꺼짐 상태에서 태그만 기록된 경우 — 위치 없음으로 취급
        val geo = runCatching { gps?.geoLocation }.getOrNull()?.takeUnless { it.isZero }

        return Extracted(
            takenAt = takenAt?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDateTime(),
            width = width,
            height = height,
            cameraMake = ifd0?.getString(ExifIFD0Directory.TAG_MAKE)?.trim(),
            cameraModel = ifd0?.getString(ExifIFD0Directory.TAG_MODEL)?.trim(),
            gpsLat = geo?.latitude,
            gpsLon = geo?.longitude,
        )
    }

    private fun dimensions(metadata: Metadata, sub: ExifSubIFDDirectory?): Pair<Int?, Int?> {
        metadata.getFirstDirectoryOfType(JpegDirectory::class.java)?.let {
            runCatching { return it.imageWidth to it.imageHeight }
        }
        metadata.getFirstDirectoryOfType(PngDirectory::class.java)?.let {
            runCatching {
                return it.getInt(PngDirectory.TAG_IMAGE_WIDTH) to it.getInt(PngDirectory.TAG_IMAGE_HEIGHT)
            }
        }
        runCatching {
            val w = sub?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH)
            val h = sub?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT)
            if (w != null && h != null) return w to h
        }
        return null to null
    }
}
