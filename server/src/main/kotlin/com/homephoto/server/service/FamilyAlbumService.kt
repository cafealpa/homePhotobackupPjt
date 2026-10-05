package com.homephoto.server.service

import com.homephoto.server.api.AssetDto
import com.homephoto.server.api.AssetPageDto
import com.homephoto.server.api.toAssetDto
import com.homephoto.server.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class FamilyDevice(val id: String?, val name: String, val count: Int)
data class FamilyAlbumSummary(
    val kind: String, val date: String, val endDate: String, val title: String,
    val albumId: Long?, val coverAssetId: Long?, val photoCount: Int, val deviceCount: Int,
)
data class FamilyAlbumDetail(
    val summary: FamilyAlbumSummary, val note: String, val revision: Int,
    val selected: List<AssetDto>, val devices: List<FamilyDevice>,
)
data class FamilyAlbumHome(val weekly: List<FamilyAlbumSummary>, val together: List<FamilyAlbumSummary>, val saved: List<FamilyAlbumSummary>)
data class SaveFamilyAlbum(val title: String, val note: String = "", val assetIds: List<Long>, val revision: Int = 0)
internal data class FamilyPhoto(val id: Long, val takenAt: String, val deviceId: String?, val favorite: Boolean)
internal data class FamilyPeriod(val kind: String, val start: LocalDate, val end: LocalDate)

