package com.homephoto.server.api

import com.homephoto.server.db.Assets
import org.jetbrains.exposed.sql.ResultRow

data class AssetDto(
    val id: Long,
    val hash: String,
    val mediaType: String,
    val originalFilename: String,
    val fileSize: Long,
    val takenAt: String?,
    val takenAtSource: String,
    val yearMonth: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val favorite: Boolean,
    val deviceId: String?,
    val cameraMake: String?,
    val cameraModel: String?,
    val gpsLat: Double?,
    val gpsLon: Double?,
)

fun ResultRow.toAssetDto() = AssetDto(
    id = this[Assets.id],
    hash = this[Assets.hash],
    mediaType = this[Assets.mediaType],
    originalFilename = this[Assets.originalFilename],
    fileSize = this[Assets.fileSize],
    takenAt = this[Assets.takenAt],
    takenAtSource = this[Assets.takenAtSource],
    yearMonth = this[Assets.yearMonth],
    width = this[Assets.width],
    height = this[Assets.height],
    durationMs = this[Assets.durationMs],
    favorite = this[Assets.favorite],
    deviceId = this[Assets.deviceId],
    cameraMake = this[Assets.cameraMake],
    cameraModel = this[Assets.cameraModel],
    gpsLat = this[Assets.gpsLat],
    gpsLon = this[Assets.gpsLon],
)

data class CheckRequest(val hashes: List<String>)
data class CheckResponse(
    val missing: List<String>,
    /** 서버에서 삭제된(재백업 스킵 대상) 해시들 */
    val deleted: List<String> = emptyList(),
)

data class MonthDto(val yearMonth: String, val count: Long)

data class AssetPageDto(val items: List<AssetDto>, val nextCursor: String?)

/** mode: SCAN(미리 확인) | COPY(복사, 기본) | MOVE(이동). 키즈노트 임포트는 mode를 쓰지 않는다. */
data class ImportRequest(val sourcePath: String, val mode: String = "COPY")

data class ImportStatusDto(
    val running: Boolean,
    /** IDLE | SCANNING | IMPORTING | DONE | CANCELLED | ERROR */
    val phase: String,
    val mode: String?,
    val sourcePath: String?,
    val total: Int,
    val processed: Int,
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    /** 스캔 단계의 진행 표시용 — 아직 total을 모를 때 지금까지 찾은 개수 */
    val scannedFiles: Int,
    val totalBytes: Long,
    val processedBytes: Long,
    /** 저장소 볼륨의 여유 공간 (스캔 완료 시점) */
    val freeBytes: Long,
    val elapsedMs: Long,
    /** 남은 시간 추정 — 계산할 근거가 없으면 null */
    val etaMs: Long?,
    val currentFile: String?,
    val lastError: String?,
    /** 완료·중지·오류 요약 (그대로 화면에 표시된다) */
    val message: String?,
)

data class KidsnoteImportStatusDto(
    val running: Boolean,
    val totalDays: Int,        // content.json 일자 폴더 수
    val processedDays: Int,
    val posts: Int,            // 이번 실행에서 만난 글 수 (스킵 포함)
    val newPosts: Int,
    val images: Int,
    val newImages: Int,
    val videosDownloaded: Int,
    val videosLost: Int,
    val failed: Int,
    val currentFolder: String?,
    val lastError: String?,
)
