package com.homephoto.server.db

import org.jetbrains.exposed.sql.Table

// 스키마 정의는 docs/DESIGN.md 4장이 기준. 날짜/시각은 ISO-8601 TEXT로 저장한다.

object Assets : Table("assets") {
    val id = long("id").autoIncrement()
    val hash = text("hash").uniqueIndex()                  // SHA-256 hex
    val mediaType = text("media_type")                     // PHOTO | VIDEO
    val originalPath = text("original_path")               // storageRoot 기준 상대경로
    val originalFilename = text("original_filename")
    val fileSize = long("file_size")
    val takenAt = text("taken_at").nullable()
    val takenAtSource = text("taken_at_source")            // EXIF | FILE_MTIME | UPLOAD_TIME
    val yearMonth = text("year_month").index()             // '2026-08'
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val durationMs = long("duration_ms").nullable()        // VIDEO만
    val cameraMake = text("camera_make").nullable()
    val cameraModel = text("camera_model").nullable()
    val gpsLat = double("gps_lat").nullable()
    val gpsLon = double("gps_lon").nullable()
    val createdAt = text("created_at")
    val deletedAt = text("deleted_at").nullable()          // 휴지통 이동 시각 (NULL = 활성)
    val purgedAt = text("purged_at").nullable()            // 영구 삭제 시각 (파일 제거됨, 행은 재백업 스킵용 묘비)
    val favorite = bool("favorite").default(false)
    val deviceId = text("device_id").nullable()            // 처음 백업한 기기 (소유자)
    // 프로퍼티명이 sourceTag인 이유: Exposed ColumnSet에 이미 source 멤버가 있다
    val sourceTag = text("source").nullable()              // NULL = 일반 | KIDSNOTE = 키즈노트 전용(타임라인 제외)

    override val primaryKey = PrimaryKey(id)
}

/** 백업 주체 기기. 이름을 바꾸면 그 기기의 모든 사진에 소급 반영된다. */
object Devices : Table("devices") {
    val id = text("id")                                    // 앱 설치 시 생성된 UUID
    val name = text("name")                                // "아빠 폰" 등 (기본: 기기 모델명)
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Persons : Table("persons") {
    val id = long("id").autoIncrement()
    val name = text("name").nullable()                     // 사용자가 붙인 이름 (NULL = 미명명)

    override val primaryKey = PrimaryKey(id)
}

object Faces : Table("faces") {
    val id = long("id").autoIncrement()
    val assetId = long("asset_id").references(Assets.id)
    val bboxX = double("bbox_x")                           // 0~1 정규화 좌표
    val bboxY = double("bbox_y")
    val bboxW = double("bbox_w")
    val bboxH = double("bbox_h")
    val embedding = blob("embedding")                      // float32[512] little-endian
    val clusterId = integer("cluster_id").nullable()       // 자동 클러스터링 결과
    val personId = long("person_id").references(Persons.id).nullable() // 사용자 확정 라벨
    val hidden = bool("hidden").default(false)             // 인물 뷰에서 숨김 (사용자 삭제)

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, assetId)
        index(false, clusterId)
    }
}

/** Phase 4: VLM 장면 분석 결과. 자산당 1행 — 재처리 시 덮어쓴다. */
object Captions : Table("captions") {
    val assetId = long("asset_id").references(Assets.id)
    val caption = text("caption")                          // 한국어 한두 문장
    val tags = text("tags").nullable()                     // 쉼표 구분 키워드
    val model = text("model").nullable()                   // 재처리 판단용 모델명
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(assetId)
}

/** 키즈노트 백업의 아이. folder_name은 백업 루트 하위 폴더명(예: "규연"). */
object KidsnoteChildren : Table("kidsnote_children") {
    val id = long("id").autoIncrement()
    val folderName = text("folder_name").uniqueIndex()     // "규연"
    val childName = text("child_name")                     // "조규연" (content.json의 child_name)
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** 키즈노트 알림장 글. post_id는 키즈노트 전역 고유 id — 재임포트 멱등성 키. */
object KidsnotePosts : Table("kidsnote_posts") {
    val id = long("id").autoIncrement()
    val postId = long("post_id").uniqueIndex()
    val childId = long("child_id").references(KidsnoteChildren.id)
    val dateWritten = text("date_written")                 // "2018-12-14" — 일자 버킷 기준
    val yearMonth = text("year_month")                     // "2018-12"
    val content = text("content")
    val authorName = text("author_name")                   // "퍼플1 주임교사" / "조규연 엄마"
    val createdAt = text("created_at")                     // 원본 ISO-8601 UTC
    val videoStatus = text("video_status").nullable()      // NULL(영상 없음) | DOWNLOADED | LOST
    val videoAssetId = long("video_asset_id").references(Assets.id).nullable()
    val videoOriginalName = text("video_original_name").nullable()
    val videoHighUrl = text("video_high_url").nullable()   // LOST 재시도용 보존
    val videoLowUrl = text("video_low_url").nullable()
    val importedAt = text("imported_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, childId, yearMonth)
        index(false, childId, dateWritten)
    }
}

/** 알림장 글 ↔ 사진(assets) 연결. post_id는 KidsnotePosts.postId(자연키)를 참조한다. */
object KidsnotePostImages : Table("kidsnote_post_images") {
    val id = long("id").autoIncrement()
    val postId = long("post_id")
    val assetId = long("asset_id").references(Assets.id)
    val filename = text("filename")                        // "266621988_579040456.jpg" (원본명)
    val seq = integer("seq")                               // content.json images 배열 순서

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(postId, filename)                      // 재임포트 멱등성 (insertIgnore)
        index(false, assetId)
    }
}

/** 수동 앨범. cover_asset_id가 NULL이면 앨범 내 최신(takenAt) 활성 사진이 자동 커버. */
object Albums : Table("albums") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val coverAssetId = long("cover_asset_id").references(Assets.id).nullable()
    val createdAt = text("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** 앨범 ↔ 사진 M:N 연결. 사진 원본(assets)은 불변 — 연결만 생성/삭제한다. */
object AlbumAssets : Table("album_assets") {
    val id = long("id").autoIncrement()
    val albumId = long("album_id").references(Albums.id)
    val assetId = long("asset_id").references(Assets.id)
    val addedAt = text("added_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(albumId, assetId)                      // 재추가 멱등성 (insertIgnore)
        index(false, assetId)
    }
}

object Jobs : Table("jobs") {
    val id = long("id").autoIncrement()
    val assetId = long("asset_id").references(Assets.id)
    val jobType = text("job_type")                         // THUMBNAIL | FACE | CAPTION
    val status = text("status").default("PENDING")         // PENDING | RUNNING | DONE | FAILED
    val priority = integer("priority").default(0)          // 높을수록 먼저 (최근 사진 우선 백필)
    val attempts = integer("attempts").default(0)
    val lastError = text("last_error").nullable()
    val updatedAt = text("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(assetId, jobType)
        index(false, jobType, status, priority)
    }
}
