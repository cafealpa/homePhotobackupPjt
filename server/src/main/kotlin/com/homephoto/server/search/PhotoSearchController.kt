package com.homephoto.server.search

import com.homephoto.server.db.Assets
import com.homephoto.server.service.PhotoDateRange
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
class PhotoSearchController(private val search: PhotoSemanticSearch) {
    /** API 키로 보호된 내부 목록. 원본 경로나 파일 내용은 반환하지 않는다. 삭제 묘비도 전달한다. */
    @GetMapping("/api/v1/internal/search/catalog")
    fun catalog(@RequestParam(defaultValue = "0") afterId: Long): Map<String, Any?> {
        if (!search.enabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "semantic search disabled")
        if (afterId < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "afterId must be nonnegative")
        val items = transaction {
            Assets.selectAll().where { Assets.id greater afterId }.orderBy(Assets.id to SortOrder.ASC).limit(100).map {
                mapOf("id" to it[Assets.id], "hash" to it[Assets.hash], "taken_at" to it[Assets.takenAt],
                    "active" to (it[Assets.deletedAt] == null && it[Assets.purgedAt] == null &&
                        it[Assets.sourceTag] == null && it[Assets.mediaType] == "PHOTO"))
            }
        }
        return mapOf("items" to items, "next_id" to if (items.size == 100) items.last()["id"] else null)
    }

    @GetMapping("/api/v1/photos/search")
    fun search(@RequestParam params: Map<String, String>): Map<String, Any?> {
        try {
            require(params.keys.all { it in setOf("query", "date", "start_date", "end_date", "limit") }) { "지원하지 않는 검색 조건입니다." }
            val dates = params.filterKeys { it in setOf("date", "start_date", "end_date") }
            val range = if (dates.isEmpty()) null else PhotoDateRange.parse(dates)
            val limit = params["limit"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("limit은 정수입니다.") } ?: 12
            val result = search.search(params["query"] ?: "", range, limit)
            return mapOf("items" to result.items, "indexed_photos" to result.indexedPhotos,
                "model" to result.model, "approximate" to true)
        } catch (e: IllegalArgumentException) { throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: java.time.DateTimeException) { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "날짜를 확인해 주세요.")
        } catch (e: PhotoSearchUnavailable) { throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.message) }
    }
}
