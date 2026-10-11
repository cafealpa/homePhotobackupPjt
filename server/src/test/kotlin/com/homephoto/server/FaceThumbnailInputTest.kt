package com.homephoto.server

import com.homephoto.server.config.*
import com.homephoto.server.db.*
import com.homephoto.server.service.*
import com.homephoto.server.storage.StorageAdapter
import com.homephoto.server.worker.FaceWorker
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

/** 원본은 모든 접근이 금지된 mock이다. 실제 SQLite/로컬 썸네일과 모의 추론으로 워커 계약을 검증한다. */
class FaceThumbnailInputTest {
    @TempDir lateinit var temp: Path
    private lateinit var db: Database
    private lateinit var queue: JobQueueService
    private lateinit var thumbnails: ThumbnailService
    private lateinit var originals: StorageAdapter
    private lateinit var engine: FaceEngine
    private lateinit var worker: FaceWorker
    private var sequence = 0
    private val vector = FloatArray(512) { if (it == 0) 1f else 0f }

    @BeforeEach fun setup() {
        db = Database.connect("jdbc:sqlite:${temp.resolve("test.db")}", driver = "org.sqlite.JDBC")
        DatabaseMigrations().migrate()
        val props = AppProperties(temp.resolve("local"), "test", face = AppProperties.FaceProperties(true))
        originals = mock(StorageAdapter::class.java)
        thumbnails = ThumbnailService(props, ThumbnailStorage(props), AssetLocks(), MediaProcessRunner(), originals)
        queue = JobQueueService()
        engine = mock(FaceEngine::class.java)
        `when`(engine.modelDirectory()).thenReturn(temp)
        `when`(engine.analyze(anyPath())).thenReturn(listOf(DetectedFace(.2, .3, .1, .15, vector)))
        worker = FaceWorker(props, engine, queue, thumbnails, FaceGroupingService(), ServerActivity())
    }

    @AfterEach fun cleanup() {
        worker.close()
        verifyNoInteractions(originals)
        TransactionManager.closeAndUnregister(db)
    }

    private fun asset(thumbnailStatus: String? = "DONE", media: String = "PHOTO", faceStatus: String = "PENDING"): Long = transaction {
        val hash = (++sequence).toString(16).padStart(64, '0')
        val id = Assets.insert {
            it[Assets.hash] = hash; it[mediaType] = media; it[originalPath] = "\\\\unavailable\\photos\\$hash.jpg"
            it[originalFilename] = "$hash.jpg"; it[fileSize] = 1
            it[takenAtSource] = "UPLOAD_TIME"; it[yearMonth] = "2026-10"; it[createdAt] = "2026-10-11T00:00:00"
        }[Assets.id]
        Jobs.insert { it[assetId] = id; it[jobType] = "FACE"; it[status] = faceStatus; it[updatedAt] = "2026-10-11T00:00:00" }
        if (thumbnailStatus != null) Jobs.insert {
            it[assetId] = id; it[jobType] = "THUMBNAIL"; it[status] = thumbnailStatus; it[updatedAt] = "2026-10-11T00:00:00"
        }
        id
    }
    private fun thumbnail(id: Long, size: Int = 1600): Path {
        val hash = transaction { Assets.selectAll().where { Assets.id eq id }.single()[Assets.hash] }
        return thumbnails.thumbPath(hash, size).also {
            Files.createDirectories(it.parent)
            ImageIO.write(BufferedImage(size, size / 2, BufferedImage.TYPE_INT_RGB), "jpg", it.toFile())
        }
    }
    private fun job(id: Long) = transaction { Jobs.selectAll().where { (Jobs.assetId eq id) and (Jobs.jobType eq "FACE") }.single() }
    private fun anyPath(): Path = any(Path::class.java) ?: temp
    private fun tick() {
        worker.tick()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (worker.status().running && System.nanoTime() < deadline) Thread.sleep(5)
        assertFalse(worker.status().running)
    }

