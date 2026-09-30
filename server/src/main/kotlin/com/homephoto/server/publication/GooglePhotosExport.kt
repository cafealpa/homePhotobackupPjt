package com.homephoto.server.publication

import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.AtomicFiles
import com.homephoto.server.service.ThumbnailService
import org.apache.commons.imaging.common.RationalNumber
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.GpsTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryType
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfoAscii
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class PublicationAsset(
    val id: Long, val hash: String, val originalPath: String, val originalFilename: String,
    val takenAt: String?, val takenAtSource: String, val latitude: Double?, val longitude: Double?,
)

data class PublicationMetadata(
    val originalFilename: String, val captureTime: String?, val offset: String? = null,
    val latitude: Double? = null, val longitude: Double? = null, val altitude: Double? = null,
    val warnings: List<String> = emptyList(),
)

/** 5단계의 최소 필드 세트. 실제 Google Photos 인식 검증은 보류된 임시 규칙이다. */
@Component
class ExportExifWriter {
    fun write(source: Path, output: Path, metadata: PublicationMetadata) {
        val tags = TiffOutputSet()
        tags.orCreateRootDirectory.add(TiffTagConstants.TIFF_TAG_ORIENTATION, 1.toShort())
        metadata.captureTime?.let { date ->
            val exif = tags.orCreateExifDirectory
            val formatted = LocalDateTime.parse(date).format(EXIF_DATE)
            exif.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, formatted)
            exif.add(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED, formatted)
            metadata.offset?.let { offset ->
                exif.add(OFFSET_ORIGINAL, offset)
                exif.add(OFFSET_DIGITIZED, offset)
            }
        }
        if (metadata.latitude != null && metadata.longitude != null) {
            tags.setGpsInDegrees(metadata.longitude, metadata.latitude)
            metadata.altitude?.let { altitude ->
                val gps = tags.orCreateGpsDirectory
                gps.add(GpsTagConstants.GPS_TAG_GPS_ALTITUDE_REF, if (altitude < 0) 1.toByte() else 0.toByte())
                gps.add(GpsTagConstants.GPS_TAG_GPS_ALTITUDE, RationalNumber.valueOf(abs(altitude)))
            }
        }
        Files.newOutputStream(output).use { ExifRewriter().updateExifMetadataLossless(source.toFile(), it, tags) }
    }

    companion object {
        private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
        private val OFFSET_ORIGINAL = TagInfoAscii("OffsetTimeOriginal", 0x9011, 7, TiffDirectoryType.EXIF_DIRECTORY_EXIF_IFD)
        private val OFFSET_DIGITIZED = TagInfoAscii("OffsetTimeDigitized", 0x9012, 7, TiffDirectoryType.EXIF_DIRECTORY_EXIF_IFD)
    }
}

@Component
class GooglePhotosExport(
    private val props: AppProperties,
    private val thumbnails: ThumbnailService,
    private val metadataProvider: PublicationMetadataProvider,
    private val writer: ExportExifWriter,
) {
    data class Prepared(val path: Path, val sha256: String, val metadata: PublicationMetadata)

    fun prepare(asset: PublicationAsset): Prepared {
        val source = thumbnails.thumbPath(asset.hash, 1600)
        require(Files.isRegularFile(source)) { "1600 썸네일이 준비되지 않았습니다." }
        val metadata = metadataProvider.resolve(asset)
        val directory = props.uploadTmpDir.resolve("google-photos")
        Files.createDirectories(directory)
        val path = Files.createTempFile(directory, "${asset.id}-", ".jpg")
        try {
            AtomicFiles.write(path) { writer.write(source, it, metadata) }
            return Prepared(path, sha256(path), metadata)
        } catch (error: Exception) {
            Files.deleteIfExists(path)
            throw error
        }
    }

    companion object {
        const val VERSION = "jpeg1600-exif-v1"
        fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
