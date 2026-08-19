package com.homephoto.server.api

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Albums
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Devices
import com.homephoto.server.db.Faces
import com.homephoto.server.db.Jobs
import com.homephoto.server.db.Persons
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files

/**
 * 대시보드용 집계 API. 타임라인과 같은 기준(휴지통·키즈노트 전용 제외)으로 센다.
 * 목록 API와 달리 전건 집계라 페이지네이션이 없다 — 10만 장에서도 수십 ms 수준.
 */
@RestController
@RequestMapping("/api/v1/stats")
class StatsController(private val props: AppProperties) {

    data class SummaryDto(
        /** 타임라인에 보이는 사진+동영상 */
        val assets: Long,
        val photos: Long,
        val videos: Long,
        /** 원본 파일 용량 합계 (바이트) */
        val bytes: Long,
        val favorites: Long,
        /** 휴지통에 있는 항목 (영구 삭제 전) */
        val trashed: Long,
        /** 키즈노트 전용 자산 — 타임라인 집계에는 포함되지 않는다 */
        val kidsnote: Long,
        val devices: Long,
        /** 이름이 붙은 인물 수 */
        val people: Long,
        val albums: Long,
        /** 얼굴이 검출된 사진 수 */
        val facesDetected: Long,
        val oldestTakenAt: String?,
        val newestTakenAt: String?,
        val storage: StorageDto,
        val jobs: List<JobStatDto>,
    )

    data class StorageDto(
        val root: String,
        val totalBytes: Long,
        val usableBytes: Long,
        /** 저장소가 실제로 쓰고 있는 용량 (원본 합계 — 썸네일은 별도) */
        val usedByOriginals: Long,
    )

    data class JobStatDto(val jobType: String, val status: String, val count: Long)

    /** 대시보드 상단 카드 묶음. */
    @GetMapping("/summary")
    fun summary(): SummaryDto = transaction {
        val cnt = Assets.id.count()
        val bytesSum = Assets.fileSize.sum()

        // 미디어 타입별 건수·용량을 한 번의 그룹 조회로
        val byType = Assets.select(Assets.mediaType, cnt, bytesSum)
            .where { Assets.deletedAt.isNull() and Assets.sourceTag.isNull() }
            .groupBy(Assets.mediaType)
            .associate { it[Assets.mediaType] to (it[cnt] to (it[bytesSum] ?: 0L)) }
        val photos = byType["PHOTO"]?.first ?: 0L
        val videos = byType["VIDEO"]?.first ?: 0L
        val bytes = byType.values.sumOf { it.second }

        val favorites = Assets.selectAll()
            .where { Assets.deletedAt.isNull() and Assets.sourceTag.isNull() and (Assets.favorite eq true) }
            .count()
        val trashed = Assets.selectAll()
            .where { Assets.deletedAt.isNotNull() and Assets.purgedAt.isNull() }
            .count()
        val kidsnote = Assets.selectAll()
            .where { Assets.deletedAt.isNull() and Assets.sourceTag.isNotNull() }
            .count()

        // 촬영 시각 범위 (taken_at은 ISO 텍스트라 사전순 = 시간순)
        val takenBounds = Assets.select(Assets.takenAt)
            .where { Assets.deletedAt.isNull() and Assets.sourceTag.isNull() and Assets.takenAt.isNotNull() }
        val oldest = takenBounds.copy().orderBy(Assets.takenAt to SortOrder.ASC).limit(1)
            .firstOrNull()?.get(Assets.takenAt)
        val newest = takenBounds.copy().orderBy(Assets.takenAt to SortOrder.DESC).limit(1)
            .firstOrNull()?.get(Assets.takenAt)

        val facesDetected = Assets.join(Faces, org.jetbrains.exposed.sql.JoinType.INNER,
            onColumn = Assets.id, otherColumn = Faces.assetId)
            .select(Assets.id.countDistinct())
            .where { Assets.deletedAt.isNull() and Assets.sourceTag.isNull() and (Faces.hidden eq false) }
            .firstOrNull()?.get(Assets.id.countDistinct()) ?: 0L

        val jobCnt = Jobs.id.count()
        val jobs = Jobs.select(Jobs.jobType, Jobs.status, jobCnt)
            .groupBy(Jobs.jobType, Jobs.status)
            .map { JobStatDto(it[Jobs.jobType], it[Jobs.status], it[jobCnt]) }
            .sortedWith(compareBy({ it.jobType }, { it.status }))

        SummaryDto(
            assets = photos + videos,
            photos = photos,
            videos = videos,
            bytes = bytes,
            favorites = favorites,
            trashed = trashed,
            kidsnote = kidsnote,
            devices = Devices.selectAll().count(),
            people = Persons.selectAll().where { Persons.name.isNotNull() }.count(),
            albums = Albums.selectAll().count(),
            facesDetected = facesDetected,
            oldestTakenAt = oldest,
            newestTakenAt = newest,
            storage = storageInfo(bytes),
            jobs = jobs,
        )
    }

    private fun storageInfo(usedByOriginals: Long): StorageDto {
        val store = runCatching { Files.getFileStore(props.storageRoot) }.getOrNull()
        return StorageDto(
            root = props.storageRoot.toAbsolutePath().toString(),
            totalBytes = store?.let { runCatching { it.totalSpace }.getOrDefault(0L) } ?: 0L,
            usableBytes = store?.let { runCatching { it.usableSpace }.getOrDefault(0L) } ?: 0L,
            usedByOriginals = usedByOriginals,
        )
    }

    data class SeriesPointDto(val key: String, val count: Long, val bytes: Long)

    /**
     * 촬영일 기준 시계열. unit=year|month|day, 오름차순.
     * taken_at이 'YYYY-MM-DDTHH:MM:SS' 텍스트라 앞 4/7/10자를 잘라 그룹핑한다.
     * limit은 끝(최근)에서부터 남길 구간 수 — 일자 그래프가 수천 점이 되는 것을 막는다.
     */
    @GetMapping("/timeseries")
    fun timeseries(
        @RequestParam(defaultValue = "month") unit: String,
        @RequestParam(required = false) limit: Int?,
    ): List<SeriesPointDto> {
        val width = when (unit.lowercase()) {
            "year" -> 4
            "month" -> 7
            "day" -> 10
            else -> throw IllegalArgumentException("unit must be year, month or day")
        }
        val rows = transaction {
            exec(
                """
                SELECT substr(taken_at, 1, $width) AS k, COUNT(*) AS c, COALESCE(SUM(file_size), 0) AS b
                FROM assets
                WHERE deleted_at IS NULL AND source IS NULL AND taken_at IS NOT NULL
                GROUP BY k
                ORDER BY k
                """.trimIndent()
            ) { rs ->
                buildList {
                    while (rs.next()) add(SeriesPointDto(rs.getString(1), rs.getLong(2), rs.getLong(3)))
                }
            }
        } ?: emptyList()
        val max = limit?.coerceIn(1, 5000)
        return if (max != null && rows.size > max) rows.takeLast(max) else rows
    }
}
