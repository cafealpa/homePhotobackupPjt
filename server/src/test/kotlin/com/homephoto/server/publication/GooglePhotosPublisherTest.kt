package com.homephoto.server.publication

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.homephoto.server.config.AppProperties
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

@Timeout(30)
class GooglePhotosPublisherTest {
    @TempDir lateinit var temp: Path
    private val mapper = jacksonObjectMapper()
    private fun credentials(expired: Boolean = false): AppProperties {
        val client = temp.resolve("client.json")
        val tokens = temp.resolve("tokens.json")
        mapper.writeValue(client.toFile(), mapOf("installed" to mapOf("client_id" to "client-fixture", "client_secret" to "secret-fixture")))
        mapper.writeValue(tokens.toFile(), mapOf("connectionId" to "connection-fixture", "accessToken" to "access-fixture",
            "refreshToken" to "refresh-fixture&plus", "expiresAt" to (if (expired) Instant.EPOCH else Instant.now().plusSeconds(3600)).toString(),
            "scope" to GooglePhotosDesktopOAuth.SCOPE))
        return AppProperties(temp.resolve("data"), "test", googlePhotos = AppProperties.GooglePhotosProperties(
            enabled = true, clientFile = client.toString(), tokenFile = tokens.toString()))
    }

    @Test fun `production publisher sends JPEG bytes and original name without metadata request fields`() {
        val props = credentials()
        val provider = GooglePhotosTokenProvider(props, mapper)
        val publisher = GooglePhotosLibraryPublisher(provider, mapper)
        val file = Files.write(temp.resolve("export.jpg"), byteArrayOf(1, 2, 3))
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            assertEquals("Bearer access-fixture", exchange.requestHeaders.getFirst("Authorization"))
            val result = if (exchange.requestURI.path == "/v1/uploads") {
                assertEquals("image/jpeg", exchange.requestHeaders.getFirst("X-Goog-Upload-Content-Type"))
                assertEquals("raw", exchange.requestHeaders.getFirst("X-Goog-Upload-Protocol"))
                assertContentEquals(Files.readAllBytes(file), exchange.requestBody.readAllBytes())
                "upload-fixture"
            } else {
                val payload = mapper.readTree(exchange.requestBody)
                assertEquals("album-fixture", payload.path("albumId").asText())
                val item = payload.path("newMediaItems")[0]
                assertEquals(setOf("simpleMediaItem"), item.fieldNames().asSequence().toSet())
                assertEquals("원본.HEIC", item.path("simpleMediaItem").path("fileName").asText())
                assertEquals("upload-fixture", item.path("simpleMediaItem").path("uploadToken").asText())
                """{"newMediaItemResults":[{"uploadToken":"upload-fixture","status":{"code":0},"mediaItem":{"id":"google-id","productUrl":"https://photos.google.com/photo/test"}}]}"""
            }
            count.incrementAndGet()
            val bytes = result.toByteArray(); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            publisher.endpoint = URI("http://127.0.0.1:${server.address.port}")
            val connection = publisher.connectionId()
            val token = publisher.uploadBytes(file, connection)
            val result = publisher.createMediaItem(token, "원본.HEIC", connection, "album-fixture")
            assertEquals("google-id", result.mediaItemId); assertEquals(2, count.get())
        } finally { server.stop(0) }
    }

    @Test fun `video resumes from server offset after restart uses original MIME and caches final token`() {
        val publisher = GooglePhotosLibraryPublisher(GooglePhotosTokenProvider(credentials(), mapper), mapper)
        val original = ByteArray(8 * 1024 * 1024 + 5) { (it % 251).toByte() }
        val file = Files.write(temp.resolve("clip.mp4"), original)
        val received = ByteArrayOutputStream()
        var starts = 0; var queries = 0; var uploads = 0; var creates = 0
        val offsets = mutableListOf<Long>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            assertEquals("Bearer access-fixture", exchange.requestHeaders.getFirst("Authorization"))
            var status = 200
            val response = when (exchange.requestHeaders.getFirst("X-Goog-Upload-Command")) {
                "start" -> {
                    starts++
                    assertEquals("video/mp4", exchange.requestHeaders.getFirst("X-Goog-Upload-Content-Type"))
                    assertEquals("resumable", exchange.requestHeaders.getFirst("X-Goog-Upload-Protocol"))
                    assertEquals(original.size.toString(), exchange.requestHeaders.getFirst("X-Goog-Upload-Raw-Size"))
                    exchange.responseHeaders.set("X-Goog-Upload-URL", "http://127.0.0.1:${server.address.port}/v1/uploads?upload_id=fixture")
                    exchange.responseHeaders.set("X-Goog-Upload-Chunk-Granularity", "262144")
                    ""
                }
                "query" -> {
                    queries++
                    exchange.responseHeaders.set("X-Goog-Upload-Status", "active")
                    exchange.responseHeaders.set("X-Goog-Upload-Size-Received", received.size().toString())
                    ""
                }
                "upload", "upload, finalize" -> {
                    uploads++
                    val offset = exchange.requestHeaders.getFirst("X-Goog-Upload-Offset").toLong(); offsets += offset
                    assertEquals(received.size().toLong(), offset)
                    val chunk = exchange.requestBody.readAllBytes()
                    assertEquals(chunk.size.toString(), exchange.requestHeaders.getFirst("Content-Length"))
                    received.write(chunk)
                    if (uploads == 1) { status = 500; "fixture failed response after accepting chunk" } else "video-token"
                }
                else -> {
                    creates++
                    assertEquals("/v1/mediaItems:batchCreate", exchange.requestURI.path)
                    val item = mapper.readTree(exchange.requestBody).path("newMediaItems")[0].path("simpleMediaItem")
                    assertEquals("원본.mp4", item.path("fileName").asText()); assertEquals("video-token", item.path("uploadToken").asText())
                    """{"newMediaItemResults":[{"uploadToken":"video-token","status":{"code":0},"mediaItem":{"id":"video-google-id","mimeType":"video/mp4","mediaMetadata":{"video":{"status":"PROCESSING"}}}}]}"""
                }
            }
            val bytes = response.toByteArray(); exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        try {
            val endpoint = URI("http://127.0.0.1:${server.address.port}")
            publisher.endpoint = endpoint
            assertEquals(PublicationFailure.Kind.RETRYABLE,
                assertFailsWith<PublicationFailure> { publisher.uploadBytes(file, "connection-fixture", "video/mp4") }.kind)
            assertTrue(Files.isRegularFile(GooglePhotosResumableUpload.sessionPath(file)))
            val restarted = GooglePhotosLibraryPublisher(GooglePhotosTokenProvider(credentials(), mapper), mapper).also { it.endpoint = endpoint }
            val token = restarted.uploadBytes(file, "connection-fixture", "video/mp4")
            assertEquals("video-token", token)
            assertEquals(token, restarted.uploadBytes(file, "connection-fixture", "video/mp4"))
            assertEquals("video-google-id", restarted.createMediaItem(token, "원본.mp4", "connection-fixture").mediaItemId)
            assertContentEquals(original, received.toByteArray()); assertContentEquals(original, Files.readAllBytes(file))
            assertEquals(listOf(0L, 8 * 1024 * 1024L), offsets)
            assertEquals(1, starts); assertEquals(1, queries); assertEquals(2, uploads); assertEquals(1, creates)
        } finally { server.stop(0) }
    }

    @Test fun `video refuses upload session URLs outside the Google endpoint`() {
        val publisher = GooglePhotosLibraryPublisher(GooglePhotosTokenProvider(credentials(), mapper), mapper)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/uploads") { exchange ->
            exchange.responseHeaders.set("X-Goog-Upload-URL", "https://untrusted.invalid/upload")
            exchange.responseHeaders.set("X-Goog-Upload-Chunk-Granularity", "262144")
            exchange.sendResponseHeaders(200, -1); exchange.close()
        }
        server.start()
        try {
            publisher.endpoint = URI("http://127.0.0.1:${server.address.port}")
            val file = Files.write(temp.resolve("clip.mov"), byteArrayOf(1, 2))
            assertEquals("UPLOAD_SESSION_URL_INVALID",
                assertFailsWith<PublicationFailure> { publisher.uploadBytes(file, "connection-fixture", "video/quicktime") }.code)
            assertFalse(Files.exists(GooglePhotosResumableUpload.sessionPath(file)))
        } finally { server.stop(0) }
    }

    @Test fun `create errors distinguish unknown result from rate limit and never reveal response body`() {
        val publisher = GooglePhotosLibraryPublisher(GooglePhotosTokenProvider(credentials(), mapper), mapper)
        var status = 500
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            exchange.requestBody.readAllBytes()
            exchange.responseHeaders.set("Retry-After", "45")
            val bytes = "secret-response-fixture".toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            publisher.endpoint = URI("http://127.0.0.1:${server.address.port}")
            val file = Files.write(temp.resolve("export.jpg"), byteArrayOf(1))
            assertEquals(PublicationFailure.Kind.RETRYABLE, assertFailsWith<PublicationFailure> { publisher.uploadBytes(file, "connection-fixture") }.kind)
            val unknown = assertFailsWith<PublicationFailure> { publisher.createMediaItem("upload-fixture", "name.jpg", "connection-fixture") }
            assertEquals(PublicationFailure.Kind.UNCERTAIN, unknown.kind); assertEquals("HTTP_500", unknown.message)
            status = 429
            val limited = assertFailsWith<PublicationFailure> { publisher.createMediaItem("upload-fixture", "name.jpg", "connection-fixture") }
            assertEquals(PublicationFailure.Kind.RETRYABLE, limited.kind); assertEquals(45L, limited.retryAfterSeconds)
            status = 200
            assertEquals(PublicationFailure.Kind.UNCERTAIN, assertFailsWith<PublicationFailure> { publisher.createMediaItem("upload-fixture", "name.jpg", "connection-fixture") }.kind)
        } finally { server.stop(0) }
    }

    @Test fun `explicit album management works while publishing is disabled and never uploads JPEG bytes`() {
        val props = credentials(); props.googlePhotos = props.googlePhotos.copy(enabled = false)
        val publisher = GooglePhotosLibraryPublisher(GooglePhotosTokenProvider(props, mapper), mapper)
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { exchange ->
            assertNull(exchange.requestHeaders.getFirst("X-Goog-Upload-Protocol"))
            assertEquals("Bearer access-fixture", exchange.requestHeaders.getFirst("Authorization"))
            val payload = mapper.readTree(exchange.requestBody)
            val result = when (exchange.requestURI.path) {
                "/v1/albums" -> { assertEquals(GooglePhotosPublicationAlbum.TITLE, payload.path("album").path("title").asText()); """{"id":"album-fixture","productUrl":"https://photos.google.com/album/fixture"}""" }
                "/v1/albums/album-fixture:batchAddMediaItems" -> { assertEquals(listOf("existing-id"), payload.path("mediaItemIds").map { it.asText() }); "" }
                else -> error("unexpected photo upload")
            }
            count.incrementAndGet()
            val bytes = result.toByteArray(); exchange.sendResponseHeaders(200, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        server.start()
        try {
            publisher.endpoint = URI("http://127.0.0.1:${server.address.port}")
            val connection = publisher.albumConnectionId()
            val album = publisher.createAlbum(GooglePhotosPublicationAlbum.TITLE, connection)
            publisher.addToAlbum(album.id, listOf("existing-id"), connection)
            assertEquals("PUBLICATION_DISABLED", assertFailsWith<PublicationFailure> { publisher.connectionId() }.code)
            val file = Files.write(temp.resolve("never-upload.jpg"), byteArrayOf(1))
            assertEquals("PUBLICATION_DISABLED", assertFailsWith<PublicationFailure> { publisher.uploadBytes(file, connection) }.code)
            assertEquals(2, count.get())
        } finally { server.stop(0) }
    }

    @Test fun `concurrent expired tokens refresh once and persist rotated access without losing refresh token`() {
        val props = credentials(true)
        val provider = GooglePhotosTokenProvider(props, mapper)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            assertContains(body, "refresh_token=refresh-fixture%26plus")
            assertContains(body, "client_secret=secret-fixture")
            requests.incrementAndGet()
            val bytes = """{"access_token":"access-renewed","expires_in":3600}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val executor = Executors.newFixedThreadPool(8)
        try {
            provider.tokenEndpoint = URI("http://127.0.0.1:${server.address.port}/token")
            val futures = (1..8).map { executor.submit<String> { provider.accessToken("connection-fixture") } }
            assertTrue(futures.all { it.get() == "access-renewed" }); assertEquals(1, requests.get())
            val saved = mapper.readTree(Path.of(props.googlePhotos.tokenFile).toFile())
            assertEquals("refresh-fixture&plus", saved.path("refreshToken").asText())
            assertEquals("connection-fixture", saved.path("connectionId").asText())
            assertTrue(Instant.parse(saved.path("expiresAt").asText()).isAfter(Instant.now()))
        } finally { executor.shutdownNow(); server.stop(0) }
    }

    @Test fun `disabled credentials and connection changes fail before Google requests without secret leakage`() {
        val disabled = GooglePhotosTokenProvider(AppProperties(temp.resolve("data"), "test"), mapper)
        assertEquals("PUBLICATION_DISABLED", assertFailsWith<PublicationFailure> { disabled.connectionId() }.code)
        val props = credentials()
        val provider = GooglePhotosTokenProvider(props, mapper)
        assertEquals("CONNECTION_CHANGED", assertFailsWith<PublicationFailure> { provider.accessToken("other") }.code)
        Files.writeString(Path.of(props.googlePhotos.tokenFile), "secret-invalid-json")
        val error = assertFailsWith<PublicationFailure> { provider.connectionId() }
        assertEquals("CREDENTIALS_UNREADABLE", error.code); assertFalse(error.message!!.contains("secret-invalid"))
    }

    @Test fun `revoked refresh credentials require user reconnection and keep existing private file`() {
        val props = credentials(true)
        val provider = GooglePhotosTokenProvider(props, mapper)
        val before = Files.readString(Path.of(props.googlePhotos.tokenFile))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = """{"error":"invalid_grant","error_description":"secret-fixture"}""".toByteArray()
            exchange.sendResponseHeaders(400, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            provider.tokenEndpoint = URI("http://127.0.0.1:${server.address.port}/token")
            val error = assertFailsWith<PublicationFailure> { provider.accessToken("connection-fixture") }
            assertEquals(PublicationFailure.Kind.AUTH, error.kind); assertEquals("TOKEN_HTTP_400", error.code)
            assertEquals(before, Files.readString(Path.of(props.googlePhotos.tokenFile)))
        } finally { server.stop(0) }
    }
}
