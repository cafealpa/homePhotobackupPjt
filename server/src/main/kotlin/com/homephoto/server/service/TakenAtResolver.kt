package com.homephoto.server.service

import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 촬영일 판정. 우선순위: 파일명 → EXIF → 파일수정시각 → 현재시각.
 * (사용자 결정: 기기·앱이 파일명에 박은 날짜가 EXIF보다 신뢰도가 높다)
 */
@Component
class TakenAtResolver {

    data class Resolved(val takenAt: LocalDateTime, val source: String)

    fun resolve(filename: String, exifTakenAt: LocalDateTime?, fileMtime: Instant?): Resolved {
        fromFilename(filename)?.let { return Resolved(it, "FILENAME") }
        exifTakenAt?.let { return Resolved(it, "EXIF") }
        fileMtime?.let { return Resolved(LocalDateTime.ofInstant(it, ZoneId.systemDefault()), "FILE_MTIME") }
        return Resolved(LocalDateTime.now(), "UPLOAD_TIME")
    }

    fun fromFilename(filename: String): LocalDateTime? {
        // 1) 날짜+시각: 20230815_123456, IMG_2023-08-15_12.34.56, PXL_20230815_123456789 등
        FULL_DATETIME.findAll(filename).forEach { m ->
            val (y, mo, d, h, mi, s) = m.destructured
            runCatching {
                LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
            }.getOrNull()?.takeIf { plausible(it) }?.let { return it }
        }
        // 2) epoch millis 13자리: 카카오톡 등 (예: 1629012345678.jpg)
        EPOCH_MILLIS.findAll(filename).forEach { m ->
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(m.value.toLong()), ZoneId.systemDefault())
            if (plausible(dt)) return dt
        }
        // 3) 날짜만: Screenshot_2023-08-15, 20230815 등 → 00:00 취급
        DATE_ONLY.findAll(filename).forEach { m ->
            val (y, mo, d) = m.destructured
            runCatching {
                LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atStartOfDay()
            }.getOrNull()?.takeIf { plausible(it) }?.let { return it }
        }
        return null
    }

    private fun plausible(dt: LocalDateTime): Boolean =
        dt.year in 2000..(LocalDate.now().year + 1) && !dt.isAfter(LocalDateTime.now().plusDays(1))

    companion object {
        private val FULL_DATETIME =
            Regex("""(20\d{2})[-_.]?(\d{2})[-_.]?(\d{2})[-_ .T]?(\d{2})[:._-]?(\d{2})[:._-]?(\d{2})""")
        private val EPOCH_MILLIS = Regex("""(?<!\d)1[3-9]\d{11}(?!\d)""")
        private val DATE_ONLY = Regex("""(20\d{2})[-_.]?(\d{2})[-_.]?(\d{2})""")
    }
}
