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
class JobQueueService {
    data class Claimed(
        val jobId: Long, val assetId: Long, val hash: String,
        val relPath: String, val mediaType: String, val attempts: Int,
    )

    fun claim(type: String): Claimed? {
        require(type in TYPES)
        return transaction {
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
                Claimed(it[Jobs.id], it[Assets.id], it[Assets.hash], it[Assets.originalPath], it[Assets.mediaType], it[Jobs.attempts])
            }
        }
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
    }

    fun fail(id: Long, type: String, error: String?) = transaction {
        require(type in TYPES)
        exec(
            """
            UPDATE jobs SET attempts = attempts + 1,
              status = CASE WHEN attempts + 1 >= $MAX_ATTEMPTS THEN 'FAILED' ELSE 'PENDING' END,
              last_error = ?, updated_at = '${now()}'
            WHERE id = $id AND job_type = '$type' AND status = 'RUNNING'
            """.trimIndent(), args = listOf(TextColumnType() to (error ?: "unknown").take(500)),
        )
    }

    fun release(id: Long, type: String) = transaction {
        Jobs.update({ (Jobs.id eq id) and (Jobs.jobType eq type) and (Jobs.status eq "RUNNING") }) {
            it[status] = "PENDING"
            it[updatedAt] = now()
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private val TYPES = setOf("THUMBNAIL", "CAPTION", "FACE")
        private fun now() = LocalDateTime.now().format(AssetIngestService.ISO)
    }
}
