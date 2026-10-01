package com.homephoto.server.publication

import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.AtomicFiles
import com.homephoto.server.service.ThumbnailService
import com.homephoto.server.storage.StorageAdapter
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
import java.security.DigestInputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class PublicationAsset(
    val id: Long, val hash: String, val originalPath: String, val originalFilename: String,
    val takenAt: String?, val takenAtSource: String, val latitude: Double?, val longitude: Double?,
    val mediaType: String = "PHOTO",
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
    private val originals: StorageAdapter,
) {
    data class Prepared(val path: Path, val sha256: String, val metadata: PublicationMetadata,
                        val contentType: String = "image/jpeg", val version: String = VERSION)

    fun ownedPath(path: Path): Boolean = path.toAbsolutePath().normalize().parent == props.uploadTmpDir.resolve("google-photos").toAbsolutePath().normalize()
    fun delete(path: Path) {
        require(ownedPath(path)) { "게시 임시 폴더 밖의 파일은 정리하지 않습니다." }
        Files.deleteIfExists(path)
        Files.deleteIfExists(GooglePhotosResumableUpload.sessionPath(path))
    }

    fun prepare(asset: PublicationAsset): Prepared = if (asset.mediaType == "VIDEO") prepareVideo(asset) else preparePreview(asset)

    /** 관리 화면의 미리보기는 동영상도 기존 대표 JPEG를 사용한다. */
    fun preparePreview(asset: PublicationAsset): Prepared {
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

    private fun prepareVideo(asset: PublicationAsset): Prepared {
        val extension = asset.originalFilename.substringAfterLast('.', "").lowercase()
        val contentType = videoContentType(asset.originalFilename)
        val size = originals.stat(asset.originalPath)?.size
            ?: throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "VIDEO_ORIGINAL_MISSING")
        if (size !in 1..20_000_000_000L) throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "VIDEO_SIZE_UNSUPPORTED")
        val directory = props.uploadTmpDir.resolve("google-photos")
        Files.createDirectories(directory)
        val path = Files.createTempFile(directory, "${asset.id}-", ".$extension")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            AtomicFiles.write(path) { output ->
                DigestInputStream(originals.open(asset.originalPath), digest).use { Files.copy(it, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (hash != asset.hash) throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "VIDEO_ORIGINAL_CHANGED")
            val metadata = PublicationMetadata(asset.originalFilename, asset.takenAt, latitude = asset.latitude, longitude = asset.longitude,
                warnings = listOf("동영상 원본의 내장 메타데이터를 유지합니다. DB 날짜·위치 수정은 원본에 반영하지 않습니다."))
            return Prepared(path, hash, metadata, contentType, VIDEO_VERSION)
        } catch (error: Exception) { delete(path); throw error }
    }

    companion object {
        const val VERSION = "jpeg1600-exif-v1"
        const val VIDEO_VERSION = "video-original-v1"
        fun videoContentType(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m4v" -> "video/x-m4v"
            "3gp" -> "video/3gpp"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "mts" -> "video/mp2t"
            "wmv" -> "video/x-ms-wmv"
            else -> throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "VIDEO_FORMAT_UNSUPPORTED")
        }
        fun sha256(path: Path): String = Files.newInputStream(path).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
