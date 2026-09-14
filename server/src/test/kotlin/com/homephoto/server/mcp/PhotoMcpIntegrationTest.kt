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
                    origin: String? = null, forwarded: Boolean = false,
                    version: String? = "2025-06-18", requestId: Any = 1): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/mcp"))
            .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
        version?.let { request.header("MCP-Protocol-Version", it) }
        token?.let { request.header("Authorization", "Bearer $it") }
        origin?.let { request.header("Origin", it) }
        if (forwarded) request.header("X-Forwarded-For", "203.0.113.1")
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(
            mapOf("jsonrpc" to "2.0", "id" to requestId, "method" to method, "params" to params),
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

    @Test fun `discover probe returns method not found so legacy initialization can proceed`() {
        for (version in listOf(null, "2025-06-18", "2026-07-28")) {
        val probe = rpc("server/discover", mapOf("_meta" to mapOf(
            "io.modelcontextprotocol/protocolVersion" to "2026-07-28",
            "io.modelcontextprotocol/clientInfo" to mapOf("name" to "probe", "version" to "1"),
            "io.modelcontextprotocol/clientCapabilities" to emptyMap<String, Any>())), version = version, requestId = "discover-1")
        assertEquals(404, probe.statusCode(), probe.body())
        assertEquals(-32601, mapper.readTree(probe.body())["error"]["code"].asInt())
        assertEquals("discover-1", mapper.readTree(probe.body())["id"].asText())
        }
        assertEquals(200, rpc("initialize", mapOf("protocolVersion" to "2025-06-18",
            "capabilities" to emptyMap<String, Any>(), "clientInfo" to mapOf("name" to "probe", "version" to "1"))).statusCode())
        assertEquals(200, rpc("tools/list").statusCode())
    }

    @Test fun `discovery compatibility does not bypass authentication origin or proxy checks`() {
        assertEquals(401, rpc("server/discover", token = null).statusCode())
        assertEquals(401, rpc("server/discover", token = "invalid").statusCode())
        assertEquals(403, rpc("server/discover", origin = "https://evil.example").statusCode())
        assertEquals(403, rpc("server/discover", forwarded = true).statusCode())
    }

    @Test fun `probe notifications malformed JSON and oversized bodies have bounded responses`() {
        fun post(body: String) = http.send(HttpRequest.newBuilder(URI("http://localhost:$port/mcp"))
            .header("Authorization", "Bearer $TEST_TOKEN").header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString())
        val notification = post("""{"jsonrpc":"2.0","method":"server/discover"}""")
        assertEquals(202, notification.statusCode()); assertTrue(notification.body().isEmpty())
        val malformed = post("{not-json")
        assertEquals(400, malformed.statusCode())
        assertEquals(-32700, mapper.readTree(malformed.body())["error"]["code"].asInt())
        val invalidId = post("""{"jsonrpc":"2.0","id":{},"method":"server/discover"}""")
        assertEquals(400, invalidId.statusCode())
        assertEquals(-32600, mapper.readTree(invalidId.body())["error"]["code"].asInt())
        assertEquals(413, post(" ".repeat(65537)).statusCode())
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

    @Test fun `range search includes both boundary days and paginates without duplicates`() {
        transaction(db) {
            insert(20, "2025-11-04T23:59:59.999999999")
            insert(21, "2025-11-05T00:00:00")
        }
        val dates = mapOf("start_date" to "2025-11-03", "end_date" to "2025-11-04")
        val first = call("search_photos", dates + ("limit" to 12))["structuredContent"]
        assertEquals("2025-11-03", first["start_date"].asText())
        assertEquals("2025-11-04", first["end_date"].asText())
        assertTrue(first["date"].isNull)
        val second = call("search_photos", dates + ("cursor" to first["nextCursor"].asText()))["structuredContent"]
        val ids = (first["items"].toList() + second["items"].toList()).map { it["id"].asLong() }
        assertEquals(listOf(20L, 16L) + (14L downTo 1L).toList(), ids)
        assertTrue(second["nextCursor"].isNull)
        val day = call("search_photos", mapOf("start_date" to "2025-11-03", "end_date" to "2025-11-03", "limit" to 24))["structuredContent"]
        assertEquals(14, day["items"].size())
    }

    @Test fun `range search handles leap month year boundary and empty results`() {
        transaction(db) {
            insert(20, "2024-02-29T23:59:59.999")
            insert(21, "2024-03-01T00:00:00")
            insert(22, "2024-12-31T23:59:59")
            insert(23, "2025-01-01T00:00:00")
        }
        fun ids(start: String, end: String) = call("search_photos",
            mapOf("start_date" to start, "end_date" to end))["structuredContent"]["items"].map { it["id"].asLong() }
        assertEquals(listOf(20L), ids("2024-02-01", "2024-02-29"))
        assertEquals(listOf(23L, 22L), ids("2024-12-31", "2025-01-01"))
        assertTrue(ids("2024-09-01", "2024-09-30").isEmpty())
    }

    @Test fun `invalid or conflicting range arguments are tool errors`() {
        for (args in listOf(emptyMap(), mapOf("start_date" to "2025-11-03"),
            mapOf("end_date" to "2025-11-04"),
            mapOf("date" to "2025-11-03", "start_date" to "2025-11-03", "end_date" to "2025-11-04"),
            mapOf("start_date" to "2025-11-04", "end_date" to "2025-11-03"),
            mapOf("start_date" to "2025-02-01", "end_date" to "2025-02-29"),
            mapOf("start_date" to 20251103, "end_date" to "2025-11-04"),
            mapOf("start_date" to "2025-11-03", "end_date" to "2025-11-04", "cursor" to "2025-11-05T00:00:00~21"))) {
            assertTrue(call("search_photos", args)["isError"].asBoolean(), args.toString())
        }
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
