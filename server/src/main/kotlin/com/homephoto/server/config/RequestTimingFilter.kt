package com.homephoto.server.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 요청 처리 시간 기록. 느린 요청(기본 500ms 초과)은 WARN으로 남겨 "그리드가 느리다"의 원인이
 * DB 쿼리인지, 썸네일 파일 읽기인지, 업로드인지 로그만 보고 판별할 수 있게 한다.
 * 모든 요청은 DEBUG로 남기므로 필요할 때 `logging.level.com.homephoto.server.config.RequestTimingFilter: debug`.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestTimingFilter(private val props: AppProperties) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/") // 정적 파일(웹 뷰어 자체)은 제외

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val started = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val ms = (System.nanoTime() - started) / 1_000_000
            val query = request.queryString?.let { "?$it" } ?: ""
            if (ms >= props.slowRequestMs) {
                log.warn("느린 요청 {}ms: {} {}{} → {}", ms, request.method, request.requestURI, query, response.status)
            } else if (log.isDebugEnabled) {
                log.debug("{}ms: {} {}{} → {}", ms, request.method, request.requestURI, query, response.status)
            }
        }
    }
}
