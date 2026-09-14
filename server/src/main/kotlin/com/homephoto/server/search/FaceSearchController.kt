package com.homephoto.server.search

import com.homephoto.server.db.Assets
import com.homephoto.server.db.Faces
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.Base64

@RestController
class FaceSearchController(private val search: FaceVectorSearch) {
    @GetMapping("/api/v1/internal/search/faces")
    fun catalog(@RequestParam(defaultValue = "0") afterId: Long): Map<String, Any?> {
        if (!search.enabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "search disabled")
        if (afterId < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "afterId must be nonnegative")
        val rows = transaction {
            Faces.innerJoin(Assets).selectAll().where { Faces.id greater afterId }
                .orderBy(Faces.id to SortOrder.ASC).limit(100).map {
                    val active = !it[Faces.hidden] && it[Assets.deletedAt] == null && it[Assets.purgedAt] == null &&
                        it[Assets.sourceTag] == null && it[Assets.mediaType] == "PHOTO"
                    mapOf("face_id" to it[Faces.id], "asset_id" to it[Faces.assetId], "active" to active,
                        "embedding" to if (active) Base64.getEncoder().encodeToString(it[Faces.embedding].bytes) else null)
                }
        }
        return mapOf("model" to FACE_VECTOR_MODEL, "items" to rows,
            "next_id" to if (rows.size == 100) rows.last()["face_id"] else null)
    }

    @GetMapping("/api/v1/assets/{assetId}/faces")
    fun faces(@PathVariable assetId: Long): List<FaceReference> = search.faces(assetId)

    @GetMapping("/api/v1/faces/{faceId}/similar")
    fun similar(@PathVariable faceId: Long, @RequestParam(defaultValue = "20") limit: Int,
                @RequestParam(defaultValue = "0.55") minSimilarity: Double): FaceSuggestions {
        try { return search.similar(faceId, limit, minSimilarity)
        } catch (e: IllegalArgumentException) { throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: PhotoSearchUnavailable) { throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message) }
    }
}
