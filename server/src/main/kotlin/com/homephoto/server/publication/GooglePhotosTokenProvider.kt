package com.homephoto.server.publication

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.AtomicFiles
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** 비활성 상태의 게시/폴링은 인증정보를 읽지 않는다. 명시적 앨범 정리 요청은 별도로 허용한다. */
@Component
class GooglePhotosTokenProvider(private val props: AppProperties, private val mapper: ObjectMapper) {
    internal var tokenEndpoint: URI = URI("https://oauth2.googleapis.com/token")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private var expiredConnection: String? = null

    @Synchronized fun connectionId(forManagement: Boolean = false): String = readTokens(forManagement).path("connectionId").asText().takeIf(String::isNotBlank)
        ?: throw PublicationFailure(PublicationFailure.Kind.AUTH, "CONNECTION_ID_MISSING")

    @Synchronized fun accessToken(expectedConnectionId: String, forManagement: Boolean = false): String {
        val saved = readTokens(forManagement)
        val connection = saved.path("connectionId").asText()
        if (connection != expectedConnectionId) throw PublicationFailure(PublicationFailure.Kind.AUTH, "CONNECTION_CHANGED")
        val expires = runCatching { Instant.parse(saved.path("expiresAt").asText()) }.getOrNull()
        if (expires != null && expires.isAfter(Instant.now().plusSeconds(60)) && expiredConnection != connection) {
            return saved.path("accessToken").asText().takeIf(String::isNotBlank)
                ?: throw PublicationFailure(PublicationFailure.Kind.AUTH, "ACCESS_TOKEN_MISSING")
        }
        val refresh = saved.path("refreshToken").asText().takeIf(String::isNotBlank)
            ?: throw PublicationFailure(PublicationFailure.Kind.AUTH, "REFRESH_TOKEN_MISSING")
        try {
            val client = mapper.readTree(Path.of(props.googlePhotos.clientFile).toFile()).path("installed")
            val clientId = client.path("client_id").asText().takeIf(String::isNotBlank)
                ?: throw PublicationFailure(PublicationFailure.Kind.AUTH, "DESKTOP_CLIENT_REQUIRED")
            val body = mutableMapOf("client_id" to clientId, "refresh_token" to refresh, "grant_type" to "refresh_token")
            client.path("client_secret").asText().takeIf(String::isNotBlank)?.let { body["client_secret"] = it }
            val request = HttpRequest.newBuilder(tokenEndpoint).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.entries.joinToString("&") { "${URLEncoder.encode(it.key, UTF_8)}=${URLEncoder.encode(it.value, UTF_8)}" })).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 500..599 || response.statusCode() == 429)
                throw PublicationFailure(PublicationFailure.Kind.RETRYABLE, "TOKEN_HTTP_${response.statusCode()}")
            if (response.statusCode() != 200) throw PublicationFailure(PublicationFailure.Kind.AUTH, "TOKEN_HTTP_${response.statusCode()}")
            val result = mapper.readTree(response.body())
            val access = result.path("access_token").asText().takeIf(String::isNotBlank)
                ?: throw PublicationFailure(PublicationFailure.Kind.AUTH, "TOKEN_RESPONSE_INVALID")
            val scopes = result.path("scope").asText(saved.path("scope").asText())
            if (GooglePhotosDesktopOAuth.SCOPE !in scopes.split(' ')) throw PublicationFailure(PublicationFailure.Kind.AUTH, "APPEND_SCOPE_REQUIRED")
            saved.put("accessToken", access)
            saved.put("expiresAt", Instant.now().plusSeconds(result.path("expires_in").asLong(3600)).toString())
            saved.put("scope", scopes)
            result.path("refresh_token").asText().takeIf(String::isNotBlank)?.let { saved.put("refreshToken", it) }
            AtomicFiles.write(Path.of(props.googlePhotos.tokenFile).toAbsolutePath()) { mapper.writeValue(it.toFile(), saved) }
            expiredConnection = null
            return access
        } catch (error: PublicationFailure) { throw error }
        catch (_: java.io.IOException) { throw PublicationFailure(PublicationFailure.Kind.RETRYABLE, "TOKEN_IO_FAILURE") }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw PublicationFailure(PublicationFailure.Kind.RETRYABLE, "TOKEN_REQUEST_INTERRUPTED") }
        catch (_: Exception) { throw PublicationFailure(PublicationFailure.Kind.AUTH, "TOKEN_CONFIGURATION_INVALID") }
    }

    @Synchronized fun expireAccessToken(expectedConnectionId: String? = null) { expiredConnection = expectedConnectionId ?: runCatching { connectionId() }.getOrNull() }

    private fun readTokens(forManagement: Boolean): ObjectNode {
        val settings = props.googlePhotos
        if (!settings.enabled && !forManagement) throw PublicationFailure(PublicationFailure.Kind.AUTH, "PUBLICATION_DISABLED")
        if (settings.tokenFile.isBlank() || settings.clientFile.isBlank()) throw PublicationFailure(PublicationFailure.Kind.AUTH, "CREDENTIALS_NOT_CONFIGURED")
        return try {
            val tokens = mapper.readTree(Files.readString(Path.of(settings.tokenFile))) as ObjectNode
            if (GooglePhotosDesktopOAuth.SCOPE !in tokens.path("scope").asText().split(' ')) throw PublicationFailure(PublicationFailure.Kind.AUTH, "APPEND_SCOPE_REQUIRED")
            tokens
        } catch (error: PublicationFailure) { throw error }
        catch (_: Exception) { throw PublicationFailure(PublicationFailure.Kind.AUTH, "CREDENTIALS_UNREADABLE") }
    }
}
