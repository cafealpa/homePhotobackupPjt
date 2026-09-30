package com.homephoto.server.publication

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class GooglePhotosPocClientTest {
    @TempDir lateinit var temp: Path

    @Test fun `raw uploads and single batch preserve filenames without description and match shuffled results`() {
        val mapper = jacksonObjectMapper()
        val files = GooglePhotosMetadataPoc.samples.map { sample ->
            Files.write(temp.resolve("${sample.id}.jpg"), sample.id.toByteArray()) to sample.fileName
        }
        val calls = AtomicInteger()
        val uploads = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/") { request ->
            try {
                assertEquals("POST", request.requestMethod)
                assertEquals("Bearer test-only", request.requestHeaders.getFirst("Authorization"))
                val body: String
                if (request.requestURI.path == "/v1/uploads") {
                    assertEquals("raw", request.requestHeaders.getFirst("X-Goog-Upload-Protocol"))
                    assertEquals("image/jpeg", request.requestHeaders.getFirst("X-Goog-Upload-Content-Type"))
                    assertEquals("application/octet-stream", request.requestHeaders.getFirst("Content-Type"))
                    val index = uploads.getAndIncrement()
                    assertContentEquals(Files.readAllBytes(files[index].first), request.requestBody.readAllBytes())
                    body = "token-$index"
                } else {
                    assertEquals("/v1/mediaItems:batchCreate", request.requestURI.path)
                    assertEquals(5, uploads.get())
                    val json = mapper.readTree(request.requestBody)
                    assertEquals(setOf("newMediaItems"), json.fieldNames().asSequence().toSet())
                    val rows = json.path("newMediaItems").toList()
                    assertEquals(5, rows.size)
                    rows.forEachIndexed { i, row ->
                        assertEquals(setOf("simpleMediaItem"), row.fieldNames().asSequence().toSet())
                        assertEquals(files[i].second, row.path("simpleMediaItem").path("fileName").asText())
                        assertEquals("token-$i", row.path("simpleMediaItem").path("uploadToken").asText())
                    }
                    body = mapper.writeValueAsString(mapOf("newMediaItemResults" to rows.mapIndexed { i, _ ->
                        if (i == 2) mapOf("uploadToken" to "token-$i", "status" to mapOf("code" to 3)) else
                            mapOf("uploadToken" to "token-$i", "status" to mapOf("code" to 0), "mediaItem" to
                                mapOf("id" to "media-$i", "productUrl" to "https://photos.google.com/photo/test-$i",
                                    "filename" to files[i].second, "mediaMetadata" to mapOf("creationTime" to "2020-01-01T00:00:00Z", "width" to "1600", "height" to "1000")))
                    }.reversed()))
                }
                calls.incrementAndGet()
                val bytes = body.toByteArray()
                request.sendResponseHeaders(200, bytes.size.toLong())
                request.responseBody.use { it.write(bytes) }
            } catch (error: Throwable) {
                request.close(); throw error
            }
        }
        server.start()
        try {
            val result = GooglePhotosPocClient("test-only", URI("http://127.0.0.1:${server.address.port}")).publish(files)
            assertEquals(6, calls.get())
            assertEquals("media-0", result[0]["mediaItemId"])
            assertEquals(files[4].second, result[4]["fileName"])
            assertEquals(3, result[2]["statusCode"]); assertNull(result[2]["mediaItemId"])
            assertFalse(mapper.writeValueAsString(result).contains("token-"))
        } finally { server.stop(0) }
    }

    @Test fun `HTTP failure is not retried or exposed as a secret bearing response`() {
        val file = Files.write(temp.resolve("test.jpg"), byteArrayOf(1))
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/uploads") { request ->
            calls.incrementAndGet()
            request.requestBody.readAllBytes()
            val body = "sensitive-response-test-only".toByteArray()
            request.sendResponseHeaders(500, body.size.toLong())
            request.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val error = assertFailsWith<IllegalStateException> {
                GooglePhotosPocClient("test-only", URI("http://127.0.0.1:${server.address.port}")).publish(List(3) { file to "test.jpg" })
            }
            assertEquals(1, calls.get()); assertContains(error.message!!, "HTTP 500")
            assertFalse(error.message!!.contains("sensitive-response"))
        } finally { server.stop(0) }
    }
}
