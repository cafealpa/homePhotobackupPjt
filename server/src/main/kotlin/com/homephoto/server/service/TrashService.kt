package com.homephoto.server.service

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Captions
import com.homephoto.server.db.Faces
import com.homephoto.server.db.Jobs
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import kotlin.io.path.deleteIfExists

/** 휴지통 영구 삭제 처리. 파일·faces·jobs를 제거하고 행은 재백업 스킵용 묘비로 남긴다. */
@Service
class TrashService(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 휴지통의 한 항목을 영구 삭제한다. */
    fun purge(row: ResultRow) {
        val id = row[Assets.id]
        val hash = row[Assets.hash]
        props.storageRoot.resolve(row[Assets.originalPath]).deleteIfExists()
        ThumbnailService.SIZES.forEach { size ->
            props.thumbsDir.resolve("${hash}_$size.jpg").deleteIfExists()
        }
        transaction {
            Assets.update({ Assets.id eq id }) {
                it[purgedAt] = LocalDateTime.now().format(AssetIngestService.ISO)
            }
            Faces.deleteWhere { Faces.assetId eq id }
            Jobs.deleteWhere { Jobs.assetId eq id }
            Captions.deleteWhere { Captions.assetId eq id }
        }
        log.info("영구 삭제: #{} {} — 파일 제거, 해시는 재백업 스킵용 묘비로 유지", id, row[Assets.originalFilename])
    }

    /** 휴지통에 있는(아직 영구 삭제 안 된) 행들 */
    fun trashRows(): List<ResultRow> = transaction {
        Assets.selectAll()
            .where { Assets.deletedAt.isNotNull() and Assets.purgedAt.isNull() }
            .toList()
    }

    /** 보관 기간이 지난 휴지통 항목 자동 영구 삭제 — 1시간 후 시작, 6시간마다 */
    @Scheduled(initialDelay = 3_600_000, fixedDelay = 21_600_000)
    fun purgeExpired() {
        val cutoff = LocalDateTime.now().minusDays(props.trashRetentionDays).format(AssetIngestService.ISO)
        val expired = transaction {
            Assets.selectAll()
                .where {
                    Assets.deletedAt.isNotNull() and Assets.purgedAt.isNull() and (Assets.deletedAt less cutoff)
                }
                .toList()
        }
        if (expired.isEmpty()) return
        expired.forEach { purge(it) }
        log.info("휴지통 자동 비우기: {}건 영구 삭제 (보관 {}일 초과)", expired.size, props.trashRetentionDays)
    }
}
