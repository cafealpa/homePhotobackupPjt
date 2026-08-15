package com.homephoto.server.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 단일 사용자용 고정 API 키 인증.
 * - 앱/워커: X-Api-Key 헤더
 * - 웹 뷰어: hp_auth 쿠키 (브라우저 <img>/<video>는 헤더를 못 붙이므로)
 * 정적 파일(웹 뷰어 자체)과 헬스체크, 로그인은 인증 없이 접근 가능.
 */
@Component
class ApiKeyFilter(private val props: AppProperties) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return !uri.startsWith("/api/") ||
            uri == "/api/v1/health" ||
            uri == "/api/v1/auth/login"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val headerOk = request.getHeader("X-Api-Key") == props.apiKey
        val cookieOk = request.cookies?.any { it.name == AUTH_COOKIE && it.value == props.apiKey } == true
        if (!headerOk && !cookieOk) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid api key")
            return
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val AUTH_COOKIE = "hp_auth"
    }
}
