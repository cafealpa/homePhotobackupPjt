package com.homephoto.server.api

import com.homephoto.server.db.Faces
import com.homephoto.server.db.Jobs
import com.homephoto.server.service.AssetIngestService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.util.Base64

data class ClaimRequest(val jobType: String)
data class ClaimedJobDto(val jobId: Long, val assetId: Long)
data class FaceUpload(val x: Double, val y: Double, val w: Double, val h: Double, val embedding: String)
data class CompleteRequest(val faces: List<FaceUpload> = emptyList())
data class FailRequest(val error: String?)
data class FaceEmbeddingDto(val id: Long, val embedding: String)
data class ClusterAssignRequest(val assignments: Map<Long, Int>)

/** ML 워커(Python)용 API. 워커는 DB에 직접 접근하지 않고 이 API로만 통신한다. */
@RestController
@RequestMapping("/api/v1/internal")
class InternalController {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /** 대기 중인 작업 하나를 클레임한다. 없으면 204. */
    @PostMapping("/jobs/claim")
    fun claim(@RequestBody request: ClaimRequest): ResponseEntity<ClaimedJobDto> {
        require(request.jobType in CLAIMABLE_TYPES) { "unknown job type: ${request.jobType}" }
        val claimed = transaction {
            // 쓰기 문장을 트랜잭션 첫 문장으로 (SQLITE_BUSY 규율) + RETURNING으로 원자적 클레임
            exec(
                """
                UPDATE jobs SET status = 'RUNNING', updated_at = '${now()}'
                WHERE id = (
                  SELECT id FROM jobs
                  WHERE job_type = '${request.jobType}' AND status = 'PENDING'
                  ORDER BY priority DESC
                  LIMIT 1
                )
                RETURNING id, asset_id
                """.trimIndent(),
                explicitStatementType = StatementType.SELECT,
            ) { rs -> if (rs.next()) ClaimedJobDto(rs.getLong(1), rs.getLong(2)) else null }
        }
        return if (claimed != null) ResponseEntity.ok(claimed)
        else ResponseEntity.noContent().build()
    }

    /** FACE 작업 결과 제출: 감지된 얼굴들(정규화 bbox + float32[512] base64 임베딩). */
    @PostMapping("/jobs/{id}/complete")
    fun complete(@PathVariable id: Long, @RequestBody request: CompleteRequest): Map<String, Any> {
        val decoded = request.faces.map { face ->
            val bytes = Base64.getDecoder().decode(face.embedding)
            require(bytes.size == 512 * 4) { "embedding must be 512 float32 values (got ${bytes.size} bytes)" }
            face to bytes
        }
        transaction {
            val updated = Jobs.update({ (Jobs.id eq id) and (Jobs.status eq "RUNNING") }) {
                it[status] = "DONE"
                it[lastError] = null
                it[updatedAt] = now()
            }
            if (updated == 0) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "job $id is not RUNNING")
            }
            val jobAssetId = Jobs.selectAll().where { Jobs.id eq id }.first()[Jobs.assetId]

            // 재처리 대비: 기존 얼굴을 지우고 새로 넣는다 (멱등)
            Faces.deleteWhere { assetId eq jobAssetId }
            decoded.forEach { (face, bytes) ->
                Faces.insert {
                    it[assetId] = jobAssetId
                    it[bboxX] = face.x
                    it[bboxY] = face.y
                    it[bboxW] = face.w
                    it[bboxH] = face.h
                    it[embedding] = ExposedBlob(bytes)
                }
            }
        }
        log.info("FACE 작업 {} 완료: 얼굴 {}개 저장", id, decoded.size)
        return mapOf("saved" to decoded.size)
    }

    /** 작업 실패 보고. 3회 누적 시 FAILED, 아니면 PENDING으로 되돌려 재시도. */
    @PostMapping("/jobs/{id}/fail")
    fun fail(@PathVariable id: Long, @RequestBody request: FailRequest): Map<String, String> {
        log.warn("워커가 작업 {} 실패 보고: {}", id, request.error ?: "원인 미상")
        transaction {
            val error = (request.error ?: "unknown").take(500).replace("'", "''")
            exec(
                """
                UPDATE jobs SET
                  attempts = attempts + 1,
                  status = CASE WHEN attempts + 1 >= 3 THEN 'FAILED' ELSE 'PENDING' END,
                  last_error = '$error',
                  updated_at = '${now()}'
                WHERE id = $id AND status = 'RUNNING'
                """.trimIndent()
            )
        }
        return mapOf("status" to "ok")
    }

    /** 클러스터링용 전체 얼굴 임베딩. */
    @GetMapping("/faces")
    fun faces(): List<FaceEmbeddingDto> = transaction {
        Faces.selectAll().map {
            FaceEmbeddingDto(
                id = it[Faces.id],
                embedding = Base64.getEncoder().encodeToString(it[Faces.embedding].bytes),
            )
        }
    }

    /** 클러스터링 결과 반영. 맵에 없는 얼굴은 노이즈로 간주해 cluster_id를 비운다. */
    @PostMapping("/faces/clusters")
    fun assignClusters(@RequestBody request: ClusterAssignRequest): Map<String, Any> {
        transaction {
            Faces.update { it[clusterId] = null }
            request.assignments.forEach { (faceId, cluster) ->
                Faces.update({ Faces.id eq faceId }) { it[clusterId] = cluster }
            }
        }
        val clusterCount = request.assignments.values.distinct().size
        log.info("클러스터링 갱신: 얼굴 {}개 → {}개 클러스터", request.assignments.size, clusterCount)
        return mapOf("assigned" to request.assignments.size)
    }

    private fun now(): String = LocalDateTime.now().format(AssetIngestService.ISO)

    companion object {
        // CAPTION은 서버 내장 CaptionWorker가 DB에서 직접 클레임한다 (GB10 VLM을 HTTP로 호출).
        // 외부 워커가 CAPTION을 잡으면 complete()가 얼굴 결과로 처리해 버리므로 막는다.
        private val CLAIMABLE_TYPES = setOf("FACE")
    }
}
