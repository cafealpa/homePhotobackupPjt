package com.homephoto.server.mcp

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress
import java.net.URI

/** Caddy는 같은 장비의 loopback에서만 신뢰한다. 외부 호스트/프로토콜은 고정 설정과 대조한다. */
class PhotoOAuthBoundaryFilter(private val props: PhotoMcpProperties) : OncePerRequestFilter() {
    private val windows = mutableMapOf<String, Pair<Long, Int>>()
    private val grantMutationLock = Any()
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !protectedPath(request.servletPath)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!props.publicOAuth) {
            if (request.servletPath.startsWith("/oauth") || request.servletPath.startsWith("/.well-known/")) {
                response.sendError(404); return
            }
            chain.doFilter(request, response); return
        }
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("X-Content-Type-Options", "nosniff")
        val base = URI(props.baseUrl)
        if (!InetAddress.getByName(request.remoteAddr).isLoopbackAddress ||
            request.getHeader("X-Forwarded-Proto") != "https" ||
            request.getHeader("X-Forwarded-Host") != base.rawAuthority ||
            request.getHeader("Host") != base.rawAuthority || request.servletPath == "/mcp-dev" ||
            request.getHeader("Origin")?.let { it != props.baseUrl } == true) {
            response.sendError(403); return
        }
        val limit = when {
            request.servletPath == "/oauth/login" && request.method == "POST" -> "login" to 10
            request.servletPath == "/oauth2/token" -> "token" to 120
            request.servletPath == "/mcp" -> "mcp" to 120
            request.servletPath.startsWith("/mcp-media/") -> "preview" to 600
            else -> null
        }
        if (limit != null && !allow(limit.first, limit.second)) {
            response.setHeader("Retry-After", "60"); response.status = 429; return
        }
        // 인증 코드 요청과 교환/갱신 시 resource를 고정한다. 동의 POST의 저장된 요청은 SAS가 검증한다.
        val authorizationRequest = request.servletPath == "/oauth2/authorize" && request.getParameter("response_type") != null
        if (authorizationRequest || request.servletPath == "/oauth2/token") {
            if (request.getParameterValues("resource")?.toList() != listOf("${props.baseUrl}/mcp")) {
                response.status = 400; response.contentType = "application/json"
                response.writer.write("{\"error\":\"invalid_target\"}"); return
            }
        }
        if (authorizationRequest && (request.getParameterValues("code_challenge_method")?.toList() != listOf("S256") ||
                request.getParameter("code_challenge")?.matches(Regex("[A-Za-z0-9_-]{43}")) != true)) {
            response.status = 400; response.contentType = "application/json"
            response.writer.write("{\"error\":\"invalid_request\",\"error_description\":\"S256 PKCE required\"}"); return
        }
        // 인증 전 경계 검사 후에만 public origin을 적용한다. 전역 ForwardedHeaderFilter는 사용하지 않는다.
        val forwardedRequest = object : HttpServletRequestWrapper(request) {
            override fun getScheme() = "https"
            override fun isSecure() = true
            override fun getServerName() = base.host
            override fun getServerPort() = if (base.port < 0) 443 else base.port
            override fun getRequestURL() = StringBuffer(props.baseUrl + request.requestURI)
        }
        // 단일 프로세스에서 갱신이 연결 철회와 경합해 삭제한 grant를 되살리지 않도록 직렬화한다.
        if (request.servletPath in setOf("/oauth2/token", "/oauth2/revoke", "/oauth/revoke-all")) {
            synchronized(grantMutationLock) { chain.doFilter(forwardedRequest, response) }
        } else chain.doFilter(forwardedRequest, response)
    }

    // 단일 소유자 서버 전체의 분당 상한. IP별 무제한 맵을 만들지 않는다.
    @Synchronized private fun allow(group: String, maximum: Int): Boolean {
        val minute = System.nanoTime() / 60_000_000_000L
        val previous = windows[group]
        val count = if (previous?.first == minute) previous.second + 1 else 1
        windows[group] = minute to count.coerceAtMost(maximum + 1)
        return count <= maximum
    }

    companion object {
        fun protectedPath(path: String) = path == "/mcp" || path.startsWith("/mcp/") ||
            path.startsWith("/mcp-media/") || path == "/mcp-dev" || path.startsWith("/oauth/") ||
            path.startsWith("/oauth2/") || path.startsWith("/.well-known/oauth-")
    }
}
