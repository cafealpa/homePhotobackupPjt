package com.homephoto.server.config

import com.homephoto.server.service.ServerActivity
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ServerDrainFilter(private val activity: ServerActivity) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method == "GET" && request.requestURI in setOf("/api/v1/health", "/api/v1/admin/maintenance/status")
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!activity.enter()) {
            response.status = 503
            response.setHeader("Retry-After", "30")
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("""{"error":"서버 종료 준비 중입니다. 잠시 후 다시 시도해 주세요."}""")
            return
        }
        try { chain.doFilter(request, response) } finally { activity.leave() }
    }
}
