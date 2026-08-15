package com.homephoto.server.api

import com.homephoto.server.db.AlbumAssets
import com.homephoto.server.db.Albums
import com.homephoto.server.db.Assets
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class AlbumSummaryDto(
    val id: Long,
    val name: String,
    val count: Long,          // 활성(휴지통·키즈노트 제외) 사진 수
    val coverAssetId: Long?,  // 썸네일은 /assets/{id}/thumb — 빈 앨범이면 null
    val createdAt: String,
)

data class CreateAlbumRequest(val name: String)
data class RenameAlbumRequest(val name: String)
data class AlbumAssetsRequest(val assetIds: List<Long>)

/** 수동 앨범 CRUD + 사진 연결 관리. 사진 원본은 불변 — album_assets 연결만 조작한다. */
@RestController
@RequestMapping("/api/v1")
class AlbumController {

    @GetMapping("/albums")
    fun list(): List<AlbumSummaryDto> = transaction {
        val albums = Albums.selectAll()
            .orderBy(Albums.createdAt to SortOrder.DESC, Albums.id to SortOrder.DESC)
            .toList()

        // 앨범별 활성 사진 수 — 휴지통·키즈노트 제외 (상세 뷰 /assets?albumId 필터와 일치해야 한다)
        val cnt = Assets.id.count()
        val counts: Map<Long, Long> = (AlbumAssets innerJoin Assets)
            .select(AlbumAssets.albumId, cnt)
            .where { Assets.deletedAt.isNull() and Assets.sourceTag.isNull() }
            .groupBy(AlbumAssets.albumId)
            .associate { it[AlbumAssets.albumId] to it[cnt] }

        albums.map { it.toSummaryDto(counts[it[Albums.id]] ?: 0L) }
    }

    @PostMapping("/albums")
    fun create(@RequestBody request: CreateAlbumRequest): AlbumSummaryDto {
        val name = request.name.trim()
        require(name.isNotEmpty()) { "name must not be empty" }
        return transaction {
            val id = Albums.insert {
                it[Albums.name] = name
                it[createdAt] = now()
            } get Albums.id
            AlbumSummaryDto(id = id, name = name, count = 0, coverAssetId = null, createdAt = now())
        }
    }

    @PostMapping("/albums/{id}/name")
    fun rename(@PathVariable id: Long, @RequestBody request: RenameAlbumRequest): Map<String, Any> {
        val name = request.name.trim()
        require(name.isNotEmpty()) { "name must not be empty" }
        transaction {
            findAlbum(id)
            Albums.update({ Albums.id eq id }) { it[Albums.name] = name }
        }
        return mapOf("id" to id, "name" to name)
    }

    /** 앨범 삭제 — 연결 행만 지운다. 사진(assets)은 그대로. */
    @DeleteMapping("/albums/{id}")
    fun delete(@PathVariable id: Long): Map<String, Any> = transaction {
        findAlbum(id)
        AlbumAssets.deleteWhere { albumId eq id }
        Albums.deleteWhere { Albums.id eq id }
        mapOf("deleted" to true, "id" to id)
    }

    /** 사진 추가 — UNIQUE(album_id, asset_id) + insertIgnore로 재추가 멱등. */
    @PostMapping("/albums/{id}/assets")
    fun addAssets(@PathVariable id: Long, @RequestBody request: AlbumAssetsRequest): Map<String, Any> {
        require(request.assetIds.isNotEmpty()) { "assetIds must not be empty" }
        return transaction {
            findAlbum(id)
            // SQLite JDBC는 FK를 강제하지 않으므로 실재하는 활성 자산만 걸러 고아 행을 막는다
            val valid = Assets.select(Assets.id)
                .where { (Assets.id inList request.assetIds) and Assets.deletedAt.isNull() }
                .map { it[Assets.id] }
            val addedAt = now()
            var added = 0
            for (assetId in valid) {
                val result = AlbumAssets.insertIgnore {
                    it[albumId] = id
                    it[AlbumAssets.assetId] = assetId
                    it[AlbumAssets.addedAt] = addedAt
                }
                if (result.insertedCount > 0) added++
            }
            mapOf("added" to added, "requested" to request.assetIds.size)
        }
    }

    /** 사진 제거. DELETE+body는 클라이언트 지원이 갈려서 POST 하위 경로로 받는다. */
    @PostMapping("/albums/{id}/assets/remove")
    fun removeAssets(@PathVariable id: Long, @RequestBody request: AlbumAssetsRequest): Map<String, Any> {
        require(request.assetIds.isNotEmpty()) { "assetIds must not be empty" }
        return transaction {
            findAlbum(id)
            val removed = AlbumAssets.deleteWhere { (albumId eq id) and (assetId inList request.assetIds) }
            mapOf("removed" to removed)
        }
    }

    /** transaction 안에서 호출. 없으면 404. */
    private fun findAlbum(id: Long): ResultRow =
        Albums.selectAll().where { Albums.id eq id }.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "album $id not found")

    private fun ResultRow.toSummaryDto(count: Long): AlbumSummaryDto {
        val id = this[Albums.id]
        // 지정 커버가 유효(활성)하면 사용, 아니면 앨범 내 최신 활성 사진으로 폴백
        val explicit = this[Albums.coverAssetId]?.takeIf { cid ->
            Assets.selectAll().where { (Assets.id eq cid) and Assets.deletedAt.isNull() }.count() > 0
        }
        val cover = explicit ?: (AlbumAssets innerJoin Assets)
            .select(Assets.id)
            .where { (AlbumAssets.albumId eq id) and Assets.deletedAt.isNull() and Assets.sourceTag.isNull() }
            .orderBy(Assets.takenAt to SortOrder.DESC, Assets.id to SortOrder.DESC)
            .limit(1)
            .firstOrNull()?.get(Assets.id)
        return AlbumSummaryDto(
            id = id,
            name = this[Albums.name],
            count = count,
            coverAssetId = cover,
            createdAt = this[Albums.createdAt],
        )
    }

    private fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}
