package com.homephoto.server.api

import org.jetbrains.exposed.sql.DoubleColumnType
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.math.pow

data class MapClusterDto(
    val lat: Double,            // 멤버 평균 좌표 = 핀 위치
    val lon: Double,
    val count: Long,
    val coverAssetId: Long,     // 대표 썸네일용 (셀 내 최신 백업분)
    val minLat: Double,         // 멤버 실좌표 범위 — 클러스터 상세 조회의 bbox로 그대로 사용
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

/** 지도 뷰: 줌 레벨별 그리드 스냅 클러스터링. SQLite에 공간 인덱스가 없어 순수 SQL 집계로 처리. */
@RestController
@RequestMapping("/api/v1")
class MapController {

    @GetMapping("/map/clusters")
    fun clusters(
        @RequestParam zoom: Int,
        @RequestParam minLat: Double,
        @RequestParam maxLat: Double,
        @RequestParam minLon: Double,
        @RequestParam maxLon: Double,
    ): List<MapClusterDto> {
        require(minLat <= maxLat) { "minLat must be <= maxLat" }
        require(minLon <= maxLon) { "minLon must be <= maxLon" }
        // 세계지도 랩 등으로 범위 밖 값이 와도 조용히 클램프
        val loLat = minLat.coerceIn(-90.0, 90.0)
        val hiLat = maxLat.coerceIn(-90.0, 90.0)
        val loLon = minLon.coerceIn(-180.0, 180.0)
        val hiLon = maxLon.coerceIn(-180.0, 180.0)
        // 셀 한 변의 크기(도). 88px = 핀 지름 64px + 여백 — 줌과 무관하게 화면상 핀 간격이 일정해진다.
        val cell = 360.0 * 88 / (256.0 * 2.0.pow(zoom.coerceIn(0, 19)))

        // floor()는 SQLite math 확장 컴파일 여부에 의존하므로 +90/+180 시프트로 양수화해
        // CAST 절삭이 floor와 동일해지도록 한다. WHERE는 나중에 키즈노트 토글 시
        // source 줄만 파라미터화하면 되도록 줄 단위로 조립한다.
        val where = listOf(
            "deleted_at IS NULL",
            "source IS NULL",
            "gps_lat IS NOT NULL",
            "gps_lon IS NOT NULL",
            "NOT (gps_lat = 0.0 AND gps_lon = 0.0)", // (0,0) = 위치 없음 규칙
            "gps_lat BETWEEN ? AND ?",
            "gps_lon BETWEEN ? AND ?",
        ).joinToString(" AND ")
        val sql = """
            SELECT CAST((gps_lat + 90.0) / ? AS INTEGER) AS cy,
                   CAST((gps_lon + 180.0) / ? AS INTEGER) AS cx,
                   COUNT(*), AVG(gps_lat), AVG(gps_lon),
                   MAX(id),
                   MIN(gps_lat), MAX(gps_lat), MIN(gps_lon), MAX(gps_lon)
            FROM assets
            WHERE $where
            GROUP BY cy, cx
        """.trimIndent()
        val args = listOf(cell, cell, loLat, hiLat, loLon, hiLon).map { DoubleColumnType() to it }

        return transaction {
            exec(sql, args) { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            MapClusterDto(
                                count = rs.getLong(3),
                                lat = rs.getDouble(4),
                                lon = rs.getDouble(5),
                                coverAssetId = rs.getLong(6),
                                minLat = rs.getDouble(7),
                                maxLat = rs.getDouble(8),
                                minLon = rs.getDouble(9),
                                maxLon = rs.getDouble(10),
                            )
                        )
                    }
                }
            } ?: emptyList()
        }
    }
}