    @Test fun `detection and embedding use existing local 1600 thumbnail without any original access`() {
        val id = asset(); val input = thumbnail(id); thumbnail(id, 400)
        tick()
        verify(engine).analyze(input)
        assertEquals("DONE", job(id)[Jobs.status])
        transaction {
            val face = Faces.selectAll().single()
            assertEquals(.2, face[Faces.bboxX]); assertEquals(.3, face[Faces.bboxY])
            assertEquals(.1, face[Faces.bboxW]); assertEquals(.15, face[Faces.bboxH])
            assertContentEquals(vector, FaceGroupingService.decode(face[Faces.embedding].bytes))
            assertNotNull(face[Faces.clusterId])
        }
    }

    @Test fun `pending running and failed thumbnail jobs do not consume FACE attempts or block ready photos`() {
        val pending = asset("PENDING"); val running = asset("RUNNING"); val failed = asset("FAILED"); val ready = asset()
        for (id in listOf(pending, running, failed, ready)) thumbnail(id)
        repeat(3) { tick() }
        assertEquals(2L, worker.status().waitingThumbnail); assertEquals(1L, worker.status().thumbnailFailed)
        for (id in listOf(pending, running, failed)) {
            assertEquals("PENDING", job(id)[Jobs.status]); assertEquals(0, job(id)[Jobs.attempts])
        }
        assertEquals("DONE", job(ready)[Jobs.status]); verify(engine, times(1)).analyze(anyPath())
        transaction { Jobs.update({ Jobs.jobType eq "THUMBNAIL" }) { it[status] = "DONE" } }
        tick()
        assertEquals(4L, worker.status().completed)
        verify(engine, times(4)).analyze(anyPath())
    }

    @Test fun `missing 1600 waits durably with no 400 fallback rapid repeat or attempt consumption`() {
        val missing = asset(); thumbnail(missing, 400)
        val ready = asset(); thumbnail(ready)
        tick()
        val timestamp = job(missing)[Jobs.updatedAt]
        repeat(3) { tick() }
        assertEquals(timestamp, job(missing)[Jobs.updatedAt]); assertEquals(0, job(missing)[Jobs.attempts])
        assertEquals(JobQueueService.FACE_THUMBNAIL_MISSING, job(missing)[Jobs.lastError])
        assertEquals(1L, worker.status().thumbnailMissing)
        assertNull(JobQueueService().claim("FACE")) // 새 큐 객체에서도 저장된 대기 간격을 따른다.
        thumbnail(missing)
        transaction { Jobs.update({ (Jobs.assetId eq missing) and (Jobs.jobType eq "FACE") }) {
            it[updatedAt] = java.time.LocalDateTime.now().minusSeconds(61).format(AssetIngestService.ISO)
        } }
        tick()
        assertEquals("DONE", job(missing)[Jobs.status]); assertEquals(1, job(missing)[Jobs.attempts])
        assertNull(job(missing)[Jobs.lastError]); verify(engine, times(2)).analyze(anyPath())
    }

    @Test fun `legacy thumbnail without generation job works but accidental video FACE is never analyzed`() {
        val legacy = asset(null); thumbnail(legacy)
        val video = asset(media = "VIDEO"); thumbnail(video)
        tick()
        assertEquals("DONE", job(legacy)[Jobs.status])
        assertEquals("PENDING", job(video)[Jobs.status]); assertEquals(0, job(video)[Jobs.attempts])
        verify(engine, times(1)).analyze(anyPath())
    }

    @Test fun `startup and new input preserve existing completed results until explicit failed-job retry`() {
        val id = asset(faceStatus = "DONE"); val input = thumbnail(id)
        val old = transaction { Faces.insert {
            it[assetId] = id; it[bboxX] = .1; it[bboxY] = .2; it[bboxW] = .3; it[bboxH] = .4
            it[embedding] = ExposedBlob(FaceGroupingService.encode(vector)); it[clusterId] = 9; it[hidden] = true
        }[Faces.id] }
        StartupJobRecovery().recover(); DatabaseMigrations().migrate(); tick()
        transaction { val row = Faces.selectAll().single(); assertEquals(old, row[Faces.id]); assertTrue(row[Faces.hidden]); assertEquals(9, row[Faces.clusterId]) }
        verify(engine, never()).analyze(anyPath())
        transaction { Jobs.update({ (Jobs.assetId eq id) and (Jobs.jobType eq "FACE") }) { it[status] = "FAILED" } }
        assertEquals(1, queue.retryAll("FACE")); tick()
        verify(engine).analyze(input)
        assertEquals("DONE", job(id)[Jobs.status])
    }
}
