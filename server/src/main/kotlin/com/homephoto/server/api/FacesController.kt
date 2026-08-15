package com.homephoto.server.api

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Faces
import com.homephoto.server.db.Persons
import com.homephoto.server.service.ThumbnailService
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

data class ClusterSummaryDto(
    val clusterId: Int,
    val faceCount: Long,
    val coverFaceId: Long,
    val name: String?,
)

data class NameClusterRequest(val name: String)

data class MergeClusterRequest(val into: Int)

/** toCluster가 null이면 해당 인물에서 제외(얼굴 숨김). */
data class ReassignFaceRequest(val assetId: Long, val fromCluster: Int, val toCluster: Int?)

/** 인물(얼굴 클러스터) 조회·이름 붙이기 API. */
@RestController
@RequestMapping("/api/v1")
class FacesController(private val props: AppProperties) {

    @GetMapping("/faces/clusters")
    fun clusters(): List<ClusterSummaryDto> = transaction {
        // 클러스터에 속한 얼굴 중 사용자가 이름 붙인 person이 있으면 그 이름을 대표로
        val names: Map<Int, String> = Faces.innerJoin(Persons)
            .select(Faces.clusterId, Persons.name)
            .where { Faces.clusterId.isNotNull() and (Faces.hidden eq false) }
            .mapNotNull { row ->
                val cluster = row[Faces.clusterId] ?: return@mapNotNull null
                val name = row[Persons.name] ?: return@mapNotNull null
                cluster to name
            }
            .toMap()

        val cnt = Faces.id.count()
        val cover = Faces.id.min()
        Faces.select(Faces.clusterId, cnt, cover)
            .where { Faces.clusterId.isNotNull() and (Faces.hidden eq false) }
            .groupBy(Faces.clusterId)
            .orderBy(cnt to SortOrder.DESC)
            .map {
                val clusterId = it[Faces.clusterId]!!
                ClusterSummaryDto(
                    clusterId = clusterId,
                    faceCount = it[cnt],
                    coverFaceId = it[cover]!!,
                    name = names[clusterId],
                )
            }
    }

    /** 클러스터에 이름 붙이기. 이미 이름이 있으면 갱신. */
    @PostMapping("/faces/clusters/{clusterId}/name")
    fun nameCluster(
        @PathVariable clusterId: Int,
        @RequestBody request: NameClusterRequest,
    ): Map<String, Any?> {
        val name = request.name.trim()
        require(name.isNotEmpty()) { "name must not be empty" }
        transaction {
            val existingPersonId = Faces.selectAll()
                .where { (Faces.clusterId eq clusterId) and (Faces.personId.isNotNull()) }
                .firstOrNull()?.get(Faces.personId)
            if (existingPersonId != null) {
                Persons.update({ Persons.id eq existingPersonId }) { it[Persons.name] = name }
            } else {
                val personId = Persons.insert { it[Persons.name] = name }[Persons.id]
                Faces.update({ Faces.clusterId eq clusterId }) { it[Faces.personId] = personId }
            }
        }
        return mapOf("clusterId" to clusterId, "name" to name)
    }

