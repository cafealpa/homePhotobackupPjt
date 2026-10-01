package com.homephoto.server.publication

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

interface GooglePhotosPublisher {
    fun connectionId(): String
    fun uploadBytes(file: Path, connectionId: String, contentType: String = "image/jpeg"): String
    fun createMediaItem(uploadToken: String, fileName: String, connectionId: String, albumId: String? = null): Published
    fun albumConnectionId(): String = connectionId()
    fun createAlbum(title: String, connectionId: String): Album
    fun addToAlbum(albumId: String, mediaItemIds: List<String>, connectionId: String)
    data class Published(val mediaItemId: String, val productUrl: String?)
    data class Album(val id: String, val productUrl: String?)
}

class PublicationFailure(val kind: Kind, val code: String, val retryAfterSeconds: Long = 30) : RuntimeException(code) {
    enum class Kind { AUTH, RETRYABLE, PERMANENT, UNCERTAIN }
}

/** 하나의 계정에 대한 batchCreate 호출은 전용 단일 워커가 직렬화한다. */
@Component
class GooglePhotosLibraryPublisher(private val tokens: GooglePhotosTokenProvider, private val mapper: ObjectMapper) : GooglePhotosPublisher {
    internal var endpoint: URI = URI("https://photoslibrary.googleapis.com")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    override fun connectionId(): String = tokens.connectionId()
    override fun albumConnectionId(): String = tokens.connectionId(forManagement = true)

    override fun uploadBytes(file: Path, connectionId: String, contentType: String): String {
        if (contentType.startsWith("video/")) return GooglePhotosResumableUpload(mapper) { uri, body, headers ->
            send(uri.toString(), body, "application/octet-stream", connectionId, false, headers = headers)
        }.upload(file, endpoint, connectionId, contentType)
        val response = send("/v1/uploads", HttpRequest.BodyPublishers.ofFile(file), "application/octet-stream", connectionId, false,
            headers = mapOf("X-Goog-Upload-Content-Type" to contentType, "X-Goog-Upload-Protocol" to "raw"))
        return response.body().trim().takeIf { it.isNotEmpty() }
            ?: throw PublicationFailure(PublicationFailure.Kind.RETRYABLE, "EMPTY_UPLOAD_TOKEN")
    }

    override fun createMediaItem(uploadToken: String, fileName: String, connectionId: String, albumId: String?): GooglePhotosPublisher.Published {
        if (fileName.length !in 1..255) throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "FILE_NAME_UNSUPPORTED")
        val payload = mutableMapOf<String, Any>("newMediaItems" to listOf(mapOf("simpleMediaItem" to mapOf("uploadToken" to uploadToken, "fileName" to fileName))))
        albumId?.let { payload["albumId"] = it }
        val response = send("/v1/mediaItems:batchCreate", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)), "application/json", connectionId, true)
        try {
            val rows = mapper.readTree(response.body()).path("newMediaItemResults").toList()
            val row = rows.singleOrNull { it.path("uploadToken").asText() == uploadToken }
                ?: throw PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "CREATE_RESULT_MISSING")
            val code = row.path("status").path("code").asInt()
            if (code != 0) throw PublicationFailure(when (code) {
                16 -> PublicationFailure.Kind.AUTH
                8 -> PublicationFailure.Kind.RETRYABLE
                2, 13, 14 -> PublicationFailure.Kind.UNCERTAIN
                else -> PublicationFailure.Kind.PERMANENT
            }, "CREATE_RPC_$code")
            val media = row.path("mediaItem")
            val id = media.path("id").asText().takeIf { it.isNotBlank() }
                ?: throw PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "CREATE_ID_MISSING")
            return GooglePhotosPublisher.Published(id, media.path("productUrl").asText().takeIf { it.isNotBlank() })
        } catch (error: PublicationFailure) { throw error }
        catch (_: Exception) { throw PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "CREATE_RESULT_INVALID") }
    }

    override fun createAlbum(title: String, connectionId: String): GooglePhotosPublisher.Album {
        val response = send("/v1/albums", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("album" to mapOf("title" to title)))),
            "application/json", connectionId, true, management = true)
        return try {
            val album = mapper.readTree(response.body())
            val id = album.path("id").asText().takeIf(String::isNotBlank)
                ?: throw PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "ALBUM_ID_MISSING")
            GooglePhotosPublisher.Album(id, album.path("productUrl").asText().takeIf(String::isNotBlank))
        } catch (error: PublicationFailure) { throw error }
        catch (_: Exception) { throw PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "ALBUM_RESULT_INVALID") }
    }

    override fun addToAlbum(albumId: String, mediaItemIds: List<String>, connectionId: String) {
        require(mediaItemIds.size in 1..50)
        val path = "/v1/albums/${java.net.URLEncoder.encode(albumId, java.nio.charset.StandardCharsets.UTF_8)}:batchAddMediaItems"
        send(path, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf("mediaItemIds" to mediaItemIds))),
            "application/json", connectionId, false, management = true)
    }

    private fun send(path: String, body: HttpRequest.BodyPublisher, type: String, connectionId: String, creating: Boolean,
                     management: Boolean = false, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val request = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer ${tokens.accessToken(connectionId, forManagement = management)}").header("Content-Type", type).POST(body)
        headers.forEach { (name, value) -> request.header(name, value) }
        val response = try { http.send(request.build(), HttpResponse.BodyHandlers.ofString()) }
        catch (_: IOException) { throw PublicationFailure(if (creating) PublicationFailure.Kind.UNCERTAIN else PublicationFailure.Kind.RETRYABLE, "TRANSPORT_FAILURE") }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PublicationFailure(if (creating) PublicationFailure.Kind.UNCERTAIN else PublicationFailure.Kind.RETRYABLE, "REQUEST_INTERRUPTED")
        }
        when (val status = response.statusCode()) {
            in 200..299 -> return response
            401 -> { tokens.expireAccessToken(connectionId); throw PublicationFailure(PublicationFailure.Kind.AUTH, "HTTP_401") }
            429 -> throw PublicationFailure(PublicationFailure.Kind.RETRYABLE, "HTTP_429", retryAfter(response).coerceAtLeast(30))
            in 500..599 -> throw PublicationFailure(if (creating) PublicationFailure.Kind.UNCERTAIN else PublicationFailure.Kind.RETRYABLE, "HTTP_$status", retryAfter(response))
            else -> throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "HTTP_$status")
        }
    }

    private fun retryAfter(response: HttpResponse<*>): Long {
        val value = response.headers().firstValue("Retry-After").orElse("")
        return value.toLongOrNull()?.coerceAtLeast(0) ?: runCatching {
            Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds.coerceAtLeast(0)
        }.getOrDefault(30)
    }
}
