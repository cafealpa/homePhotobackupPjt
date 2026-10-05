package com.homephoto.server.api

import com.homephoto.server.db.*
import com.homephoto.server.service.AssetIngestService
import com.homephoto.server.worker.CaptionWorker
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

/** 사진 한 장의 완료 트랜잭션이 커밋되면 다음 조회부터 바로 노출한다. */
@RestController
@RequestMapping("/api/v1/admin/captions")
class CaptionController(private val worker: CaptionWorker) {
    data class Item(val assetId: Long, val filename: String, val caption: String?, val tags: List<String>,
                    val model: String?, val analyzedAt: String?, val status: String, val error: String?)
    data class Page(val total: Long, val items: List<Item>)
    private fun active() = (Assets.mediaType eq "PHOTO") and Assets.deletedAt.isNull() and Assets.purgedAt.isNull()
    private fun photos() = Assets.join(Jobs, JoinType.LEFT, Assets.id, Jobs.assetId) { Jobs.jobType eq "CAPTION" }
        .join(Captions, JoinType.LEFT, Assets.id, Captions.assetId)

    @GetMapping("/status") fun status(): Map<String, Any> {
        val counts = transaction {
            val query = photos()
            fun count(condition: Op<Boolean>) = query.selectAll().where { active() and condition }.count()
            mapOf("total" to count(Op.TRUE), "completed" to count(Captions.assetId.isNotNull()),
                "pending" to count(Jobs.status eq "PENDING"), "running" to count(Jobs.status eq "RUNNING"),
                "failed" to count(Jobs.status eq "FAILED"),
                "missing" to count(Captions.assetId.isNull()))
        }
        return mapOf("worker" to worker.status(), "counts" to counts)
    }

    @GetMapping fun list(@RequestParam(defaultValue = "completed") filter: String,
                         @RequestParam(defaultValue = "") q: String,
                         @RequestParam(defaultValue = "0") page: Int): Page = transaction {
        require(page in 0..1_000_000 && q.length <= 200) { "검색 범위를 확인하세요" }
        val condition = when (filter) {
            "completed" -> Captions.assetId.isNotNull()
            "pending" -> Jobs.status inList listOf("PENDING", "RUNNING")
            "failed" -> Jobs.status eq "FAILED"
            "missing" -> Captions.assetId.isNull()
            else -> throw IllegalArgumentException("지원하지 않는 필터")
        }
        // LIKE 와일드카드는 검색 연산으로만 사용되며 Exposed가 값을 바인딩한다.
        val search = if (q.isBlank()) Op.TRUE else (Captions.caption like "%${q.trim()}%") or
            (Captions.tags like "%${q.trim()}%") or (Assets.originalFilename like "%${q.trim()}%")
        val query = photos().selectAll().where { active() and condition and search }
        val total = query.count()
        Page(total, query.orderBy(Captions.createdAt to SortOrder.DESC, Assets.id to SortOrder.DESC)
            .limit(40).offset(page.toLong() * 40).map { row ->
                Item(row[Assets.id], row[Assets.originalFilename], row.getOrNull(Captions.caption),
                    row.getOrNull(Captions.tags)?.split(',')?.filter(String::isNotBlank).orEmpty(),
                    row.getOrNull(Captions.model), row.getOrNull(Captions.createdAt),
                    row.getOrNull(Jobs.status) ?: "NONE", row.getOrNull(Jobs.lastError))
            })
    }

    data class EnqueueRequest(val mode: String = "missing")
    /** 완료 결과는 보존한다. 실행 중인 작업은 건드리지 않고 실패만 명시적으로 재등록한다. */
    @PostMapping("/enqueue") fun enqueue(@RequestBody request: EnqueueRequest): Map<String, Int> = transaction {
        require(request.mode in setOf("missing", "failed")) { "지원하지 않는 등록 방식" }
        val now = LocalDateTime.now().format(AssetIngestService.ISO)
        val eligible = "a.media_type = 'PHOTO' AND a.deleted_at IS NULL AND a.purged_at IS NULL"
        if (request.mode == "failed") exec("""
            UPDATE jobs SET status='PENDING', attempts=0, last_error=NULL, updated_at='$now'
            WHERE job_type='CAPTION' AND status='FAILED' AND asset_id IN (SELECT a.id FROM assets a WHERE $eligible)
        """.trimIndent()) else exec("""
            INSERT INTO jobs(asset_id,job_type,status,priority,attempts,updated_at)
            SELECT a.id,'CAPTION','PENDING',0,0,'$now' FROM assets a
            WHERE $eligible AND NOT EXISTS (SELECT 1 FROM captions c WHERE c.asset_id=a.id)
            ON CONFLICT(asset_id,job_type) DO UPDATE SET status='PENDING', attempts=0,last_error=NULL,updated_at='$now'
            WHERE jobs.status IN ('DONE','FAILED')
        """.trimIndent())
        val count = exec("SELECT changes()") { rs -> rs.next(); rs.getInt(1) } ?: 0
        mapOf("queued" to count)
    }
}
