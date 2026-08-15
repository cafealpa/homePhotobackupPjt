package com.homephoto.server.api

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.AlbumAssets
import com.homephoto.server.db.Albums
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Captions
import com.homephoto.server.db.Faces
import com.homephoto.server.service.AssetIngestService
import com.homephoto.server.service.ThumbnailService
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import kotlin.io.path.deleteIfExists

@RestController
@RequestMapping("/api/v1")
class AssetController(
    private val ingestService: AssetIngestService,
    private val thumbnailService: ThumbnailService,
    private val props: AppProperties,
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    @PostMapping("/assets/check")
    fun check(@RequestBody request: CheckRequest): CheckResponse {
        // (해시, 삭제 여부)로 조회해 활성/삭제됨/없음 3가지를 구분한다.
        // 키즈노트 전용(source != NULL) 자산은 missing 취급 — 폰이 업로드하면 ingest가 일반 자산으로 승격시킨다.
        data class Found(val hash: String, val deleted: Boolean, val kidsnoteOnly: Boolean)
        val found = transaction {
            request.hashes.chunked(500).flatMap { chunk ->
                Assets.select(Assets.hash, Assets.deletedAt, Assets.sourceTag)
                    .where { Assets.hash inList chunk }
                    .map { Found(it[Assets.hash], it[Assets.deletedAt] != null, it[Assets.sourceTag] != null) }
            }
        }
        val active = found.filter { !it.deleted && !it.kidsnoteOnly }.map { it.hash }.toSet()
        val deleted = found.filter { it.deleted }.map { it.hash }.toSet()
        return CheckResponse(
            missing = request.hashes.filter { it !in active && it !in deleted },
            deleted = deleted.toList(),
        )
    }

    @PostMapping("/assets")
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @RequestParam(required = false) hash: String?,
        @RequestParam(required = false) fileMtime: Long?,
        @RequestHeader(value = "X-Device-Id", required = false) deviceId: String?,
        @RequestHeader(value = "X-Device-Name", required = false) deviceNameEncoded: String?,
    ): ResponseEntity<AssetDto> {
        val filename = file.originalFilename?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "filename required")

        // 임시 파일을 저장소와 같은 볼륨에 둬서 ingest의 최종 배치가 복사 없는 rename이 되게 한다
        Files.createDirectories(props.uploadTmpDir)
        val temp = Files.createTempFile(props.uploadTmpDir, "upload-", ".bin")
        try {
            // 수신 스트림을 쓰면서 해시를 같이 계산 — 해시 검증을 위한 전체 재읽기를 없앤다
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            java.security.DigestInputStream(file.inputStream, digest).use { input ->
                Files.copy(input, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            val receivedHash = digest.digest().joinToString("") { "%02x".format(it) }
            val result = ingestService.ingest(
                source = temp,
                originalFilename = filename,
                expectedHash = hash,
                precomputedHash = receivedHash,
                moveSource = true,
                fileMtime = fileMtime?.let { java.time.Instant.ofEpochMilli(it) },
                deviceId = deviceId?.takeIf { it.isNotBlank() },
                // 한글 기기명은 헤더에 못 실리므로 클라이언트가 URL 인코딩해서 보낸다
                deviceName = deviceNameEncoded?.let {
                    java.net.URLDecoder.decode(it, Charsets.UTF_8)
                },
            )
            val status = if (result.created) HttpStatus.CREATED else HttpStatus.CONFLICT
            return ResponseEntity.status(status).body(result.asset)
        } finally {
            temp.deleteIfExists()
        }
    }

    @GetMapping("/months")
    fun months(): List<MonthDto> = transaction {
        val cnt = Assets.id.count()
        Assets.select(Assets.yearMonth, cnt)
            .where { Assets.deletedAt.isNull() and Assets.sourceTag.isNull() }
            .groupBy(Assets.yearMonth)
            .orderBy(Assets.yearMonth to SortOrder.DESC)
            .map { MonthDto(it[Assets.yearMonth], it[cnt]) }
    }

    @GetMapping("/assets")
    fun list(
        @RequestParam(required = false) yearMonth: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) clusterId: Int?,
        @RequestParam(required = false) albumId: Long?,
        @RequestParam(required = false) day: String?,
        @RequestParam(required = false) deviceId: String?,
        @RequestParam(required = false) favorite: Boolean?,
        @RequestParam(required = false) minLat: Double?,
        @RequestParam(required = false) maxLat: Double?,
        @RequestParam(required = false) minLon: Double?,
        @RequestParam(required = false) maxLon: Double?,
        @RequestParam(defaultValue = "100") limit: Int,
    ): AssetPageDto {
        val pageSize = limit.coerceIn(1, 500)
        val items = transaction {
            var query = Assets.selectAll().where { Assets.deletedAt.isNull() and Assets.sourceTag.isNull() }
            yearMonth?.let { ym -> query = query.andWhere { Assets.yearMonth eq ym } }
            // 하루 여정 뷰: taken_at은 ISO-8601 텍스트라 prefix LIKE로 일자 필터
            day?.let { d ->
                require(Regex("""\d{4}-\d{2}-\d{2}""").matches(d)) { "day must be YYYY-MM-DD" }
                query = query.andWhere { Assets.takenAt like "$d%" }
            }
            // 지도 클러스터 상세: 클러스터 응답의 멤버 실좌표 min/max를 그대로 bbox로 받는다
            if (minLat != null && maxLat != null && minLon != null && maxLon != null) {
                query = query.andWhere {
                    Assets.gpsLat.isNotNull() and Assets.gpsLon.isNotNull() and
                        (Assets.gpsLat greaterEq minLat) and (Assets.gpsLat lessEq maxLat) and
                        (Assets.gpsLon greaterEq minLon) and (Assets.gpsLon lessEq maxLon) and
                        not((Assets.gpsLat eq 0.0) and (Assets.gpsLon eq 0.0))
                }
            }
            deviceId?.let { d -> query = query.andWhere { Assets.deviceId eq d } }
            if (favorite == true) query = query.andWhere { Assets.favorite eq true }
            clusterId?.let { cid ->
                query = query.andWhere {
                    Assets.id inSubQuery Faces.select(Faces.assetId)
                        .where { (Faces.clusterId eq cid) and (Faces.hidden eq false) }
                }
            }
            albumId?.let { aid ->
                if (Albums.selectAll().where { Albums.id eq aid }.count() == 0L) {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "album $aid not found")
                }
                query = query.andWhere {
                    Assets.id inSubQuery AlbumAssets.select(AlbumAssets.assetId)
                        .where { AlbumAssets.albumId eq aid }
                }
            }
            cursor?.let { c ->
                val (takenAtCursor, idCursor) = parseCursor(c)
                query = query.andWhere {
                    (Assets.takenAt less takenAtCursor) or
                        ((Assets.takenAt eq takenAtCursor) and (Assets.id less idCursor))
                }
            }
            query.orderBy(Assets.takenAt to SortOrder.DESC, Assets.id to SortOrder.DESC)
                .limit(pageSize)
                .map { it.toAssetDto() }
        }
        val nextCursor = items.lastOrNull()
            ?.takeIf { items.size == pageSize }
            ?.let { "${it.takenAt}~${it.id}" }
        return AssetPageDto(items = items, nextCursor = nextCursor)
    }

    @GetMapping("/assets/{id}")
    fun get(@PathVariable id: Long): AssetDto = findAsset(id).toAssetDto()

    data class CaptionDto(val caption: String?, val tags: List<String>, val model: String?)

    /** 장면 분석(Phase 4) 결과. 아직 분석 전이면 caption=null. */
    @GetMapping("/assets/{id}/caption")
    fun caption(@PathVariable id: Long): CaptionDto = transaction {
        Captions.selectAll().where { Captions.assetId eq id }.firstOrNull()
    }?.let { row ->
        CaptionDto(
            caption = row[Captions.caption],
            tags = row[Captions.tags]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            model = row[Captions.model],
        )
    } ?: CaptionDto(caption = null, tags = emptyList(), model = null)

    /**
     * 사진 삭제 = 휴지통 이동. 파일은 그대로 두고 deleted_at만 기록한다.
     * 30일 뒤 TrashService가 자동 영구 삭제하며, 그 전에는 /trash API로 복원 가능.
     * 휴지통·영구삭제 모두 재백업 스킵 대상이다.
     */
    @DeleteMapping("/assets/{id}")
    fun delete(@PathVariable id: Long): Map<String, Any> {
        val row = findAsset(id)
        transaction {
            Assets.update({ Assets.id eq id }) {
                it[deletedAt] = java.time.LocalDateTime.now().format(AssetIngestService.ISO)
            }
        }
        log.info("휴지통 이동: #{} {} ({}일 후 자동 영구 삭제)", id, row[Assets.originalFilename], props.trashRetentionDays)
        return mapOf("trashed" to true, "id" to id)
    }

    data class FavoriteRequest(val favorite: Boolean)

    @PostMapping("/assets/{id}/favorite")
    fun setFavorite(@PathVariable id: Long, @RequestBody request: FavoriteRequest): AssetDto {
        transaction {
            Assets.update({ Assets.id eq id }) { it[favorite] = request.favorite }
        }
        return findAsset(id).toAssetDto()
    }

    @GetMapping("/assets/{id}/thumb")
    fun thumb(@PathVariable id: Long, @RequestParam(defaultValue = "400") size: Int): ResponseEntity<Resource> {
        if (size !in ThumbnailService.SIZES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be one of ${ThumbnailService.SIZES}")
        }
        val asset = findAssetIncludingTrashed(id) // 휴지통 뷰에서도 썸네일이 보여야 한다
        val path = thumbnailService.thumbPath(asset[Assets.hash], size)
        if (!Files.exists(path)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "thumbnail not ready")
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            // 썸네일은 해시 기반이라 사실상 불변 — 장기 캐시로 그리드 재방문/스크롤을 빠르게
            .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofDays(30)))
            .body(FileSystemResource(path))
    }

    /** 원본 서빙. Resource 반환이라 동영상의 HTTP Range 요청은 Spring이 처리한다. */
    @GetMapping("/assets/{id}/file")
    fun file(@PathVariable id: Long): ResponseEntity<Resource> {
        val asset = findAssetIncludingTrashed(id)
        val path = props.storageRoot.resolve(asset[Assets.originalPath])
        if (!Files.exists(path)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "file missing on disk")
        val ext = asset[Assets.originalPath].substringAfterLast('.', "").lowercase()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(CONTENT_TYPES[ext] ?: "application/octet-stream"))
            .body(FileSystemResource(path))
    }

    private fun findAsset(id: Long) = transaction {
        Assets.selectAll().where { (Assets.id eq id) and Assets.deletedAt.isNull() }.firstOrNull()
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "asset $id not found")

    /** 휴지통 항목 포함 조회 (영구 삭제된 것은 파일이 없으므로 제외) */
    private fun findAssetIncludingTrashed(id: Long) = transaction {
        Assets.selectAll().where { (Assets.id eq id) and Assets.purgedAt.isNull() }.firstOrNull()
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "asset $id not found")

    private fun parseCursor(cursor: String): Pair<String, Long> {
        val idx = cursor.lastIndexOf('~')
        require(idx > 0) { "invalid cursor" }
        return cursor.substring(0, idx) to cursor.substring(idx + 1).toLong()
    }

    companion object {
        private val CONTENT_TYPES = mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
            "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
            "heic" to "image/heic", "heif" to "image/heif", "dng" to "image/x-adobe-dng",
            "tif" to "image/tiff", "tiff" to "image/tiff", "avif" to "image/avif",
            "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime",
            "3gp" to "video/3gpp", "avi" to "video/x-msvideo", "mkv" to "video/x-matroska",
            "webm" to "video/webm", "wmv" to "video/x-ms-wmv", "mts" to "video/mp2t",
        )
    }
}
