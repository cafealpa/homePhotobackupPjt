package com.homephoto.server.service

import com.homephoto.server.api.*
import com.homephoto.server.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** 타임라인 필터와 양방향 커서 페이징 규칙. HTTP 요청/파일 전송과 분리한다. */
data class AssetFilter(
    val yearMonth: String? = null, val cursor: String? = null, val after: String? = null,
    val clusterId: Int? = null, val albumId: Long? = null, val day: String? = null,
    val deviceId: String? = null, val favorite: Boolean? = null,
    val minLat: Double? = null, val maxLat: Double? = null,
    val minLon: Double? = null, val maxLon: Double? = null, val limit: Int = 100,
    val mediaType: String? = null,
    val startDate: java.time.LocalDate? = null, val endDate: java.time.LocalDate? = null,
)

@Service
class AssetQueryService {
    fun list(filter: AssetFilter): AssetPageDto = with(filter) {
        val pageSize = limit.coerceIn(1, 500)
        val items = transaction {
            var query = Assets.selectAll().where { Assets.deletedAt.isNull() and Assets.sourceTag.isNull() }
            filter.mediaType?.let { type -> query = query.andWhere { Assets.mediaType eq type } }
            startDate?.let { date -> query = query.andWhere { Assets.takenAt greaterEq date.toString() } }
            // 다음 날 미만으로 비교해 종료일의 소수점 이하 초까지 포함한다.
            endDate?.let { date -> query = query.andWhere { Assets.takenAt less date.plusDays(1).toString() } }
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
            if (after != null) {
                // 최신 방향(위로 스크롤): 커서보다 새로운 것을 오래된→새로운 순으로 pageSize개 뽑은 뒤
                // 뒤집어 응답은 항상 최신순을 유지한다. 연도 점프 후 위로 올릴 때 쓴다.
                val (takenAtCursor, idCursor) = parseCursor(after)
                query = query.andWhere {
                    (Assets.takenAt greater takenAtCursor) or
                        ((Assets.takenAt eq takenAtCursor) and (Assets.id greater idCursor))
                }
                query.orderBy(Assets.takenAt to SortOrder.ASC, Assets.id to SortOrder.ASC)
                    .limit(pageSize)
                    .map { it.toAssetDto() }
                    .asReversed()
            } else {
                query.orderBy(Assets.takenAt to SortOrder.DESC, Assets.id to SortOrder.DESC)
                    .limit(pageSize)
                    .map { it.toAssetDto() }
            }
        }
        val full = items.size == pageSize
        // after= 요청은 "이 커서보다 새로운 것"만 받았으므로 오래된 쪽 끝이 아니다 — nextCursor를 주지 않는다
        // (클라이언트는 이미 그 아래를 갖고 있다). 그냥 요청은 반대로 prevCursor가 없다.
        val nextCursor = if (after == null && full) items.last().let { "${it.takenAt}~${it.id}" } else null
        val prevCursor = if (after != null && full) items.first().let { "${it.takenAt}~${it.id}" } else null
        AssetPageDto(items = items, nextCursor = nextCursor, prevCursor = prevCursor)
    }

    private fun parseCursor(cursor: String): Pair<String, Long> {
        val idx = cursor.lastIndexOf('~')
        require(idx > 0) { "invalid cursor" }
        return cursor.substring(0, idx) to cursor.substring(idx + 1).toLong()
    }
}
