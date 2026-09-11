package com.homephoto.server.mcp

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress
import java.security.MessageDigest

/** 프록시 공개를 포함해 로컬 검증 경계를 넘지 않도록 한다. 기존 hp_auth 쿠키는 사용하지 않는다. */
class PhotoMcpAccessFilter(private val props: PhotoMcpProperties) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.servletPath != "/mcp-dev" && request.servletPath != "/mcp" && !request.servletPath.startsWith("/mcp/") &&
            !request.servletPath.startsWith("/mcp-media/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Referrer-Policy", "no-referrer")
        if (!props.enabled) {
            response.sendError(404)
            return
        }
        val loopback = runCatching { InetAddress.getByName(request.remoteAddr).isLoopbackAddress }.getOrDefault(false)
        val forwarded = listOf("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Real-IP")
            .any { request.getHeader(it) != null }
        if (!loopback || forwarded || request.serverName !in setOf("localhost", "127.0.0.1", "[::1]")) {
            response.sendError(403, "MCP is restricted to local development")
            return
        }
        if (request.servletPath == "/mcp-dev") {
            chain.doFilter(request, response)
            return
        }
        if (request.servletPath.startsWith("/mcp-media/")) {
            chain.doFilter(request, response) // 사진별 서명은 미리보기 컨트롤러가 검증한다.
            return
        }
        val origin = request.getHeader("Origin")
        if (origin != null && origin != props.baseUrl) {
            response.sendError(403, "Origin not allowed")
            return
        }
        val expected = "Bearer ${props.token}".toByteArray(Charsets.UTF_8)
        val actual = request.getHeader("Authorization").orEmpty().toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(expected, actual)) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"homephoto-local\"")
            response.sendError(401)
            return
        }
        chain.doFilter(request, response)
    }
}
