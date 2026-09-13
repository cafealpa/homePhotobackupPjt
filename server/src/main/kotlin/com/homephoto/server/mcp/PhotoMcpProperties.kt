package com.homephoto.server.mcp

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/** 기본은 로컬 검증 모드이며 공개 OAuth 모드는 별도 자격 증명과 HTTPS origin을 요구한다. */
@ConfigurationProperties("homephoto.mcp")
data class PhotoMcpProperties(
    val enabled: Boolean = false,
    val token: String = "",
    val baseUrl: String = "http://localhost:8080",
    val previewTtlSeconds: Long = 300,
    val mode: String = "local",
    val oauth: PhotoOAuthProperties = PhotoOAuthProperties(),
) {
    val publicOAuth: Boolean get() = enabled && mode == "oauth"
    fun validate() {
        require(mode in setOf("local", "oauth")) { "MCP mode must be local or oauth" }
        require(publicOAuth || (token.length >= 32 && token.none { it.isWhitespace() })) {
            "homephoto.mcp.token must contain at least 32 non-whitespace characters"
        }
        val uri = URI(baseUrl)
        require(if (publicOAuth) uri.scheme == "https" && !uri.host.isNullOrBlank()
            else uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]")) {
            "MCP preview currently supports a loopback HTTP base-url only; configure OAuth before remote deployment"
        }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
            "homephoto.mcp.base-url must be an origin without path or credentials"
        }
        require(previewTtlSeconds in 30..600) { "MCP preview TTL must be between 30 and 600 seconds" }
        if (publicOAuth) oauth.validate()
    }
}

data class PhotoOAuthProperties(
    val owner: String = "owner",
    val passwordHash: String = "",
    val clientId: String = "homephoto-chatgpt",
    val clientSecretHash: String = "",
    val redirectUri: String = "",
    val stateDirectory: String = "",
    val signingKeyFile: String = "",
    val previewKey: String = "",
) {
    fun validate() {
        require(owner.isNotBlank() && clientId.isNotBlank()) { "OAuth owner and client ID are required" }
        val bcrypt = Regex("\\{bcrypt}\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}")
        require(bcrypt.matches(passwordHash) && bcrypt.matches(clientSecretHash)) { "OAuth requires bcrypt password and client secret hashes" }
        val redirect = URI(redirectUri)
        require(redirect.scheme == "https" && redirect.host == "chatgpt.com" && redirect.rawQuery == null &&
            redirect.rawFragment == null && redirect.rawUserInfo == null &&
            (redirect.path == "/connector_platform_oauth_redirect" ||
                Regex("/connector/oauth/[A-Za-z0-9_-]+").matches(redirect.path))) { "Copy the exact ChatGPT redirect URI from its management page" }
        require(stateDirectory.isNotBlank() && signingKeyFile.isNotBlank()) { "Separate OAuth state directory and persistent signing key file are required" }
        require(previewKey.length >= 32 && previewKey.none { it.isWhitespace() }) { "A separate preview signing key is required" }
    }
}
