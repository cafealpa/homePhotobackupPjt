package com.homephoto.server.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.net.URI
import java.net.URLDecoder
import org.springframework.security.oauth2.server.authorization.*
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.security.oauth2.jwt.*
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.core.OAuth2AccessToken
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import com.homephoto.server.db.Assets
import com.homephoto.server.service.ThumbnailService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.mockito.Mockito
import java.time.Instant

@SpringBootTest(classes = [McpTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["logging.file.name=", "homephoto.mcp.enabled=true", "homephoto.mcp.mode=oauth"])
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(PhotoOAuthLegacyTestConfiguration::class)
class PhotoOAuthIntegrationTest {
    @org.springframework.boot.test.web.server.LocalServerPort private var port: Int = 0
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var db: PhotoOAuthDatabase
    @Autowired lateinit var grants: OAuth2AuthorizationService
    @Autowired lateinit var clients: RegisteredClientRepository
    @Autowired lateinit var keys: JWKSource<SecurityContext>
    @Autowired lateinit var thumbnails: ThumbnailService

    private fun proxied(request: MockHttpServletRequestBuilder) = request
        .header("Host", "photos.example.test").header("X-Forwarded-Host", "photos.example.test")
        .header("X-Forwarded-Proto", "https").header("X-Forwarded-For", "203.0.113.40")
        .with { it.remoteAddr = "127.0.0.1"; it.servletPath = it.requestURI!!; it.pathInfo = null; it }

    private fun code(challengeMethod: String = "S256"): String {
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(VERIFIER.toByteArray()))
        val response = mvc.perform(proxied(get("/oauth2/authorize").queryParam("response_type", "code")
            .queryParam("client_id", "homephoto-chatgpt").queryParam("redirect_uri", REDIRECT).queryParam("scope", "photos:read")
            .queryParam("state", "test-state").queryParam("resource", "$BASE/mcp")
            .queryParam("code_challenge", challenge).queryParam("code_challenge_method", challengeMethod))
            .with(user("owner").roles("PHOTO_OWNER"))).andReturn().response
        assertEquals(302, response.status, response.contentAsString)
        if (URI(response.redirectedUrl!!).host == "chatgpt.com") return query(response.redirectedUrl!!, "code")
        assertTrue(response.redirectedUrl!!.contains("/oauth/consent"), response.redirectedUrl)
        val state = query(response.redirectedUrl!!, "state")
        val consent = mvc.perform(proxied(post("/oauth2/authorize").param("client_id", "homephoto-chatgpt")
            .param("state", state).param("scope", "photos:read")).with(user("owner").roles("PHOTO_OWNER")))
            .andReturn().response
        assertEquals(302, consent.status, consent.contentAsString)
        return query(consent.redirectedUrl!!, "code")
    }

    private fun exchange(code: String, verifier: String = VERIFIER, resource: String = "$BASE/mcp") = mvc.perform(
        proxied(post("/oauth2/token").param("grant_type", "authorization_code").param("code", code)
            .param("redirect_uri", REDIRECT).param("code_verifier", verifier).param("resource", resource))
            .with(httpBasic("homephoto-chatgpt", SECRET))).andReturn().response

    private fun token(): JsonNode {
        val response = exchange(code())
        assertEquals(200, response.status, response.contentAsString)
        return mapper.readTree(response.contentAsString)
    }

    private fun rpc(token: String? = null, method: String = "tools/list", params: Map<String, Any> = emptyMap()) = mvc.perform(proxied(post("/mcp")
        .contentType("application/json").header("Accept", "application/json, text/event-stream")
        .content(mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))))
        .also { if (token != null) it.header("Authorization", "Bearer $token") }).andReturn().response

    @Test fun `metadata challenge and proxy boundary are explicit`() {
        assertEquals(403, mvc.perform(get("/.well-known/oauth-protected-resource").servletPath("/.well-known/oauth-protected-resource")).andReturn().response.status)
        val metadata = mvc.perform(proxied(get("/.well-known/oauth-protected-resource"))).andReturn().response
        assertEquals(200, metadata.status, metadata.contentAsString)
        assertEquals("$BASE/mcp", mapper.readTree(metadata.contentAsString)["resource"].asText())
        val discovery = mvc.perform(proxied(get("/.well-known/oauth-authorization-server"))).andReturn().response
        assertEquals(200, discovery.status, discovery.contentAsString)
        assertEquals(listOf("S256"), mapper.readTree(discovery.contentAsString)["code_challenge_methods_supported"].map { it.asText() })
        assertEquals(401, rpc().status)
        assertTrue(rpc().getHeader("WWW-Authenticate")!!.contains("resource_metadata="))
        assertEquals(401, rpc("wrong-token").status)
        assertEquals(403, mvc.perform(proxied(get("/mcp-dev"))).andReturn().response.status)
        assertEquals(403, mvc.perform(proxied(get("/.well-known/oauth-protected-resource")).with { it.remoteAddr = "192.168.1.9"; it }).andReturn().response.status)
    }

    @Test fun `code PKCE refresh rotation and revocation work with persisted grants`() {
        val tokens = token()
        assertEquals(200, rpc(tokens["access_token"].asText()).status)
        val refresh = tokens["refresh_token"].asText()
        fun renew() = mvc.perform(proxied(post("/oauth2/token").param("grant_type", "refresh_token")
            .param("refresh_token", refresh).param("resource", "$BASE/mcp"))
            .with(httpBasic("homephoto-chatgpt", SECRET))).andReturn().response
        val renewed = renew()
        assertEquals(200, renewed.status, renewed.contentAsString)
        assertEquals(400, renew().status)
        val access = mapper.readTree(renewed.contentAsString)["access_token"].asText()
        assertEquals(200, rpc(access).status)
        val revoked = mvc.perform(proxied(post("/oauth2/revoke").param("token", access))
            .with(httpBasic("homephoto-chatgpt", SECRET))).andReturn().response
        assertEquals(200, revoked.status, revoked.contentAsString)
        assertEquals(401, rpc(access).status)
    }

    @Test fun `code rejects replay wrong verifier and wrong resource`() {
        val first = code()
        assertEquals(200, exchange(first).status)
        assertEquals(400, exchange(first).status)
        assertEquals(400, exchange(code(), "wrong-verifier").status)
        assertEquals(400, exchange(code(), resource = "https://other.example/mcp").status)
    }

    @Test fun `owner login and connection revocation require password and CSRF`() {
        val login = mvc.perform(proxied(get("/oauth/login"))).andReturn().response
        assertEquals(200, login.status)
        assertTrue(login.contentAsString.contains("비밀번호"))
        assertEquals(403, mvc.perform(proxied(post("/oauth/login").param("username", "owner").param("password", PASSWORD))).andReturn().response.status)
        val good = mvc.perform(proxied(post("/oauth/login").param("username", "owner").param("password", PASSWORD)).with(csrf())).andReturn()
        assertEquals(302, good.response.status)
        assertFalse(good.response.redirectedUrl!!.contains("error"))
        val tokens = token()
        assertEquals(403, mvc.perform(proxied(post("/oauth/revoke-all")).with(user("owner").roles("PHOTO_OWNER"))).andReturn().response.status)
        assertEquals(302, mvc.perform(proxied(post("/oauth/revoke-all")).with(user("owner").roles("PHOTO_OWNER")).with(csrf())).andReturn().response.status)
        assertEquals(401, rpc(tokens["access_token"].asText()).status)
    }

    @Test fun `validly signed tokens still require issuer audience expiry and read scope`() {
        val original = token()["access_token"].asText()
        val grant = grants.findByToken(original, OAuth2TokenType.ACCESS_TOKEN)!!
        val encoder = NimbusJwtEncoder(keys)
        fun crafted(issuer: String = BASE, audience: String = "$BASE/mcp", scope: String = "photos:read", expired: Boolean = false): String {
            val issued = Instant.now().minusSeconds(600)
            val expires = if (expired) Instant.now().minusSeconds(120) else Instant.now().plusSeconds(300)
            val jwt = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                JwtClaimsSet.builder().issuer(issuer).subject("owner").audience(listOf(audience))
                    .issuedAt(issued).expiresAt(expires).claim("scope", scope).build())).tokenValue
            grants.save(OAuth2Authorization.from(grant).token(OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                jwt, issued, expires, setOf("photos:read"))).build())
            return jwt
        }
        assertEquals(401, rpc(crafted(issuer = "https://wrong.example")).status)
        assertEquals(401, rpc(crafted(audience = "https://wrong.example/mcp")).status)
        assertEquals(401, rpc(crafted(expired = true)).status)
        assertEquals(403, rpc(crafted(scope = "other:read")).status)
        val valid = crafted()
        assertEquals(200, rpc(valid).status)
        assertEquals(401, rpc(valid.dropLast(5) + "AAAAA").status)
        PhotoOAuthDatabase(root.resolve("state").toString()).use { reopened ->
            assertEquals(grant.id, JdbcOAuth2AuthorizationService(reopened.jdbc, clients)
                .findByToken(valid, OAuth2TokenType.ACCESS_TOKEN)?.id)
        }
    }

    @Test fun `OAuth photo search issues revocable HTTPS previews without exposing originals`() {
        val photos = Database.connect("jdbc:sqlite:${root.resolve("sample-photos.db")}", driver = "org.sqlite.JDBC")
        val jpeg = root.resolve("sample.jpg")
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpg", jpeg.toFile())
        Mockito.`when`(thumbnails.thumbPath(Mockito.anyString(), Mockito.anyInt())).thenReturn(jpeg)
        try {
            transaction(photos) {
                SchemaUtils.create(Assets)
                Assets.insert {
                    it[id] = 1L; it[hash] = "sample-hash"; it[mediaType] = "PHOTO"
                    it[originalPath] = "private/original.jpg"; it[originalFilename] = "original.jpg"
                    it[fileSize] = 10; it[takenAt] = "2025-11-03T12:00:00"; it[takenAtSource] = "FILENAME"; it[yearMonth] = "2025-11"
                    it[createdAt] = "2025-11-03T12:00:00"
                }
            }
            val access = token()["access_token"].asText()
            val result = rpc(access, "tools/call", mapOf("name" to "search_photos", "arguments" to mapOf("date" to "2025-11-03")))
            assertEquals(200, result.status, result.contentAsString)
            val body = mapper.readTree(result.contentAsString)["result"]
            assertFalse(body["isError"].asBoolean(), result.contentAsString)
            assertFalse(body["structuredContent"].toString().contains("private"))
            val url = body["_meta"]["previews"]["1"]["thumbnailUrl"].asText()
            assertTrue(url.startsWith("$BASE/mcp-media/1?"))
            assertTrue(url.contains("grant="))
            fun preview(value: String = url) = mvc.perform(proxied(get(URI(value)))).andReturn().response
            assertEquals(200, preview().status)
            assertEquals(403, preview(url.replace("size=400", "size=1600")).status)
            assertEquals(403, preview(url.replace(Regex("expires=\\d+"), "expires=1")).status)
            grants.remove(grants.findByToken(access, OAuth2TokenType.ACCESS_TOKEN)!!)
            assertEquals(403, preview().status)
        } finally { TransactionManager.closeAndUnregister(photos) }
    }

    @Test fun `authorization rejects plain PKCE wrong redirect and missing resource`() {
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(VERIFIER.toByteArray()))
        fun attempt(method: String, redirect: String, resource: String) = mvc.perform(proxied(get("/oauth2/authorize")
            .queryParam("client_id", "homephoto-chatgpt").queryParam("response_type", "code").queryParam("scope", "photos:read")
            .queryParam("redirect_uri", redirect).queryParam("resource", resource)
            .queryParam("code_challenge", challenge).queryParam("code_challenge_method", method))
            .with(user("owner").roles("PHOTO_OWNER"))).andReturn().response
        assertEquals(400, attempt("plain", REDIRECT, "$BASE/mcp").status)
        assertEquals(400, attempt("S256", "https://evil.example/callback", "$BASE/mcp").status)
        assertEquals(400, attempt("S256", REDIRECT, "").status)
    }

    @Test fun `real HTTP server preserves OAuth boundary and secure login cookie`() {
        fun request(path: String, bearer: String? = null, proxy: Boolean = true): String {
            val body = if (path == "/mcp") """{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""" else ""
            return java.net.Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 10000
                val headers = buildString {
                    append("${if (body.isEmpty()) "GET" else "POST"} $path HTTP/1.1\r\nHost: photos.example.test\r\nConnection: close\r\n")
                    if (proxy) append("X-Forwarded-Host: photos.example.test\r\nX-Forwarded-Proto: https\r\nX-Forwarded-For: 203.0.113.40\r\n")
                    if (bearer != null) append("Authorization: Bearer $bearer\r\n")
                    append("Accept: ${if (body.isEmpty()) "text/html" else "application/json, text/event-stream"}\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
                }
                socket.getOutputStream().write(headers.toByteArray())
                socket.getInputStream().bufferedReader().readText()
            }
        }
        assertTrue(request("/mcp").startsWith("HTTP/1.1 401"))
        assertTrue(request("/mcp", proxy = false).startsWith("HTTP/1.1 403"))
        val success = request("/mcp", token()["access_token"].asText())
        assertTrue(success.startsWith("HTTP/1.1 200"), success)
        assertTrue(success.contains("search_photos"), success)
        val login = request("/oauth/login")
        assertTrue(login.contains("__Host-hp_oauth="), login)
        assertTrue(login.contains("Secure") && login.contains("HttpOnly") && login.contains("SameSite=Lax"), login)
    }

    @Test fun `legacy web and worker authentication remain independent of OAuth`() {
        assertEquals(401, mvc.perform(get("/api/v1/auth/check")).andReturn().response.status)
        assertEquals(200, mvc.perform(get("/api/v1/auth/check").header("X-Api-Key", LEGACY_KEY)).andReturn().response.status)
        assertEquals(200, mvc.perform(get("/api/v1/auth/check").cookie(jakarta.servlet.http.Cookie("hp_auth", LEGACY_KEY))).andReturn().response.status)
        assertEquals(401, mvc.perform(get("/api/v1/auth/check").header("Authorization", "Bearer ${token()["access_token"].asText()}")).andReturn().response.status)
        val login = mvc.perform(post("/api/v1/auth/login").contentType("application/json")
            .content(mapper.writeValueAsString(mapOf("key" to LEGACY_KEY)))).andReturn().response
        assertEquals(200, login.status, login.contentAsString)
        assertTrue(login.getHeader("Set-Cookie")!!.startsWith("hp_auth="))
        assertEquals(401, mvc.perform(proxied(post("/mcp")).header("X-Api-Key", LEGACY_KEY)
            .cookie(jakarta.servlet.http.Cookie("hp_auth", LEGACY_KEY))).andReturn().response.status)
    }

    companion object {
        const val BASE = "https://photos.example.test"
        const val REDIRECT = "https://chatgpt.com/connector/oauth/homephoto-test"
        const val PASSWORD = "test-owner-password-only"
        const val SECRET = "test-client-secret-0123456789-only"
        const val VERIFIER = "test-pkce-verifier-0123456789-abcdefghijklmnopqrstuvwxyz"
        private val root = Files.createTempDirectory("homephoto-oauth-test-")
        private val key = root.resolve("signing.json").also { Files.writeString(it, RSAKeyGenerator(2048).keyID("test-key").generate().toJSONString()) }
        @JvmStatic @DynamicPropertySource fun config(registry: DynamicPropertyRegistry) {
            val encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder()
            registry.add("homephoto.mcp.base-url") { BASE }
            registry.add("homephoto.storage-root") { root.resolve("unused-legacy-storage").toString() }
            registry.add("homephoto.api-key") { LEGACY_KEY }
            registry.add("homephoto.mcp.oauth.password-hash") { encoder.encode(PASSWORD) }
            registry.add("homephoto.mcp.oauth.client-secret-hash") { encoder.encode(SECRET) }
            registry.add("homephoto.mcp.oauth.redirect-uri") { REDIRECT }
            registry.add("homephoto.mcp.oauth.state-directory") { root.resolve("state").toString() }
            registry.add("homephoto.mcp.oauth.signing-key-file") { key.toString() }
            registry.add("homephoto.mcp.oauth.preview-key") { "test-preview-key-0123456789-abcdefghijklmnopqrstuvwxyz" }
        }
        private fun query(url: String, key: String) = URI(url).rawQuery.split('&').map { it.split('=', limit = 2) }
            .first { it[0] == key }.let { URLDecoder.decode(it[1], Charsets.UTF_8) }
    }
}

const val LEGACY_KEY = "test-legacy-api-key-only-0123456789"
@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
@org.springframework.context.annotation.Import(com.homephoto.server.config.ApiKeyFilter::class, com.homephoto.server.api.AuthController::class)
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.homephoto.server.config.AppProperties::class)
class PhotoOAuthLegacyTestConfiguration
