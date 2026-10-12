package com.homephoto.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.*
import com.homephoto.server.db.*
import com.sun.net.httpserver.HttpServer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.imageio.ImageIO
import kotlin.test.*

class GeminiCaptionBatchTest {
    @TempDir lateinit var dir: Path
    private lateinit var db: Database
    private lateinit var server: HttpServer
    private lateinit var props: AppProperties
    private lateinit var client: GeminiCaptionClient
    private lateinit var image: Path
    private val queue = JobQueueService()
    private val mapper = ObjectMapper()
    private var now = 1_000_000L
    private var posts = 0
    private var gets = 0
    private var postCode = 200
    private var getCode = 200
    private var postBody = """{"name":"batches/test-batch"}"""
    private var getBody = """{"done":false,"metadata":{"state":"BATCH_STATE_PENDING"}}"""
    private var sent = ""
    private var key = ""
    private var requestPath = ""
    private var afterBatchRead: (() -> Unit)? = null
    private lateinit var jdbcUrl: String

    @BeforeEach fun setup() {
        jdbcUrl = "jdbc:sqlite:${dir.resolve("batch.db")}?foreign_keys=on&journal_mode=WAL&busy_timeout=100"
        db = Database.connect(getNewConnection = {
            val connection = DriverManager.getConnection(jdbcUrl)
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                try {
                    val value = method.invoke(connection, *(args ?: emptyArray()))
                    val sql = args?.firstOrNull() as? String ?: ""
                    if (method.name == "prepareStatement" && sql.startsWith("SELECT") && sql.contains("FROM caption_batch_items") && !sql.contains("COUNT(")) {
                        Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, queryMethod, queryArgs ->
                            try {
                                queryMethod.invoke(value, *(queryArgs ?: emptyArray())).also {
                                    if (queryMethod.name == "executeQuery") afterBatchRead?.invoke()
                                }
                            } catch (e: InvocationTargetException) { throw e.targetException }
                        }
                    } else value
                } catch (e: InvocationTargetException) { throw e.targetException }
            } as Connection
        })
        DatabaseMigrations().migrate()
        props = AppProperties(dir, "test", caption = AppProperties.CaptionProperties(enabled = true, provider = "GEMINI",
            geminiApiKeyFile = Files.writeString(dir.resolve("key"), "test-secret").toString()))
        image = dir.resolve("image.jpg")
        val bitmap = BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(bitmap, "jpg", image.toFile()); bitmap.flush()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val post = exchange.requestMethod == "POST"
            if (post) { posts++; sent = exchange.requestBody.reader().readText(); key = exchange.requestHeaders.getFirst("x-goog-api-key"); requestPath = exchange.requestURI.path }
            else gets++
            val body = (if (post) postBody else getBody).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Retry-After", "120")
            exchange.sendResponseHeaders(if (post) postCode else getCode, body.size.toLong())
            exchange.responseBody.use { it.write(body) }; exchange.close()
        }
        server.start()
        client = GeminiCaptionClient("http://127.0.0.1:${server.address.port}/v1beta")
    }
    @AfterEach fun cleanup() { server.stop(0); TransactionManager.closeAndUnregister(db) }
    private fun batch() = GeminiCaptionBatch(props, queue, client) { now }
    private fun add(name: String): Long = transaction {
        val asset = Assets.insert {
            it[hash] = name; it[mediaType] = "PHOTO"; it[originalPath] = name; it[originalFilename] = name
            it[fileSize] = 1; it[takenAtSource] = "UPLOAD_TIME"; it[yearMonth] = "2026-10"; it[createdAt] = "2026-10-13T00:00:00"
        }[Assets.id]
        Jobs.insert { it[assetId] = asset; it[jobType] = "CAPTION"; it[updatedAt] = "2026-10-13T00:00:00" }[Jobs.id]
    }
    private fun status(id: Long) = transaction { Jobs.selectAll().where { Jobs.id eq id }.single()[Jobs.status] }
    private fun output(id: Long, caption: String) = mapOf("metadata" to mapOf("key" to id.toString()), "response" to
        mapOf("candidates" to listOf(mapOf("finishReason" to "STOP", "content" to mapOf("parts" to listOf(mapOf("text" to
            mapper.writeValueAsString(mapOf("caption" to caption, "tags" to listOf("공원"), "documentClassification" to "NOT_DOCUMENT")))))))))
    private fun results(vararg entries: Any) {
        getBody = mapper.writeValueAsString(mapOf("done" to true, "response" to mapOf("inlinedResponses" to mapOf("inlinedResponses" to entries))))
        now += GeminiCaptionBatch.POLL_MS
    }

    @Test fun `batch survives worker finish and restart and matches reversed partial responses by key`() {
        val first = add("first"); val second = add("second"); val failed = add("failed")
        batch().submit({ image }) { true }
        queue.finishWorker("CAPTION")
        StartupJobRecovery().recover()
        assertEquals("RUNNING", status(first)); assertEquals(3L, batch().waiting())
        assertEquals(1, posts); assertEquals("test-secret", key); assertFalse(sent.contains("test-secret"))
        assertTrue(requestPath.endsWith(":batchGenerateContent"))
        val entries = mapper.readTree(sent).path("batch").path("inputConfig").path("requests").path("requests")
        assertEquals(3, entries.size())
        val request = entries[0].path("request")
        assertEquals("MEDIA_RESOLUTION_MEDIUM", request.path("generationConfig").path("mediaResolution").asText())
        val jpeg = java.util.Base64.getDecoder().decode(request.path("contents")[0].path("parts")[1].path("inlineData").path("data").asText())
        val transmitted = ImageIO.read(jpeg.inputStream()); assertEquals(768, transmitted.width); transmitted.flush()
        batch().poll { _, _ -> fail("not ready") }; assertEquals(0, gets)
        now += GeminiCaptionBatch.POLL_MS
        batch().poll { _, _ -> fail("pending") }; assertEquals(1, gets)
        batch().poll { _, _ -> fail("poll interval") }; assertEquals(1, gets)
        props.caption = props.caption.copy(provider = "LOCAL", geminiModel = "different")
        results(output(second, "두 번째"), mapOf("metadata" to mapOf("key" to failed.toString()), "error" to mapOf("message" to "test-secret")), output(first, "첫 번째"))
        val saved = mutableMapOf<Long, CaptionService.CaptionResult>()
        batch().poll { id, result -> saved[id] = result }
        assertEquals(listOf("첫 번째", "두 번째"), saved.toSortedMap().values.map { it.caption })
        assertTrue(saved.values.all { it.model == "gemini:gemini-2.5-flash" })
        assertEquals("DONE", status(first)); assertEquals("DONE", status(second)); assertEquals("FAILED", status(failed))
        assertEquals(0L, batch().waiting()); assertEquals(1, posts)
        transaction { assertFalse(Jobs.selectAll().where { Jobs.id eq failed }.single()[Jobs.lastError]!!.contains("test-secret")) }
    }

    @Test fun `quota rejection releases without attempts but ambiguous submission requires explicit retry`() {
        val id = add("one")
        postCode = 429; postBody = "test-secret"
        assertFailsWith<CaptionUnavailableException> { batch().submit({ image }) { true } }
        queue.finishWorker("CAPTION")
        assertEquals("PENDING", status(id)); assertEquals(0L, batch().waiting())
        transaction { assertEquals(0, Jobs.selectAll().single()[Jobs.attempts]) }
        postCode = 200; postBody = "broken test-secret"
        val error = assertFailsWith<CaptionUnavailableException> { batch().submit({ image }) { true } }
        assertFalse(error.message!!.contains("test-secret")); assertEquals("FAILED", status(id))
        batch().submit({ image }) { true }; assertEquals(2, posts)
    }

    @Test fun `poll outage does not resubmit and deleted photos never receive results`() {
        val deleted = add("deleted"); val good = add("good")
        batch().submit({ image }) { true }
        now += GeminiCaptionBatch.POLL_MS; getCode = 503
        assertFailsWith<CaptionUnavailableException> { batch().poll { _, _ -> fail("outage") } }
        assertEquals("RUNNING", status(good)); assertEquals(1, posts)
        transaction { Assets.update({ Assets.id eq deleted }) { it[deletedAt] = "2026-10-13T00:00:00" } }
        getCode = 200; results(output(deleted, "삭제됨"), output(good, "보존"))
        val saved = mutableListOf<Long>(); batch().poll { id, _ -> saved.add(id) }
        assertEquals(listOf(good), saved); assertEquals(1, posts)
        assertEquals("PENDING", status(deleted)); assertEquals("DONE", status(good))
    }

    @Test fun `unacknowledged persisted submission is failed on restart and ordinary running job recovers`() {
        val unknown = add("unknown"); val ordinary = add("ordinary")
        transaction {
            Jobs.update { it[status] = "RUNNING" }
            CaptionBatches.insert { it[id] = "unknown"; it[model] = "model"; it[keyFile] = ""; it[nextPoll] = now }
            CaptionBatchItems.insert { it[jobId] = unknown; it[batchId] = "unknown" }
        }
        StartupJobRecovery().recover()
        assertEquals("FAILED", status(unknown)); assertEquals("PENDING", status(ordinary)); assertEquals(0L, batch().waiting())
    }

    @Test fun `result transaction rollback preserves remote batch for another poll`() {
        val id = add("one"); batch().submit({ image }) { true }; results(output(id, "공원"))
        assertFailsWith<IllegalStateException> { batch().poll { _, _ -> error("db unavailable") } }
        assertEquals("RUNNING", status(id)); assertEquals(1L, batch().waiting())
        now += GeminiCaptionBatch.POLL_MS
        batch().poll { _, _ -> }; assertEquals("DONE", status(id)); assertEquals(1, posts)
    }

    @Test fun `batch cap and pause leave unsubmitted jobs pending`() {
        repeat(21) { add("photo-$it") }
        batch().submit({ image }) { false }; assertEquals(0, posts)
        batch().submit({ image }) { true }; queue.finishWorker("CAPTION")
        assertEquals(20L, batch().waiting())
        transaction { assertEquals(1L, Jobs.selectAll().where { Jobs.status eq "PENDING" }.count()) }
    }

    @Test fun `result save prevents snapshot upgrade conflict with another SQLite writer`() {
        val id = add("contended")
        batch().submit({ image }) { true }
        results(output(id, "공원"))
        var competingWrites = 0
        var competingWaits = 0
        afterBatchRead = {
            DriverManager.getConnection(jdbcUrl).use { other ->
                try {
                    other.createStatement().use { it.executeUpdate("UPDATE assets SET file_size=file_size+1 WHERE id=$id") }
                    competingWrites++
                } catch (e: SQLException) {
                    if (e.errorCode and 255 != 5) throw e
                    competingWaits++
                }
            }
        }
        try {
            batch().poll { assetId, result -> Captions.insert {
                it[Captions.assetId] = assetId; it[caption] = result.caption; it[createdAt] = "2026-10-13T00:00:00"
            } }
        } finally { afterBatchRead = null }
        assertEquals(0, competingWrites)
        assertEquals(1, competingWaits)
        assertEquals("DONE", status(id)); assertEquals(0L, batch().waiting()); assertEquals(1, posts)
        transaction { assertEquals("공원", Captions.selectAll().single()[Captions.caption]) }
        // 저장 커밋 이후에는 다른 연결의 쓰기도 정상적으로 진행된다.
        DriverManager.getConnection(jdbcUrl).use { other ->
            other.createStatement().use { assertEquals(1, it.executeUpdate("UPDATE assets SET file_size=file_size+1 WHERE id=$id")) }
        }
    }

    @Test fun `terminal batch failure remains atomic under another SQLite writer`() {
        val id = add("failed-contended")
        batch().submit({ image }) { true }
        getBody = """{"done":true,"error":{"code":13},"metadata":{"state":"BATCH_STATE_FAILED"}}"""
        now += GeminiCaptionBatch.POLL_MS
        var waits = 0
        afterBatchRead = {
            DriverManager.getConnection(jdbcUrl).use { other ->
                val error = assertFailsWith<SQLException> {
                    other.createStatement().use { it.executeUpdate("UPDATE assets SET file_size=file_size+1 WHERE id=$id") }
                }
                assertEquals(5, error.errorCode and 255)
                waits++
            }
        }
        try { batch().poll { _, _ -> fail("failed batch must not save captions") } }
        finally { afterBatchRead = null }
        assertEquals(1, waits); assertEquals("FAILED", status(id)); assertEquals(0L, batch().waiting())
        assertEquals(1, posts)
        transaction { assertEquals(1, Jobs.selectAll().where { Jobs.id eq id }.single()[Jobs.attempts]) }
    }
}