    /**
     * 클러스터 합치기: source의 얼굴을 전부 into 클러스터로 이동.
     * 이름(person)은 대상 클러스터 것을 우선, 없으면 원본 것을 승계해 전체에 적용한다.
     * 주의: 재클러스터링이 돌면 cluster_id가 다시 계산되므로, 이름이 붙은(person 연결)
     * 합치기만 재클러스터링 후에도 의미가 유지된다.
     */
    @PostMapping("/faces/clusters/{clusterId}/merge")
    fun mergeCluster(
        @PathVariable clusterId: Int,
        @RequestBody request: MergeClusterRequest,
    ): Map<String, Any?> {
        if (clusterId == request.into) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot merge a cluster into itself")
        }
        transaction {
            val sourceCount = Faces.selectAll().where { Faces.clusterId eq clusterId }.count()
            val targetCount = Faces.selectAll().where { Faces.clusterId eq request.into }.count()
            if (sourceCount == 0L) throw ResponseStatusException(HttpStatus.NOT_FOUND, "cluster $clusterId not found")
            if (targetCount == 0L) throw ResponseStatusException(HttpStatus.NOT_FOUND, "cluster ${request.into} not found")

            val targetPerson = Faces.selectAll()
                .where { (Faces.clusterId eq request.into) and Faces.personId.isNotNull() }
                .firstOrNull()?.get(Faces.personId)
            val sourcePerson = Faces.selectAll()
                .where { (Faces.clusterId eq clusterId) and Faces.personId.isNotNull() }
                .firstOrNull()?.get(Faces.personId)

            Faces.update({ Faces.clusterId eq clusterId }) { it[Faces.clusterId] = request.into }
            val person = targetPerson ?: sourcePerson
            if (person != null) {
                Faces.update({ Faces.clusterId eq request.into }) { it[Faces.personId] = person }
            }
        }
        return mapOf("merged" to clusterId, "into" to request.into)
    }

    /**
     * 사진 단위 인물 재배정: 한 사진의 얼굴(fromCluster 소속)을 다른 클러스터로 옮기거나
     * (toCluster=null이면) 해당 인물에서 제외한다. 잘못 클러스터링된 사진 정정용.
     * 옮길 때 person(이름) 연결은 대상 클러스터 것으로 교체된다 (없으면 해제).
     */
    @PostMapping("/faces/reassign")
    fun reassignFace(@RequestBody request: ReassignFaceRequest): Map<String, Any?> {
        val moved = transaction {
            val targetFaces = Faces.selectAll()
                .where { (Faces.assetId eq request.assetId) and (Faces.clusterId eq request.fromCluster) }
                .count()
            if (targetFaces == 0L) {
                throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "no faces of asset ${request.assetId} in cluster ${request.fromCluster}",
                )
            }

            val to = request.toCluster
            if (to == null) {
                // 제외: 이 사진의 해당 인물 얼굴을 숨긴다
                Faces.update({ (Faces.assetId eq request.assetId) and (Faces.clusterId eq request.fromCluster) }) {
                    it[Faces.hidden] = true
                }
            } else {
                val targetExists = Faces.selectAll().where { Faces.clusterId eq to }.count() > 0L
                if (!targetExists) throw ResponseStatusException(HttpStatus.NOT_FOUND, "cluster $to not found")
                val targetPerson = Faces.selectAll()
                    .where { (Faces.clusterId eq to) and Faces.personId.isNotNull() }
                    .firstOrNull()?.get(Faces.personId)
                Faces.update({ (Faces.assetId eq request.assetId) and (Faces.clusterId eq request.fromCluster) }) {
                    it[Faces.clusterId] = to
                    it[Faces.personId] = targetPerson
                    it[Faces.hidden] = false
                }
            }
        }
        return mapOf(
            "assetId" to request.assetId,
            "fromCluster" to request.fromCluster,
            "toCluster" to request.toCluster,
            "movedFaces" to moved,
        )
    }

    /** 인물 삭제(숨김): 얼굴 자체는 남기되 인물 뷰에서 제외한다. 사진은 삭제되지 않는다. */
    @DeleteMapping("/faces/clusters/{clusterId}")
    fun deleteCluster(@PathVariable clusterId: Int): Map<String, Any?> {
        val hiddenCount = transaction {
            Faces.update({ Faces.clusterId eq clusterId }) { it[Faces.hidden] = true }
        }
        if (hiddenCount == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "cluster $clusterId not found")
        }
        return mapOf("clusterId" to clusterId, "hiddenFaces" to hiddenCount)
    }

    /** 얼굴 부분만 잘라낸 썸네일 (인물 그리드 표지용). */
    @GetMapping("/faces/{id}/thumb")
    fun faceThumb(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val face = transaction {
            Faces.innerJoin(Assets).selectAll().where { Faces.id eq id }.firstOrNull()
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "face $id not found")

        val hash = face[Assets.hash]
        val source = ThumbnailService.SIZES.reversed()
            .map { props.thumbsDir.resolve("${hash}_$it.jpg") }
            .firstOrNull { Files.exists(it) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "thumbnail not ready")

        val image = ImageIO.read(source.toFile())
            ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "cannot read thumbnail")

        // bbox는 0~1 정규화 좌표라 축소된 썸네일에도 그대로 적용 가능. 주변 35% 여유를 준다
        val margin = 0.35
        val bw = face[Faces.bboxW]
        val bh = face[Faces.bboxH]
        val x = ((face[Faces.bboxX] - bw * margin) * image.width).toInt().coerceAtLeast(0)
        val y = ((face[Faces.bboxY] - bh * margin) * image.height).toInt().coerceAtLeast(0)
        val w = ((bw * (1 + margin * 2)) * image.width).toInt().coerceAtLeast(1)
            .coerceAtMost(image.width - x)
        val h = ((bh * (1 + margin * 2)) * image.height).toInt().coerceAtLeast(1)
            .coerceAtMost(image.height - y)

        val output = ByteArrayOutputStream()
        Thumbnails.of(image)
            .sourceRegion(x, y, w, h)
            .size(256, 256)
            .outputFormat("jpg")
            .outputQuality(0.85)
            .toOutputStream(output)

        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_JPEG)
            .body(output.toByteArray())
    }
}
