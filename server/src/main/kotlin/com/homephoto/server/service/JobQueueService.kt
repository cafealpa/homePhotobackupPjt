package com.homephoto.server.service

import com.homephoto.server.db.Assets
import com.homephoto.server.db.Jobs
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/** SQLite 쓰기 우선 트랜잭션과 RETURNING으로 선점한 작업을 정확하게 반환한다. */
@Service
class JobQueueService(private val activity: ServerActivity = ServerActivity()) {
    private val claimed = ThreadLocal.withInitial { mutableMapOf<String, MutableSet<Long>>() }
    private val recoveryLock = Any()
    private val abandoned = mutableMapOf<String, MutableSet<Long>>()
    data class Claimed(
        val jobId: Long, val assetId: Long, val hash: String,
        val relPath: String, val mediaType: String, val attempts: Int,
    )

    fun claim(type: String): Claimed? = synchronized(recoveryLock) {
        require(type in TYPES)
        if (activity.blocked(type)) return@synchronized null
        recoverAbandoned(type)
        transaction {
            val id = exec(
                """
                UPDATE jobs SET status = 'RUNNING', updated_at = '${now()}'
                WHERE id = (
                    SELECT j.id FROM jobs j JOIN assets a ON a.id = j.asset_id
                    WHERE j.job_type = '$type' AND j.status = 'PENDING'
                      AND a.deleted_at IS NULL AND a.purged_at IS NULL
                    ORDER BY j.priority DESC, j.id LIMIT 1
                ) RETURNING id
                """.trimIndent(), explicitStatementType = StatementType.SELECT,
            ) { rs -> if (rs.next()) rs.getLong(1) else null } ?: return@transaction null
            (Jobs innerJoin Assets).selectAll().where { Jobs.id eq id }.first().let {
                Claimed(it[Jobs.id], it[Assets.id], it[Assets.hash], it[Assets.originalPath], it[Assets.mediaType], it[Jobs.attempts]).also { job ->
                    ProcessMonitor.stage("항목 처리", "사진 #${job.assetId} · ${it[Assets.originalFilename]} · 시도 ${job.attempts + 1}")
                    activity.event(type,"START","사진 #${job.assetId} 처리 시작 · 시도 ${job.attempts + 1}")
                }
            }
        }.also { job -> if (job != null) claimed.get().getOrPut(type) { mutableSetOf() }.add(job.jobId) }
    }

    /** 결과 저장도 같은 트랜잭션에서 처리하여 삭제된 작업의 결과가 되살아나지 않게 한다. */
    fun complete(id: Long, type: String, save: (Long) -> Unit = {}): Boolean = transaction {
        val updated = Jobs.update({ (Jobs.id eq id) and (Jobs.jobType eq type) and (Jobs.status eq "RUNNING") }) {
            it[status] = "DONE"
            with(SqlExpressionBuilder) { it[attempts] = attempts + 1 }
            it[lastError] = null
            it[updatedAt] = now()
        }
        if (updated == 0) return@transaction false
        save(Jobs.selectAll().where { Jobs.id eq id }.first()[Jobs.assetId])
        true
    }.also { completed ->
        forget(id, type)
        if(completed) activity.success(type,"작업 #$id 처리 완료")
    }

    fun fail(id: Long, type: String, error: String?) {
        require(type in TYPES)
        if (ProcessMonitor.cancelled()) { release(id,type); return }
        activity.issue(type,"작업 #$id 실패 · ${ProcessMonitor.clean(error ?: "원인 미상")}")
        transaction { exec(
            """
            UPDATE jobs SET attempts = attempts + 1,
              status = CASE WHEN attempts + 1 >= $MAX_ATTEMPTS THEN 'FAILED' ELSE 'PENDING' END,
              last_error = ?, updated_at = '${now()}'
            WHERE id = $id AND job_type = '$type' AND status = 'RUNNING'
            """.trimIndent(), args = listOf(TextColumnType() to (error ?: "unknown").take(500)),
        ) }
        forget(id, type)
    }

    fun release(id: Long, type: String) = transaction {
        Jobs.update({ (Jobs.id eq id) and (Jobs.jobType eq type) and (Jobs.status eq "RUNNING") }) {
            it[status] = "PENDING"
            it[updatedAt] = now()
        }
    }.also { forget(id, type) }

    fun retryAll(type: String): Int {
        require(type in TYPES)
        val recovered = recoverAbandoned(type)
        return recovered + transaction { Jobs.update({ (Jobs.jobType eq type) and (Jobs.status eq "FAILED") and
            (Jobs.assetId inSubQuery Assets.select(Assets.id).where { Assets.deletedAt.isNull() and Assets.purgedAt.isNull() }) }) {
            it[status]="PENDING"; it[attempts]=0; it[lastError]=null; it[updatedAt]=now()
        } }
    }

    private fun forget(id: Long, type: String) { claimed.get()[type]?.remove(id) }

    /** 실행을 끝낸 스레드의 미확정 작업만 회수한다. 다른 워커의 RUNNING은 건드리지 않는다. */
    fun finishWorker(type: String) {
        val ids = claimed.get().remove(type).orEmpty()
        if (claimed.get().isEmpty()) claimed.remove()
        if (ids.isEmpty()) return
        synchronized(recoveryLock) { abandoned.getOrPut(type) { mutableSetOf() }.addAll(ids) }
        try { recoverAbandoned(type) }
        catch (e: Exception) {
            activity.issue(type, "작업 상태 복구 대기 · DB 연결이 회복되면 다시 시도합니다 (${e.javaClass.simpleName})")
        }
    }

    fun recoverAbandoned(type: String): Int = synchronized(recoveryLock) {
        require(type in TYPES)
        val ids = abandoned[type]?.toList().orEmpty()
        if (ids.isEmpty()) return@synchronized 0
        val count = transaction {
            ids.chunked(500).sumOf { batch ->
                Jobs.update({ (Jobs.id inList batch) and (Jobs.jobType eq type) and (Jobs.status eq "RUNNING") }) {
                    it[status] = "PENDING"; it[updatedAt] = now()
                }
            }
        }
        abandoned.remove(type)
        if (count > 0) activity.event(type,"RECOVER","종료된 워커의 미확정 작업 ${count}개를 대기열로 복구")
        count
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private val TYPES = setOf("THUMBNAIL", "CAPTION", "FACE", "DOCUMENT")
        private fun now() = LocalDateTime.now().format(AssetIngestService.ISO)
    }
}
