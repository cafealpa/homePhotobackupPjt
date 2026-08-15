package com.homephoto.server.api

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.service.AssetIngestService
import com.homephoto.server.service.TrashService
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

data class TrashItemDto(
    val asset: AssetDto,
    val deletedAt: String,
    /** 이 시각 이후 자동 영구 삭제 */
    val purgeAt: String,
)

/** 휴지통: 목록·복원·선택 영구 삭제·비우기. */
@RestController
@RequestMapping("/api/v1/trash")
class TrashController(
    private val trashService: TrashService,
    private val props: AppProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun list(): List<TrashItemDto> = transaction {
        Assets.selectAll()
            .where { Assets.deletedAt.isNotNull() and Assets.purgedAt.isNull() }
            .orderBy(Assets.deletedAt to SortOrder.DESC)
            .map { row ->
                val deletedAt = row[Assets.deletedAt]!!
                val purgeAt = LocalDateTime.parse(deletedAt, AssetIngestService.ISO)
                    .plusDays(props.trashRetentionDays)
                    .format(AssetIngestService.ISO)
                TrashItemDto(asset = row.toAssetDto(), deletedAt = deletedAt, purgeAt = purgeAt)
            }
    }

    /** 휴지통에서 복원 — 파일이 남아 있으므로 묘비만 해제하면 끝. */
    @PostMapping("/{id}/restore")
    fun restore(@PathVariable id: Long): AssetDto {
        val restored = transaction {
            val updated = Assets.update({
                (Assets.id eq id) and Assets.deletedAt.isNotNull() and Assets.purgedAt.isNull()
            }) { it[deletedAt] = null }
            if (updated == 0) null
            else Assets.selectAll().where { Assets.id eq id }.first().toAssetDto()
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "asset $id is not in trash")
        log.info("휴지통 복원: #{} {}", id, restored.originalFilename)
        return restored
    }

    /** 휴지통에서 선택 영구 삭제. */
    @DeleteMapping("/{id}")
    fun purgeOne(@PathVariable id: Long): Map<String, Any> {
        val row = transaction {
            Assets.selectAll()
                .where { (Assets.id eq id) and Assets.deletedAt.isNotNull() and Assets.purgedAt.isNull() }
                .firstOrNull()
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "asset $id is not in trash")
        trashService.purge(row)
        return mapOf("purged" to true, "id" to id)
    }

    /** 휴지통 비우기 — 전체 영구 삭제. */
    @PostMapping("/empty")
    fun empty(): Map<String, Any> {
        val rows = trashService.trashRows()
        rows.forEach { trashService.purge(it) }
        log.info("휴지통 비우기: {}건 영구 삭제", rows.size)
        return mapOf("purged" to rows.size)
    }
}
