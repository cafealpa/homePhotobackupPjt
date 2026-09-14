package com.homephoto.server.search

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Faces
import com.homephoto.server.db.Persons
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

// 기존 SQLite에는 모델 리비전이 없다. 현재 고정 buffalo_l 워커로 생성한 512차원 벡터 전용이다.
const val FACE_VECTOR_MODEL = "legacy-insightface-buffalo_l-512-v1"
fun faceFingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

data class FaceReference(val faceId: Long, val assetId: Long, val x: Double, val y: Double,
    val width: Double, val height: Double, val personId: Long?, val name: String?, val clusterId: Int?)
data class FaceMatch(val face: FaceReference, val similarity: Double)
data class PersonCandidate(val personId: Long, val name: String?, val similarity: Double, val matchedFaceIds: List<Long>)
data class FaceSuggestions(val source: FaceReference, val matches: List<FaceMatch>,
    val people: List<PersonCandidate>, val indexedFaces: Long,
    val notice: String = "유사도 기반 후보이며 동일 인물 확정이나 확률이 아닙니다. 자동으로 이름을 연결하지 않습니다.")

@Service
class FaceVectorSearch(private val props: PhotoSearchProperties, private val mapper: ObjectMapper) {
    val enabled get() = props.enabled
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    init { if (enabled) props.validate() }

    private fun visible() = Faces.innerJoin(Assets).leftJoin(Persons).selectAll().where {
        (Faces.hidden eq false) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull() and
            Assets.sourceTag.isNull() and (Assets.mediaType eq "PHOTO")
    }
    private fun reference(row: ResultRow) = FaceReference(row[Faces.id], row[Faces.assetId], row[Faces.bboxX],
        row[Faces.bboxY], row[Faces.bboxW], row[Faces.bboxH], row[Faces.personId], row.getOrNull(Persons.name), row[Faces.clusterId])

    fun faces(assetId: Long): List<FaceReference> = transaction {
        visible().andWhere { Faces.assetId eq assetId }.orderBy(Faces.id to SortOrder.ASC).map(::reference)
    }

    fun similar(faceId: Long, limit: Int, minSimilarity: Double): FaceSuggestions {
        require(faceId > 0 && limit in 1..50) { "faceId는 양수이고 limit은 1~50이어야 합니다." }
        require(minSimilarity.isFinite() && minSimilarity in 0.0..1.0) { "minSimilarity는 0~1이어야 합니다." }
        if (!enabled) throw PhotoSearchUnavailable()
        val source = transaction { visible().andWhere { Faces.id eq faceId }.firstOrNull() }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "조회 가능한 얼굴이 없습니다.")
        val embedding = source[Faces.embedding].bytes
        val fingerprint = faceFingerprint(embedding)
        val response = try {
            http.send(HttpRequest.newBuilder(URI("${props.baseUrl}/faces/search"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer ${props.token}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf(
                    "model" to FACE_VECTOR_MODEL, "embedding" to Base64.getEncoder().encodeToString(embedding),
                    "source_asset_id" to source[Faces.assetId], "limit" to 200,
                    "min_similarity" to minSimilarity)))).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw PhotoSearchUnavailable()
        } catch (e: java.io.IOException) { throw PhotoSearchUnavailable() }
        if (response.statusCode() != 200) throw PhotoSearchUnavailable()
        val body = mapper.readTree(response.body())
        if (body.path("model").asText() != FACE_VECTOR_MODEL) throw PhotoSearchUnavailable()
        val hits = body.path("matches").take(200).distinctBy { it.path("face_id").asLong() }
        val ids = hits.map { it.path("face_id").asLong() }.filter { it > 0 }
        return transaction {
            val current = visible().andWhere { Faces.id eq faceId }.firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "조회 가능한 얼굴이 없습니다.")
            if (faceFingerprint(current[Faces.embedding].bytes) != fingerprint) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "얼굴 정보가 변경되었습니다. 다시 조회해 주세요.")
            }
            val rows = visible().andWhere { (Faces.id inList ids) and (Faces.assetId neq current[Faces.assetId]) }
                .associateBy { it[Faces.id] }
            val matches = hits.mapNotNull { hit ->
                val row = rows[hit.path("face_id").asLong()] ?: return@mapNotNull null
                // 재분석으로 얼굴 ID가 재사용되거나 벡터가 변경된 오래된 검색 결과는 제외한다.
                if (hit.path("fingerprint").asText() != faceFingerprint(row[Faces.embedding].bytes)) return@mapNotNull null
                val score = hit.path("similarity").asDouble(Double.NaN)
                if (!score.isFinite() || score !in minSimilarity..1.0) return@mapNotNull null
                FaceMatch(reference(row), score)
            }.sortedByDescending { it.similarity }.take(limit)
            val people = matches.filter { it.face.personId != null }.groupBy { it.face.personId!! }.map { (id, group) ->
                PersonCandidate(id, group.first().face.name, group.maxOf { it.similarity }, group.map { it.face.faceId })
            }.sortedByDescending { it.similarity }
            FaceSuggestions(reference(current), matches, people, body.path("indexed_faces").asLong())
        }
    }
}