/** 후보 조회는 읽기 전용. 사용자가 저장한 선택·순서·기록만 기존 앨범 테이블에 확정한다. */
@Service
class FamilyAlbumService(private val assets: AssetQueryService) {
    fun home(today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))): FamilyAlbumHome = transaction {
        val current = period("WEEKLY", today.toString())
        val recent = photos(FamilyPeriod("TOGETHER", today.minusDays(29), today))
        val savedRows = Albums.selectAll().where { Albums.storyKind.isNotNull() }
            .orderBy(Albums.periodStart to SortOrder.DESC).limit(20).toList()
        FamilyAlbumHome(
            weekly = listOf(current, current.copy(start = current.start.minusWeeks(1), end = current.end.minusWeeks(1)))
                .map { p -> summary(p, photos(p), saved(p)) },
            together = recent.groupBy { it.takenAt.take(10) }.entries
                .filter { (_, rows) -> rows.mapNotNull { it.deviceId }.distinct().size >= 2 }
                .sortedByDescending { it.key }.take(8)
                .map { (date, rows) -> val p = period("TOGETHER", date); summary(p, rows, saved(p)) },
            saved = savedRows.map { row ->
                val p = period(row[Albums.storyKind]!!, row[Albums.periodStart]!!)
                summary(p, photos(p), row)
            },
        )
    }

    fun detail(kind: String, date: String): FamilyAlbumDetail = transaction {
        val p = period(kind, date)
        val rows = photos(p)
        val saved = saved(p)
        val selected = if (saved == null) resolve(recommend(rows)) else savedPhotos(saved[Albums.id])
        val deviceNames = Devices.selectAll().associate { it[Devices.id] to it[Devices.name] }
        FamilyAlbumDetail(summary(p, rows, saved), saved?.get(Albums.note) ?: "", saved?.get(Albums.revision) ?: 0,
            selected, rows.groupBy { it.deviceId }.map { (id, group) ->
                FamilyDevice(id, if (id == null) "기기 정보 없음" else deviceNames[id] ?: "등록된 기기", group.size)
            }.sortedByDescending { it.count })
    }

    fun candidates(kind: String, date: String, cursor: String?): AssetPageDto {
        val p = period(kind, date)
        return assets.list(AssetFilter(startDate = p.start, endDate = p.end, mediaType = "PHOTO", cursor = cursor, limit = 60))
    }

    @Synchronized
    fun save(kind: String, date: String, request: SaveFamilyAlbum): FamilyAlbumDetail {
        val p = period(kind, date)
        require(request.title.trim().length in 1..100) { "제목은 1~100자로 입력해 주세요." }
        require(request.note.length <= 1000) { "기록은 1000자 이내로 입력해 주세요." }
        require(request.assetIds.size in 1..100 && request.assetIds.distinct().size == request.assetIds.size) { "사진은 중복 없이 1~100장 선택해 주세요." }
        transaction {
            val existing = saved(p)
            if ((existing?.get(Albums.revision) ?: 0) != request.revision) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "다른 기기에서 앨범을 수정했어요. 다시 열어 확인해 주세요.")
            }
            val valid = photos(p).map { it.id }.toSet()
            require(request.assetIds.all { it in valid }) { "선택한 사진이 삭제되었거나 앨범 기간에 속하지 않아요. 다시 확인해 주세요." }
            val now = LocalDateTime.now().toString()
            val albumId = existing?.get(Albums.id) ?: Albums.insert {
                it[name] = request.title.trim(); it[createdAt] = now
                it[storyKind] = p.kind; it[periodStart] = p.start.toString(); it[periodEnd] = p.end.toString()
            }[Albums.id]
            Albums.update({ Albums.id eq albumId }) {
                it[name] = request.title.trim(); it[note] = request.note.trim()
                it[coverAssetId] = request.assetIds.first(); it[revision] = request.revision + 1
            }
            AlbumAssets.deleteWhere { AlbumAssets.albumId eq albumId }
            request.assetIds.forEachIndexed { index, id -> AlbumAssets.insert {
                it[AlbumAssets.albumId] = albumId; it[assetId] = id; it[addedAt] = now; it[position] = index
            } }
        }
        return detail(kind, date)
    }

    private fun saved(p: FamilyPeriod): ResultRow? = Albums.selectAll()
        .where { (Albums.storyKind eq p.kind) and (Albums.periodStart eq p.start.toString()) }.firstOrNull()

    private fun photos(p: FamilyPeriod): List<FamilyPhoto> = Assets
        .select(Assets.id, Assets.takenAt, Assets.deviceId, Assets.favorite)
        .where { Assets.deletedAt.isNull() and Assets.purgedAt.isNull() and Assets.sourceTag.isNull() and
            (Assets.mediaType eq "PHOTO") and (Assets.takenAt greaterEq p.start.toString()) and (Assets.takenAt less p.end.plusDays(1).toString()) }
        .orderBy(Assets.takenAt to SortOrder.ASC, Assets.id to SortOrder.ASC)
        .map { FamilyPhoto(it[Assets.id], it[Assets.takenAt]!!, it[Assets.deviceId], it[Assets.favorite]) }

    private fun savedPhotos(id: Long): List<AssetDto> = (AlbumAssets innerJoin Assets).selectAll()
        .where { (AlbumAssets.albumId eq id) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull() and Assets.sourceTag.isNull() }
        .orderBy(AlbumAssets.position to SortOrder.ASC, AlbumAssets.id to SortOrder.ASC).map { it.toAssetDto() }

    private fun resolve(ids: List<Long>): List<AssetDto> {
        val byId = Assets.selectAll().where { Assets.id inList ids }.associate { it[Assets.id] to it.toAssetDto() }
        return ids.mapNotNull(byId::get)
    }

    private fun summary(p: FamilyPeriod, rows: List<FamilyPhoto>, saved: ResultRow?): FamilyAlbumSummary {
        val selected = saved?.let { savedPhotos(it[Albums.id]) }
        val defaultTitle = if (p.kind == "WEEKLY") "${p.start.monthValue}월 ${p.start.dayOfMonth}일부터, 우리 가족" else "${p.start.monthValue}월 ${p.start.dayOfMonth}일, 함께 찍은 하루"
        return FamilyAlbumSummary(p.kind, p.start.toString(), p.end.toString(), saved?.get(Albums.name) ?: defaultTitle,
            saved?.get(Albums.id), selected?.firstOrNull()?.id ?: if (saved == null) recommend(rows).firstOrNull() else null,
            rows.size, rows.mapNotNull { it.deviceId }.distinct().size)
    }

    companion object {
        internal fun period(kind: String, date: String): FamilyPeriod {
            require(kind in setOf("WEEKLY", "TOGETHER")) { "지원하지 않는 앨범 종류예요." }
            val parsed = try { LocalDate.parse(date) } catch (_: java.time.DateTimeException) { throw IllegalArgumentException("날짜는 YYYY-MM-DD 형식으로 입력해 주세요.") }
            require(parsed.year in 1..9998) { "날짜 범위를 확인해 주세요." }
            val start = if (kind == "WEEKLY") parsed.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else parsed
            return FamilyPeriod(kind, start, if (kind == "WEEKLY") start.plusDays(6) else start)
        }

        /** 날짜를 먼저 고르게, 각 날짜 안에서는 기기를 번갈아 선택한다. 같은 원본은 assets에서 이미 중복 제거된다. */
        internal fun recommend(photos: List<FamilyPhoto>, limit: Int = 8): List<Long> {
            val remaining = photos.toMutableList()
            val days = mutableMapOf<String, Int>()
            val devices = mutableMapOf<String?, Int>()
            val selected = mutableListOf<Long>()
            repeat(minOf(limit, remaining.size)) {
                val photo = remaining.minWith(compareBy<FamilyPhoto> { days[it.takenAt.take(10)] ?: 0 }
                    .thenBy { devices[it.deviceId] ?: 0 }.thenByDescending { it.favorite }
                    .thenBy { it.takenAt }.thenBy { it.id })
                selected += photo.id
                days.merge(photo.takenAt.take(10), 1, Int::plus)
                devices[photo.deviceId] = (devices[photo.deviceId] ?: 0) + 1
                remaining.remove(photo)
            }
            return selected
        }
    }
}
