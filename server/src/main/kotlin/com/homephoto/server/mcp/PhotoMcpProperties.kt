package com.homephoto.server.mcp

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/** 현재는 단일 소유자의 로컬 검증 모드. 외부 계정 연결 전 OAuth를 추가한다. */
@ConfigurationProperties("homephoto.mcp")
data class PhotoMcpProperties(
    val enabled: Boolean = false,
    val token: String = "",
    val baseUrl: String = "http://localhost:8080",
    val previewTtlSeconds: Long = 300,
) {
    fun validate() {
        require(token.length >= 32 && token.none { it.isWhitespace() }) {
            "homephoto.mcp.token must contain at least 32 non-whitespace characters"
        }
        val uri = URI(baseUrl)
        require(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")) {
            "MCP preview currently supports a loopback HTTP base-url only; configure OAuth before remote deployment"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
            "homephoto.mcp.base-url must be an origin without path or credentials"
        }
        require(previewTtlSeconds in 30..600) { "MCP preview TTL must be between 30 and 600 seconds" }
    }
}
