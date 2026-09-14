package com.homephoto.server.api

import com.homephoto.server.service.AssetQueryService
import com.homephoto.server.service.PhotoDateRange
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.DateTimeException

@RestController
class PhotoCountController(private val query: AssetQueryService) {
    /** 기존 /api 인증을 사용한다. MCP OAuth 토큰과 웹 API 키를 혼용하지 않는다. */
    @GetMapping("/api/v1/photos/count")
    fun count(@RequestParam params: Map<String, String>): Map<String, Any?> {
        val range = try {
            require(params.keys.all { it in setOf("date", "start_date", "end_date") }) { "지원하지 않는 집계 조건입니다." }
            PhotoDateRange.parse(params)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        } catch (e: DateTimeException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "실제 존재하는 날짜를 YYYY-MM-DD 형식으로 입력해 주세요.")
        }
        return range.metadata() + mapOf("count" to query.count(range.filter()), "media_type" to "PHOTO")
    }
}
