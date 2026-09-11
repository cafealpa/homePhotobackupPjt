package com.homephoto.server.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.db.Assets
import com.homephoto.server.service.AssetQueryService
import com.homephoto.server.service.ThumbnailService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

@SpringBootTest(classes = [McpTestApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["homephoto.mcp.enabled=true", "homephoto.mcp.token=$TEST_TOKEN", "logging.file.name="])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoMcpIntegrationTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var thumbnails: ThumbnailService
    private val http = HttpClient.newHttpClient()
    private val directory = Files.createTempDirectory("homephoto-mcp-test-")
    private lateinit var db: Database
    private lateinit var jpeg: java.nio.file.Path

    @BeforeAll fun setup() {
        db = Database.connect("jdbc:sqlite:${directory.resolve("test.db")}", driver = "org.sqlite.JDBC")
        transaction(db) { SchemaUtils.create(Assets) }
        jpeg = directory.resolve("preview.jpg")
        ImageIO.write(BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpg", jpeg.toFile())
        Mockito.`when`(thumbnails.thumbPath(Mockito.anyString(), Mockito.anyInt())).thenReturn(jpeg)
    }

    @BeforeEach fun seed() {
        transaction(db) {
            Assets.deleteAll()
            for (i in 1..14) insert(i.toLong(), if (i <= 2) "2025-11-03T00:00:00" else "2025-11-03T12:00:00")
            insert(15, "2025-11-02T23:59:59")
            insert(16, "2025-11-04T00:00:00")
            insert(17, "2025-11-03T13:00:00", media = "VIDEO")
            insert(18, "2025-11-03T13:00:00", deleted = true)
            insert(19, "2025-11-03T13:00:00", kidsnote = true)
        }
    }

    private fun insert(assetId: Long, taken: String, media: String = "PHOTO", deleted: Boolean = false, kidsnote: Boolean = false) {
        Assets.insert {
            it[id] = assetId; it[hash] = "hash-$assetId"; it[mediaType] = media
            it[originalPath] = "private/path.jpg"; it[originalFilename] = "private-name.jpg"
            it[fileSize] = 10; it[takenAt] = taken; it[takenAtSource] = "FILENAME"
            it[yearMonth] = taken.take(7); it[createdAt] = taken
            it[deletedAt] = if (deleted) taken else null; it[sourceTag] = if (kidsnote) "KIDSNOTE" else null
        }
    }

    private fun rpc(method: String, params: Any = emptyMap<String, Any>(), token: String? = TEST_TOKEN,
                    origin: String? = null, forwarded: Boolean = false): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/mcp"))
            .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", "2025-06-18")
        token?.let { request.header("Authorization", "Bearer $it") }
        origin?.let { request.header("Origin", it) }
        if (forwarded) request.header("X-Forwarded-For", "203.0.113.1")
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
            mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params),
        ))).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun call(name: String, args: Map<String, Any>): JsonNode {
        val response = rpc("tools/call", mapOf("name" to name, "arguments" to args))
        assertEquals(200, response.statusCode(), response.body())
        val body = mapper.readTree(response.body())
        assertFalse(body.has("error"), response.body())
        return body["result"]
    }

    private fun preview(url: String): HttpResponse<ByteArray> {
        val uri = URI(url)
        return http.send(HttpRequest.newBuilder(URI("http://localhost:$port${uri.rawPath}?${uri.rawQuery}")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test fun `MCP initializes lists two read-only tools and serves gallery resource`() {
        val init = rpc("initialize", mapOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "test", "version" to "1")))
        assertEquals(200, init.statusCode(), init.body())
        assertEquals("homephoto", mapper.readTree(init.body())["result"]["serverInfo"]["name"].asText())
        val tools = mapper.readTree(rpc("tools/list").body())["result"]["tools"]
        assertEquals(setOf("search_photos", "get_photo"), tools.map { it["name"].asText() }.toSet())
        assertTrue(tools.all { it["annotations"]["readOnlyHint"].asBoolean() })
        val resource = mapper.readTree(rpc("resources/read", mapOf("uri" to PhotoMcpTools.GALLERY_URI)).body())["result"]["contents"][0]
        assertEquals("text/html;profile=mcp-app", resource["mimeType"].asText())
        assertTrue(resource["text"].asText().contains("ui/initialize"))
    }

    @Test fun `date search excludes adjacent days video trash and kidsnote and paginates ties`() {
        val first = call("search_photos", mapOf("date" to "2025-11-03", "limit" to 12))
        val data = first["structuredContent"]
        assertEquals((14L downTo 3L).toList(), data["items"].map { it["id"].asLong() })
        val next = call("search_photos", mapOf("date" to "2025-11-03", "cursor" to data["nextCursor"].asText()))["structuredContent"]
        assertEquals(listOf(2L, 1L), next["items"].map { it["id"].asLong() })
        assertTrue(next["nextCursor"].isNull)
        assertFalse(data.toString().contains("private"))
        assertFalse(data.toString().contains("signature"))
        val empty = call("search_photos", mapOf("date" to "2024-11-03"))["structuredContent"]
        assertEquals(0, empty["items"].size()); assertTrue(empty["nextCursor"].isNull)
        val exact = call("search_photos", mapOf("date" to "2025-11-03", "limit" to 14))["structuredContent"]
        assertTrue(exact["nextCursor"].isNull)
    }

    @Test fun `invalid calendar date omitted year invalid limits and mismatched cursors are tool errors`() {
        for (args in listOf(mapOf("date" to "11-03"), mapOf("date" to "2025-02-29"),
            mapOf("date" to "2025-11-03", "limit" to 0), mapOf("date" to "2025-11-03", "limit" to 25),
            mapOf("date" to "2025-11-03", "limit" to 1.5),
            mapOf("date" to "2025-11-03", "cursor" to "2025-11-02T00:00:00~1"),
            mapOf("date" to "2025-11-03", "cursor" to "invalid"),
            mapOf("date" to "2025-11-03", "sql" to "SELECT * FROM assets"))) {
            assertTrue(call("search_photos", args)["isError"].asBoolean(), args.toString())
        }
        for (id in listOf(17,18,19,999)) assertTrue(call("get_photo", mapOf("photo_id" to id))["isError"].asBoolean())
    }

    @Test fun `MCP requires its own token and rejects browser origins and forwarded requests`() {
        assertEquals(401, rpc("tools/list", token = null).statusCode())
        assertEquals(401, rpc("tools/list", token = "dev-key-change-me").statusCode())
        assertEquals(403, rpc("tools/list", origin = "https://untrusted.example").statusCode())
        assertEquals(403, rpc("tools/list", forwarded = true).statusCode())
    }

    @Test fun `signed preview works and rejects tampering expiry and deletion after issuance`() {
        val result = call("get_photo", mapOf("photo_id" to 1))
        val url = result["_meta"]["previews"]["1"]["thumbnailUrl"].asText()
        val response = preview(url)
        assertEquals(200, response.statusCode())
        assertEquals("image/jpeg", response.headers().firstValue("Content-Type").orElse(""))
        assertArrayEquals(Files.readAllBytes(jpeg), response.body())
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"))
        assertEquals(403, preview(url.replace("/1?", "/2?")).statusCode())
        assertEquals(403, preview(url.replace("size=400", "size=1600")).statusCode())
        assertEquals(403, preview(url.replace(Regex("expires=\\d+"), "expires=1")).statusCode())
        transaction(db) { Assets.update({ Assets.id eq 1 }) { it[deletedAt] = "2026-09-11T12:00:00" } }
        assertEquals(404, preview(url).statusCode())
        assertTrue(call("get_photo", mapOf("photo_id" to 1))["isError"].asBoolean())
    }

    @Test fun `correctly signed but expired URL is rejected and missing thumbnail returns not found`() {
        val past = Clock.fixed(Instant.now().minusSeconds(1000), ZoneOffset.UTC)
        val service = PhotoPreviewService(PhotoMcpProperties(true, TEST_TOKEN), thumbnails, past)
        assertEquals(403, preview(service.urls(1)["thumbnailUrl"] as String).statusCode())
        Mockito.`when`(thumbnails.thumbPath("hash-1", 400)).thenReturn(directory.resolve("missing.jpg"))
        try {
            val url = call("get_photo", mapOf("photo_id" to 1))["_meta"]["previews"]["1"]["thumbnailUrl"].asText()
            assertEquals(404, preview(url).statusCode())
        } finally { Mockito.`when`(thumbnails.thumbPath("hash-1", 400)).thenReturn(jpeg) }
    }

    @AfterAll fun cleanup() {
        TransactionManager.closeAndUnregister(db)
        Files.walk(directory).use { files -> files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

const val TEST_TOKEN = "local-test-token-0123456789-abcdef-not-a-real-secret"

@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = ["org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.jetbrains.exposed.spring.autoconfigure.ExposedAutoConfiguration"])
@Import(PhotoMcpConfiguration::class, PhotoPreviewController::class, AssetQueryService::class)
class McpTestApplication {
    @Bean fun thumbnails(): ThumbnailService = Mockito.mock(ThumbnailService::class.java)
}
