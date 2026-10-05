package com.homephoto.server

import com.homephoto.server.api.CaptionController
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.*
import com.homephoto.server.service.*
import com.homephoto.server.worker.CaptionWorker
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

class CaptionFlowTest {
    @TempDir lateinit var dir: Path
    private lateinit var ds: HikariDataSource
    private lateinit var db: Database
    private lateinit var worker: CaptionWorker
    private lateinit var controller: CaptionController
    private lateinit var captions: CaptionService
    private lateinit var props: AppProperties
    private lateinit var thumb: Path
    private val queue = JobQueueService()
    @BeforeEach fun setup() {
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${dir.resolve("test.db")}?journal_mode=WAL&busy_timeout=5000"
            maximumPoolSize = 4
        })
        db = Database.connect(ds)
        transaction { SchemaUtils.create(Assets, Jobs, Captions) }
        props = AppProperties(dir, "test", caption = AppProperties.CaptionProperties(enabled = true))
        captions = mock(CaptionService::class.java)
        val thumbs = mock(ThumbnailService::class.java)
        thumb = Files.write(dir.resolve("thumb.jpg"), byteArrayOf(1))
        `when`(thumbs.thumbPath(anyString(), anyInt())).thenReturn(thumb)
        worker = CaptionWorker(props, captions, thumbs, queue)
        controller = CaptionController(worker)
    }
    @AfterEach fun cleanup() {
        props.caption = props.caption.copy(enabled = false)
        worker.close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker.status().running && System.nanoTime() < deadline) Thread.sleep(10)
        TransactionManager.closeAndUnregister(db)
        ds.close()
    }
    private fun asset(name: String, type: String = "PHOTO", deleted: Boolean = false): Long = transaction {
        Assets.insert {
            it[hash] = name; it[mediaType] = type; it[originalPath] = name; it[originalFilename] = name
            it[fileSize] = 1; it[takenAtSource] = "UPLOAD_TIME"; it[yearMonth] = "2026-10"; it[createdAt] = "2026-10-05T00:00:00"
            if (deleted) it[deletedAt] = "2026-10-05T00:00:00"
        }[Assets.id]
    }
    @Test fun `first caption is queryable while second image is still being analyzed and pause keeps pending`() {
        asset("one.jpg"); asset("two.jpg"); asset("three.jpg")
        controller.enqueue(CaptionController.EnqueueRequest())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var calls = 0
        `when`(captions.analyze(thumb)).thenAnswer {
            calls++
            if (calls == 2) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            CaptionService.CaptionResult("공원에서 놀이", "공원,놀이", "gemini:test")
        }
        worker.tick()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val live = controller.list("completed", "공원", 0)
            assertEquals(1L, live.total)
            assertEquals("공원에서 놀이", live.items.single().caption)
            assertEquals("gemini:test", live.items.single().model)
            props.caption = props.caption.copy(enabled = false)
        } finally { release.countDown() }
        awaitIdle()
        assertEquals(2L, controller.list("completed", "", 0).total)
        assertEquals(1L, controller.list("pending", "", 0).total)
    }
    @Test fun `unavailable provider returns job to queue without consuming attempts`() {
        asset("one.jpg")
        controller.enqueue(CaptionController.EnqueueRequest())
        `when`(captions.analyze(thumb)).thenThrow(CaptionUnavailableException("quota", retrySeconds = 120))
        worker.tick(); awaitIdle()
        transaction { val job = Jobs.selectAll().single(); assertEquals("PENDING", job[Jobs.status]); assertEquals(0, job[Jobs.attempts]) }
        assertEquals("quota", worker.status().error)
        assertNotNull(worker.status().retryAt)
        assertEquals(0L, controller.list("completed", "", 0).total)
    }
    @Test fun `queue registration is idempotent excludes deleted and videos and retries only failures`() {
        asset("one.jpg"); asset("two.jpg"); asset("deleted.jpg", deleted = true); asset("video.mp4", "VIDEO")
        assertEquals(2, controller.enqueue(CaptionController.EnqueueRequest())["queued"])
        assertEquals(0, controller.enqueue(CaptionController.EnqueueRequest())["queued"])
        val completed = queue.claim("CAPTION")!!
        queue.complete(completed.jobId, "CAPTION") { id -> Captions.insert {
            it[assetId] = id; it[caption] = "기존 결과"; it[model] = "local"; it[createdAt] = "2026-10-05T00:00:00"
        } }
        val failed = queue.claim("CAPTION")!!
        transaction { Jobs.update({ Jobs.id eq failed.jobId }) { it[status] = "FAILED"; it[attempts] = 3 } }
        assertEquals(1, controller.enqueue(CaptionController.EnqueueRequest("failed"))["queued"])
        assertEquals("기존 결과", controller.list("completed", "", 0).items.single().caption)
        assertEquals(1L, controller.list("missing", "", 0).total)
        assertEquals(0, controller.enqueue(CaptionController.EnqueueRequest())["queued"])
    }
    private fun awaitIdle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker.status().running && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(worker.status().running)
    }

    @Test fun `result and queue endpoints require authentication and return paginated JSON`() {
        asset("one.jpg")
        val mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(com.homephoto.server.config.ApiKeyFilter(props)).build()
        mvc.perform(get("/api/v1/admin/captions/status"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized)
        mvc.perform(post("/api/v1/admin/captions/enqueue").header("X-Api-Key", "test")
            .contentType("application/json").content("""{"mode":"missing"}"""))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.queued").value(1))
        mvc.perform(get("/api/v1/admin/captions?filter=pending").header("X-Api-Key", "test"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].filename").value("one.jpg"))
    }
}
