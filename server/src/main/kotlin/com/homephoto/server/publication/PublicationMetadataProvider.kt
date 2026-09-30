package com.homephoto.server.publication

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.homephoto.server.storage.StorageAdapter
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** 기존 DB 날짜/좌표를 우선한다. 알려지지 않은 offset/고도는 원본 EXIF에서만 보충한다. */
@Component
class PublicationMetadataProvider(private val originals: StorageAdapter) {
    fun resolve(asset: PublicationAsset): PublicationMetadata {
        var result = PublicationMetadata(asset.originalFilename, asset.takenAt,
            latitude = asset.latitude, longitude = asset.longitude)
        try {
            originals.withReadableFile(asset.originalPath) { file ->
                val metadata = ImageMetadataReader.readMetadata(file.toFile())
                val exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
                val rawDate = exif?.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
                val date = rawDate?.let { runCatching { LocalDateTime.parse(it.trim(), EXIF_DATE) }.getOrNull() }
                val capture = asset.takenAt?.let(LocalDateTime::parse) ?: date
                // 서로 다른 날짜에 원본 offset을 붙이지 않는다. 서버 시간대를 추정하지 않는다.
                val offset = if (date != null && capture?.truncatedTo(ChronoUnit.SECONDS) == date.truncatedTo(ChronoUnit.SECONDS))
                    exif.getString(0x9011)?.let { runCatching { ZoneOffset.of(it.trim()).id.let { id -> if (id == "Z") "+00:00" else id } }.getOrNull() } else null
                val gps = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
                val geo = runCatching { gps?.geoLocation }.getOrNull()?.takeUnless { it.isZero }
                val dbGps = asset.latitude != null && asset.longitude != null
                val lat = if (dbGps) asset.latitude else geo?.latitude
                val lon = if (dbGps) asset.longitude else geo?.longitude
                val matching = geo != null && lat != null && lon != null && abs(lat - geo.latitude) < .000001 && abs(lon - geo.longitude) < .000001
                val altitude = if (matching && gps.containsTag(GpsDirectory.TAG_ALTITUDE)) runCatching {
                    gps.getDouble(GpsDirectory.TAG_ALTITUDE) * if (gps.getInteger(GpsDirectory.TAG_ALTITUDE_REF) == 1) -1 else 1
                }.getOrNull() else null
                result = result.copy(captureTime = capture?.toString(), offset = offset, latitude = lat, longitude = lon, altitude = altitude)
            }
        } catch (_: Exception) {
            result = result.copy(warnings = listOf("원본 추가 메타데이터를 읽지 못해 DB 값만 사용했습니다."))
        }
        return result
    }
    companion object { private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss") }
}
