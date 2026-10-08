package com.homephoto.server.service

import com.homephoto.server.api.AssetDto
import com.homephoto.server.api.toAssetDto
import com.homephoto.server.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class MemorySummary(val id: Long, val title: String, val startDate: String, val endDate: String,
                         val coverAssetId: Long?, val photoCount: Int, val albumId: Long?)
data class MemoryDetail(val summary: MemorySummary, val photos: List<AssetDto>)
data class MemoryHome(val date: String, val memories: List<MemorySummary>, val saved: List<MemorySummary>)
data class SaveMemoryAlbum(val title: String, val assetIds: List<Long>)
internal data class MemoryCandidate(val asset: AssetDto, val namedPeople: Set<Long>) {
    val score get() = (if (asset.favorite) 4 else 0) + (if (namedPeople.isNotEmpty()) 2 else 0)
}
private data class MemoryDay(val date: LocalDate, val score: Int)
private object MemoryDocuments : Table("document_analysis") {
    val assetId = long("asset_id")
    val classification = text("classification")
}

/** Date selection and saved snapshots are separate from user-created albums. */
@Service
class MemoryService {
    @Synchronized
    fun home(today: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))): MemoryHome = transaction {
        if (MemorySets.selectAll().where { MemorySets.generatedOn eq today.toString() }.empty()) generate(today)
        val memories = MemorySets.selectAll().where { (MemorySets.generatedOn eq today.toString()) and (MemorySets.kind neq "EMPTY") }
            .orderBy(MemorySets.id to SortOrder.ASC).map { row -> summarize(row, snapshotPhotos(row[MemorySets.id])) }
            .filter { it.photoCount > 0 }
        val saved = (MemorySets innerJoin Albums).selectAll()
            .orderBy(MemorySets.id to SortOrder.DESC).limit(20).map { row ->
                summarize(row, albumPhotos(row[Albums.id]), row[Albums.name], row[Albums.coverAssetId])
            }
        MemoryHome(today.toString(), memories, saved)
    }

    fun detail(id: Long): MemoryDetail = transaction {
        val row = find(id)
        val photos = snapshotPhotos(id)
        MemoryDetail(summarize(row, photos), photos)
    }

    fun savedAlbum(id: Long): MemoryDetail = transaction {
        val row = MemorySets.selectAll().where { MemorySets.albumId eq id }.firstOrNull() ?: missing()
        val album = Albums.selectAll().where { Albums.id eq id }.firstOrNull() ?: missing()
        val photos = albumPhotos(id)
        MemoryDetail(summarize(row, photos, album[Albums.name], album[Albums.coverAssetId]), photos)
    }

    @Synchronized
    fun save(id: Long, request: SaveMemoryAlbum): MemoryDetail = transaction {
        val row = find(id)
        row[MemorySets.albumId]?.let { existing ->
            if (!Albums.selectAll().where { Albums.id eq existing }.empty()) return@transaction savedAlbum(existing)
        }
        require(request.title.trim().length in 1..100) { "제목은 1~100자로 입력해 주세요." }
        require(request.assetIds.size in 1..100 && request.assetIds.distinct().size == request.assetIds.size) { "사진을 중복 없이 선택해 주세요." }
        val valid = snapshotPhotos(id).map { it.id }.toSet()
        require(request.assetIds.all { it in valid }) { "선택한 사진이 더 이상 추억에 없어요. 다시 열어 확인해 주세요." }
        val now = LocalDateTime.now().toString()
        val albumId = Albums.insert {
            it[name] = request.title.trim(); it[createdAt] = now; it[coverAssetId] = request.assetIds.first()
        }[Albums.id]
        request.assetIds.forEachIndexed { index, asset -> AlbumAssets.insert {
            it[AlbumAssets.albumId] = albumId; it[assetId] = asset; it[position] = index; it[addedAt] = now
        } }
        MemorySets.update({ MemorySets.id eq id }) { it[MemorySets.albumId] = albumId }
        savedAlbum(albumId)
    }

    private fun generate(today: LocalDate) {
        val history = MemorySets.selectAll().where { (MemorySets.kind eq "ANNIVERSARY") or (MemorySets.kind eq "REDISCOVER") }
            .groupBy { it[MemorySets.startDate] }.mapValues { (_, rows) -> rows.maxOf { it[MemorySets.generatedOn] } }
        val used = mutableSetOf<Long>()
        val oldDays = days(today.minusDays(90))
        fun choose(candidates: List<MemoryDay>, anniversary: Boolean): MemoryDay? = candidates.sortedWith(
            compareBy<MemoryDay> { (history[it.date.toString()] ?: "") >= today.minusDays(30).toString() }
                .thenBy { if ((history[it.date.toString()] ?: "") >= today.minusDays(30).toString()) history[it.date.toString()] else "" }
                .thenBy { if (anniversary) anniversaryDistance(today, it.date) else 0 }
                .thenByDescending { it.score }
                .thenBy { "${today}:${it.date}".hashCode() }
        ).firstOrNull()
        val anniversary = choose(oldDays.filter { it.date.year < today.year && anniversaryDistance(today, it.date) <= 7 }, true)
        fun createDay(day: MemoryDay, kind: String, title: String) {
            val selected = select(candidates(day.date, day.date), 12)
            persist(today, kind, title, day.date, day.date, selected); used.addAll(selected.map { it.asset.id })
        }
        anniversary?.let { createDay(it, "ANNIVERSARY", "${anniversaryYears(today, it.date)}년 전 이맘때") }
        choose(oldDays.filter { it.date != anniversary?.date }, false)?.let { createDay(it, "REDISCOVER", "다시 꺼내 보는 하루") }
        val recent = select(candidates(today.minusDays(6), today).filterNot { it.asset.id in used }, 12)
        if (recent.isNotEmpty()) persist(today, "RECENT", "최근 일주일 돌아보기", today.minusDays(6), today, recent)
        else {
            val added = select(candidates(today.minusDays(6), today, added = true).filterNot { it.asset.id in used }, 12)
            if (added.isNotEmpty()) persist(today, "ADDED", "최근 추가한 사진", today.minusDays(6), today, added)
        }
        if (MemorySets.selectAll().where { MemorySets.generatedOn eq today.toString() }.empty())
            persist(today, "EMPTY", "", today, today, emptyList())
    }

    /** Aggregate dates in SQL; only load photo rows for the chosen days. No image/AI work on a request. */
    private fun days(before: LocalDate): List<MemoryDay> = org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec("""
        SELECT substr(a.taken_at,1,10) AS day,
          min(sum(CASE WHEN a.favorite=1 THEN 1 ELSE 0 END),4)*4 +
          min(sum(CASE WHEN EXISTS(SELECT 1 FROM faces f JOIN persons p ON p.id=f.person_id
            WHERE f.asset_id=a.id AND f.hidden=0 AND trim(coalesce(p.name,''))!='') THEN 1 ELSE 0 END),4)*2 AS score
        FROM assets a WHERE a.deleted_at IS NULL AND a.purged_at IS NULL AND a.source IS NULL
          AND a.media_type='PHOTO' AND a.taken_at_source IN ('EXIF','FILENAME') AND a.taken_at < '${before.plusDays(1)}'
          AND NOT EXISTS(SELECT 1 FROM document_analysis d WHERE d.asset_id=a.id AND d.classification='DOCUMENT')
        GROUP BY substr(a.taken_at,1,10) HAVING count(*)>=4
    """) { rs -> buildList { while (rs.next()) {
        val date = runCatching { LocalDate.parse(rs.getString("day")) }.getOrNull()
        if (date != null) add(MemoryDay(date, rs.getInt("score")))
    } } }.orEmpty()

    private fun active(): Op<Boolean> = Op.build { Assets.deletedAt.isNull() and Assets.purgedAt.isNull() and
        Assets.sourceTag.isNull() and (Assets.mediaType eq "PHOTO") }
    private fun eligible(): Op<Boolean> = Op.build { active() and (Assets.id notInSubQuery MemoryDocuments.select(MemoryDocuments.assetId)
        .where { MemoryDocuments.classification eq "DOCUMENT" }) }

    private fun candidates(start: LocalDate, end: LocalDate, added: Boolean = false): List<MemoryCandidate> {
        val date = if (added) Assets.createdAt else Assets.takenAt
        val rows = Assets.selectAll().where { eligible() and (date greaterEq start.toString()) and (date less end.plusDays(1).toString()) }
            .apply { if (!added) andWhere { Assets.takenAtSource inList listOf("EXIF", "FILENAME") } }
            .orderBy(date to SortOrder.ASC, Assets.id to SortOrder.ASC).map { it.toAssetDto() }
        if (rows.isEmpty()) return emptyList()
        // Subquery avoids SQLite parameter limits on a busy day.
        val named = (Faces innerJoin Persons).select(Faces.assetId, Persons.id).where {
            (Faces.hidden eq false) and Persons.name.isNotNull() and (Persons.name neq "") and
                (Faces.assetId inSubQuery Assets.select(Assets.id).where { eligible() and (date greaterEq start.toString()) and (date less end.plusDays(1).toString()) })
        }.groupBy({ it[Faces.assetId] }, { it[Persons.id] }).mapValues { it.value.toSet() }
        return rows.map { MemoryCandidate(it, named[it.id].orEmpty()) }
    }

    private fun persist(today: LocalDate, type: String, label: String, start: LocalDate, end: LocalDate, photos: List<MemoryCandidate>) {
        val cover = photos.maxWithOrNull(compareBy<MemoryCandidate> { it.score }.thenBy { -it.asset.id })?.asset?.id
        val id = MemorySets.insert {
            it[generatedOn] = today.toString(); it[kind] = type; it[title] = label
            it[startDate] = start.toString(); it[endDate] = end.toString(); it[coverAssetId] = cover
        }[MemorySets.id]
        photos.forEachIndexed { index, photo -> MemoryPhotos.insert {
            it[memoryId] = id; it[assetId] = photo.asset.id; it[position] = index
        } }
    }

    private fun snapshotPhotos(id: Long) = (MemoryPhotos innerJoin Assets).selectAll()
        .where { (MemoryPhotos.memoryId eq id) and eligible() }
        .orderBy(MemoryPhotos.position to SortOrder.ASC).map { it.toAssetDto() }
    private fun albumPhotos(id: Long) = (AlbumAssets innerJoin Assets).selectAll()
        .where { (AlbumAssets.albumId eq id) and active() }
        .orderBy(AlbumAssets.position to SortOrder.ASC, AlbumAssets.id to SortOrder.ASC).map { it.toAssetDto() }
    private fun find(id: Long) = MemorySets.selectAll().where { (MemorySets.id eq id) and (MemorySets.kind neq "EMPTY") }.firstOrNull() ?: missing()
    private fun missing(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "추억이나 앨범을 찾을 수 없어요.")
    private fun summarize(row: ResultRow, photos: List<AssetDto>, title: String = row[MemorySets.title], cover: Long? = row[MemorySets.coverAssetId]) = MemorySummary(
        row[MemorySets.id], title, row[MemorySets.startDate], row[MemorySets.endDate],
        cover?.takeIf { id -> photos.any { it.id == id } } ?: photos.firstOrNull()?.id,
        photos.size, row[MemorySets.albumId]?.takeIf { !Albums.selectAll().where { Albums.id eq it }.empty() })

    companion object {
        private fun anniversaryYears(today: LocalDate, date: LocalDate): Int =
            (maxOf(1, today.year - date.year - 1)..maxOf(1, today.year - date.year + 1)).minBy { years ->
                abs(ChronoUnit.DAYS.between(today.minusYears(years.toLong()), date))
            }
        internal fun anniversaryDistance(today: LocalDate, date: LocalDate): Long =
            abs(ChronoUnit.DAYS.between(today.minusYears(anniversaryYears(today, date).toLong()), date))

        internal fun select(input: List<MemoryCandidate>, limit: Int): List<MemoryCandidate> {
            val remaining = input.toMutableList()
            val days = mutableMapOf<String, Int>()
            val buckets = mutableMapOf<String, Int>()
            val people = mutableMapOf<Long, Int>()
            val selected = mutableListOf<MemoryCandidate>()
            fun day(p: MemoryCandidate) = p.asset.takenAt?.take(10).orEmpty()
            fun bucket(p: MemoryCandidate): String {
                val time = runCatching { LocalDateTime.parse(p.asset.takenAt) }.getOrNull()
                return if (time == null) "asset:${p.asset.id}" else "${p.asset.deviceId}:${day(p)}:${time.hour}:${time.minute / 15}"
            }
            repeat(minOf(limit, remaining.size)) {
                val next = remaining.minWith(compareBy<MemoryCandidate> { days[day(it)] ?: 0 }
                    .thenBy { buckets[bucket(it)] ?: 0 }
                    .thenByDescending { it.score - (it.namedPeople.maxOfOrNull { person -> people[person] ?: 0 } ?: 0) }
                    .thenBy { it.asset.takenAt }.thenBy { it.asset.id })
                selected += next; remaining.remove(next)
                days.merge(day(next), 1, Int::plus); buckets.merge(bucket(next), 1, Int::plus)
                next.namedPeople.forEach { people.merge(it, 1, Int::plus) }
            }
            return selected.sortedWith(compareBy<MemoryCandidate> { it.asset.takenAt }.thenBy { it.asset.id })
        }
    }
}
