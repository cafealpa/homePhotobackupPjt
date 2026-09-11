package com.homephoto.server.service

import com.homephoto.server.api.AssetDto
import com.homephoto.server.api.toAssetDto
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
import java.nio.file.Files
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.io.path.deleteIfExists

/** 휴지통 영구 삭제 처리. 파일·faces·jobs를 제거하고 행은 재백업 스킵용 묘비로 남긴다. */
@Service
class TrashService(
    private val props: AppProperties,
    private val thumbnailService: ThumbnailService,
    private val locks: AssetLocks,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 휴지통의 한 항목을 영구 삭제한다. */
    fun purge(id: Long): Boolean = withAsset(id) { row ->
        // 목록을 조회한 뒤 복원됐을 수 있으므로 락 안에서 최신 상태를 확인한다.
        if (row[Assets.deletedAt] == null || row[Assets.purgedAt] != null) return@withAsset false
        val hash = row[Assets.hash]
        props.storageRoot.resolve(row[Assets.originalPath]).deleteIfExists()
        ThumbnailService.SIZES.forEach { size ->
            thumbnailService.thumbPath(hash, size).deleteIfExists()
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
        true
    } ?: false

    fun trash(id: Long): Boolean = withAsset(id) { row ->
        if (row[Assets.deletedAt] != null) return@withAsset false
        transaction {
            Assets.update({ Assets.id eq id }) {
                it[deletedAt] = LocalDateTime.now().format(AssetIngestService.ISO)
            }
        }
        log.info("휴지통 이동: #{} {} ({}일 후 자동 영구 삭제)", id, row[Assets.originalFilename], props.trashRetentionDays)
        true
    } ?: false

    fun restore(id: Long): AssetDto? = withAsset(id) { row ->
        if (row[Assets.deletedAt] == null || row[Assets.purgedAt] != null) return@withAsset null
        // 파일 삭제 중 실패한 항목은 원본 재업로드로 복구해야 한다.
        if (!Files.exists(props.storageRoot.resolve(row[Assets.originalPath]))) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT, "original missing; re-upload required",
            )
        }
        transaction {
            Assets.update({ Assets.id eq id }) { it[deletedAt] = null }
            Assets.selectAll().where { Assets.id eq id }.first().toAssetDto()
        }
    }

    private fun <T> withAsset(id: Long, action: (ResultRow) -> T): T? {
        val hash = transaction {
            Assets.select(Assets.hash).where { Assets.id eq id }.firstOrNull()?.get(Assets.hash)
        } ?: return null
        return locks.withHash(hash) {
            val current = transaction { Assets.selectAll().where { Assets.id eq id }.firstOrNull() }
            current?.let(action)
        }
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
        val purged = expired.count { row ->
            runCatching { purge(row[Assets.id]) }
                .onFailure { log.error("자동 영구 삭제 실패: #{} — 다음 주기에 재시도", row[Assets.id], it) }
                .getOrDefault(false)
        }
        log.info("휴지통 자동 비우기: {}건 영구 삭제 (보관 {}일 초과)", purged, props.trashRetentionDays)
    }
}
