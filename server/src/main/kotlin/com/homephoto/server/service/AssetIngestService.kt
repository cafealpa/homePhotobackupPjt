package com.homephoto.server.service

import com.homephoto.server.api.AssetDto
import com.homephoto.server.api.toAssetDto
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Jobs
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class UnsupportedMediaException(ext: String) : RuntimeException("unsupported file extension: .$ext")

/** 업로드·일괄 임포트가 공유하는 인제스트 파이프라인. source 파일은 삭제하지 않는다. */
@Service
class AssetIngestService(
    private val props: AppProperties,
    private val exifService: ExifService,
    private val takenAtResolver: TakenAtResolver,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    data class IngestResult(val asset: AssetDto, val created: Boolean)

    fun ingest(
        source: Path,
        originalFilename: String,
        expectedHash: String?,
        fileMtime: Instant?,
        deviceId: String? = null,
        deviceName: String? = null,
        precomputedHash: String? = null, // 수신 스트림에서 이미 계산한 해시 (전체 재읽기 방지)
        moveSource: Boolean = false,     // true면 source를 최종 위치로 move (업로드 전용 — 임포트는 원본 보존)
        takenAtOverride: TakenAtResolver.Resolved? = null, // 파일명/EXIF보다 신뢰할 날짜가 있을 때 (키즈노트: date_written)
        sourceTag: String? = null,       // 신규 INSERT 시 assets.source에 기록 (KIDSNOTE = 타임라인 제외)
        skipMlJobs: Boolean = false,     // FACE·CAPTION 작업 미등록 (THUMBNAIL은 항상 등록)
    ): IngestResult {
        val ext = originalFilename.substringAfterLast('.', "").lowercase()
        val mediaType = when (ext) {
            in PHOTO_EXTENSIONS -> "PHOTO"
            in VIDEO_EXTENSIONS -> "VIDEO"
            else -> throw UnsupportedMediaException(ext)
        }

        val hash = precomputedHash ?: sha256(source)
        if (expectedHash != null && !expectedHash.equals(hash, ignoreCase = true)) {
            log.warn("해시 불일치: {} (client={}, server={}) — 전송 중 손상 가능성", originalFilename, expectedHash.take(8), hash.take(8))
            throw IllegalArgumentException("hash mismatch: client=$expectedHash server=$hash")
        }

        val existingRow = transaction {
            Assets.selectAll().where { Assets.hash eq hash }.firstOrNull()
        }
        if (existingRow != null) {
            if (existingRow[Assets.deletedAt] == null) {
                // 키즈노트 전용 자산에 일반 업로드가 들어오면 승격: 타임라인에 노출 + ML 작업 등록.
                // (키즈노트 뷰어는 링크 테이블로 asset id를 참조하므로 승격돼도 계속 보인다)
                if (existingRow[Assets.sourceTag] != null && sourceTag == null) {
                    return promote(existingRow, deviceId, deviceName)
                }
                log.debug("중복 스킵: {} (asset #{})", originalFilename, existingRow[Assets.id])
                return IngestResult(existingRow.toAssetDto(), created = false)
            }
            // 삭제됐던 사진의 명시적 재업로드 → 묘비 해제 + 파일 복원
            return restore(existingRow, source)
        }

        val meta = exifService.extract(source)
        val resolved = takenAtOverride ?: takenAtResolver.resolve(originalFilename, meta.takenAt, fileMtime)
        val takenAt = resolved.takenAt
        val ym = "%04d-%02d".format(takenAt.year, takenAt.monthValue)

        val relDir = "originals/%04d/%02d".format(takenAt.year, takenAt.monthValue)
        // DB가 유실돼도 파일명만으로 촬영시각을 알 수 있게 시각 기반 이름을 쓴다.
        // 연사(같은 밀리초) 충돌 방지 + 무결성 검증용으로 해시 앞 8자리를 덧붙인다.
        val storedName = storedFileName(takenAt, hash, ext)
        val relPath = "$relDir/$storedName"
        val target = props.storageRoot.resolve(relDir).resolve(storedName)
        val fileSize = Files.size(source) // move 후에는 source가 없으므로 먼저 읽는다
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) {
            // move는 같은 볼륨이면 rename 한 번으로 끝난다 (다른 볼륨이면 자동으로 복사+삭제)
            if (moveSource) Files.move(source, target) else Files.copy(source, target)
        }
        val nowIso = LocalDateTime.now().format(ISO)
        val takenAtIso = takenAt.format(ISO)

        return try {
            val dto = transaction {
                if (deviceId != null) upsertDevice(deviceId, deviceName, nowIso)
                val id = Assets.insert {
                    it[Assets.hash] = hash
                    it[Assets.mediaType] = mediaType
                    it[originalPath] = relPath
                    it[Assets.originalFilename] = originalFilename
                    it[Assets.fileSize] = fileSize
                    it[Assets.takenAt] = takenAtIso
                    it[takenAtSource] = resolved.source
                    it[yearMonth] = ym
                    it[width] = meta.width
                    it[height] = meta.height
                    it[cameraMake] = meta.cameraMake
                    it[cameraModel] = meta.cameraModel
                    it[gpsLat] = meta.gpsLat
                    it[gpsLon] = meta.gpsLon
                    it[createdAt] = nowIso
                    it[Assets.deviceId] = deviceId
                    it[Assets.sourceTag] = sourceTag
                }[Assets.id]

                // 최근 사진 우선 처리 (백필 시 2TB가 과거→현재 순으로 밀리지 않도록)
                val jobPriority = takenAt.year * 100 + takenAt.monthValue
                Jobs.insert {
                    it[assetId] = id
                    it[jobType] = "THUMBNAIL"
                    it[priority] = jobPriority
                    it[updatedAt] = nowIso
                }
                if (mediaType == "PHOTO" && !skipMlJobs) {
                    for (mlJob in ML_JOB_TYPES) {
                        Jobs.insert {
                            it[assetId] = id
                            it[jobType] = mlJob
                            it[priority] = jobPriority
                            it[updatedAt] = nowIso
                        }
                    }
                }

                AssetDto(
                    id = id, hash = hash, mediaType = mediaType,
                    originalFilename = originalFilename, fileSize = fileSize,
                    takenAt = takenAtIso, takenAtSource = resolved.source, yearMonth = ym,
                    width = meta.width, height = meta.height, durationMs = null,
                    favorite = false, deviceId = deviceId,
                    cameraMake = meta.cameraMake, cameraModel = meta.cameraModel,
                    gpsLat = meta.gpsLat, gpsLon = meta.gpsLon,
                )
            }
            log.info(
                "저장: #{} {} → {} ({}KB, {}, 날짜근거={}, 기기={})",
                dto.id, originalFilename, relPath, fileSize / 1024, mediaType,
                resolved.source, deviceName ?: deviceId ?: "-",
            )
            IngestResult(dto, created = true)
        } catch (e: ExposedSQLException) {
            // 동시 업로드로 인한 hash unique 충돌 → 기존 레코드로 응답
            log.debug("동시 업로드 충돌: {} — 기존 레코드로 응답", originalFilename)
            findByHash(hash)?.let { IngestResult(it, created = false) } ?: throw e
        }
    }

    fun findByHash(hash: String): AssetDto? = transaction {
        Assets.selectAll().where { Assets.hash eq hash }.firstOrNull()?.toAssetDto()
    }

    /** 기기 등록/이름 갱신 (앱에서 이름을 바꾸면 다음 업로드 때 반영). 트랜잭션 안에서 호출해야 한다. */
    private fun org.jetbrains.exposed.sql.Transaction.upsertDevice(deviceId: String, deviceName: String?, nowIso: String) {
        exec(
            "INSERT INTO devices (id, name, created_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET name = excluded.name",
            args = listOf(
                org.jetbrains.exposed.sql.TextColumnType() to deviceId,
                org.jetbrains.exposed.sql.TextColumnType() to (deviceName ?: deviceId),
                org.jetbrains.exposed.sql.TextColumnType() to nowIso,
            ),
        )
    }

    /**
     * 키즈노트 전용(source != NULL) 자산을 일반 자산으로 승격한다: source 해제 + 업로더 기기 기록
     * + 임포트 때 생략했던 ML 작업 등록. created=true로 응답해 클라이언트가 백업 완료로 인식하게 한다.
     */
    private fun promote(row: org.jetbrains.exposed.sql.ResultRow, deviceId: String?, deviceName: String?): IngestResult {
        val id = row[Assets.id]
        val nowIso = LocalDateTime.now().format(ISO)
        val jobPriority = row[Assets.yearMonth].replace("-", "").toIntOrNull() ?: 0
        transaction {
            if (deviceId != null) upsertDevice(deviceId, deviceName, nowIso)
            Assets.update({ Assets.id eq id }) {
                it[sourceTag] = null
                if (deviceId != null) it[Assets.deviceId] = deviceId
            }
            if (row[Assets.mediaType] == "PHOTO") {
                for (mlJob in ML_JOB_TYPES) {
                    Jobs.insertIgnore {
                        it[assetId] = id
                        it[jobType] = mlJob
                        it[priority] = jobPriority
                        it[updatedAt] = nowIso
                    }
                }
            }
        }
        log.info("승격: #{} {} — 키즈노트 전용 자산이 일반 업로드로 타임라인에 노출됨", id, row[Assets.originalFilename])
        return IngestResult(row.toAssetDto().copy(deviceId = deviceId ?: row[Assets.deviceId]), created = true)
    }

    /** 삭제(묘비) 상태인 사진을 재업로드로 복원한다: 파일 재저장 + deleted_at 해제 + 처리 작업 재등록. */
    private fun restore(row: org.jetbrains.exposed.sql.ResultRow, source: Path): IngestResult {
        val id = row[Assets.id]
        val relPath = row[Assets.originalPath]
        val target = props.storageRoot.resolve(relPath)
        Files.createDirectories(target.parent)
        if (!Files.exists(target)) {
            Files.copy(source, target)
        }

        val nowIso = LocalDateTime.now().format(ISO)
        val jobPriority = row[Assets.yearMonth].replace("-", "").toIntOrNull() ?: 0
        transaction {
            Assets.update({ Assets.id eq id }) {
                it[deletedAt] = null
                it[purgedAt] = null
            }
            Jobs.insertIgnore {
                it[assetId] = id
                it[jobType] = "THUMBNAIL"
                it[priority] = jobPriority
                it[updatedAt] = nowIso
            }
            if (row[Assets.mediaType] == "PHOTO") {
                for (mlJob in ML_JOB_TYPES) {
                    Jobs.insertIgnore {
                        it[assetId] = id
                        it[jobType] = mlJob
                        it[priority] = jobPriority
                        it[updatedAt] = nowIso
                    }
                }
            }
        }
        log.info("복원: #{} {} — 삭제됐던 사진이 재업로드로 되살아남", id, row[Assets.originalFilename])
        return IngestResult(row.toAssetDto(), created = true)
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /** 저장 파일명: 촬영시각 + 해시 8자리(연사 충돌 방지·무결성 검증용). 예: 20230815_123456_87014cdc.jpg */
        fun storedFileName(takenAt: LocalDateTime, hash: String, ext: String): String {
            val base = "${takenAt.format(FILE_TIMESTAMP)}_${hash.take(8)}"
            return if (ext.isEmpty()) base else "$base.$ext"
        }
        /** 사진(PHOTO)에만 등록하는 ML 작업 — 동영상 ML은 초기 범위 제외 */
        val ML_JOB_TYPES = listOf("FACE", "CAPTION")
        val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "dng", "tif", "tiff", "avif")
        val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "3gp", "avi", "mkv", "webm", "mts", "wmv")
    }
}
