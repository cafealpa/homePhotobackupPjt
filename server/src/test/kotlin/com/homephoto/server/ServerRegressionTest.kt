package com.homephoto.server

import com.homephoto.server.config.*
import com.homephoto.server.db.*
import com.homephoto.server.service.*
import com.homephoto.server.storage.FileSystemAdapter
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
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*
import kotlin.test.assertNotNull

class ServerRegressionTest {
    @TempDir lateinit var temp: Path
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database
    private lateinit var props: AppProperties
    private lateinit var locks: AssetLocks
    private lateinit var originals: FileSystemAdapter
    private lateinit var ingest: AssetIngestService
    private lateinit var thumbs: ThumbnailService
    private lateinit var trash: TrashService
    private val queue = JobQueueService()

    @BeforeEach fun setup() {
        dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${temp.resolve("test.db")}?journal_mode=WAL&busy_timeout=5000"
            maximumPoolSize = 4
        })
        db = Database.connect(dataSource)
        DatabaseMigrations().migrate()
        props = AppProperties(temp.resolve("storage"), "test")
        locks = AssetLocks()
        originals = FileSystemAdapter(props)
        ingest = AssetIngestService(originals, ExifService(), TakenAtResolver(), locks)
        thumbs = ThumbnailService(props, ThumbnailStorage(props), locks, MediaProcessRunner(), originals)
        trash = TrashService(props, thumbs, locks, originals)
    }

    @AfterEach fun cleanup() {
        TransactionManager.closeAndUnregister(db)
        dataSource.close()
    }

    private fun image(name: String = "source.png"): Path = temp.resolve(name).also {
        ImageIO.write(BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "png", it.toFile())
    }

    private fun add(path: Path = image()) = ingest.ingest(
        path, path.fileName.toString(), null, Instant.parse("2024-01-02T03:04:05Z"),
    ).asset

    @Test fun `concurrent duplicate uploads store one asset and one set of jobs`() {
        val source = image()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll((1..8).map { Callable {
                ingest.ingest(source, "source.png", null, Instant.parse("2024-01-02T03:04:05Z"))
            } }).map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.created })
            assertEquals(1, results.map { it.asset.id }.distinct().size)
            transaction {
                assertEquals(1L, Assets.selectAll().count())
                assertEquals(3L, Jobs.selectAll().count())
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `failed database insert restores a moved source`() {
        val source = image()
        val bytes = Files.readAllBytes(source)
        transaction { exec("CREATE TRIGGER reject_asset BEFORE INSERT ON assets BEGIN SELECT RAISE(ABORT, 'test failure'); END") }
        assertFailsWith<Exception> {
            ingest.ingest(source, "source.png", null, Instant.now(), moveSource = true)
        }
        assertContentEquals(bytes, Files.readAllBytes(source))
        transaction { assertEquals(0L, Assets.selectAll().count()) }
    }

    @Test fun `successful move preserves stored bytes and removes source`() {
        val source = image()
        val bytes = Files.readAllBytes(source)
        val asset = ingest.ingest(source, "source.png", null, Instant.now(), moveSource = true).asset
        assertFalse(Files.exists(source))
        val path = transaction { Assets.selectAll().where { Assets.id eq asset.id }.first()[Assets.originalPath] }
        assertContentEquals(bytes, Files.readAllBytes(props.storageRoot.resolve(path)))
    }

    @Test fun `restored asset cannot be purged from a stale trash listing`() {
        val asset = add()
        assertTrue(trash.trash(asset.id))
        val stale = trash.trashRows().single()[Assets.id]
        assertNotNull(trash.restore(asset.id))
        assertFalse(trash.purge(stale))
        val row = transaction { Assets.selectAll().where { Assets.id eq asset.id }.first() }
        assertNull(row[Assets.deletedAt])
        assertNull(row[Assets.purgedAt])
        assertTrue(Files.exists(props.storageRoot.resolve(row[Assets.originalPath])))
    }

    @Test fun `concurrent restore and purge never leave an active asset without its file`() {
        val asset = add()
        trash.trash(asset.id)
        val gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val restore = pool.submit(Callable { gate.await(); trash.restore(asset.id) })
            val purge = pool.submit(Callable { gate.await(); trash.purge(asset.id) })
            gate.countDown()
            val restored = restore.get(10, TimeUnit.SECONDS)
            val purged = purge.get(10, TimeUnit.SECONDS)
            assertEquals(restored == null, purged)
            val row = transaction { Assets.selectAll().where { Assets.id eq asset.id }.first() }
            assertEquals(restored != null, Files.exists(props.storageRoot.resolve(row[Assets.originalPath])))
        } finally { pool.shutdownNow() }
    }

    @Test fun `purged asset reupload restores bytes and processing jobs`() {
        val source = image()
        val asset = add(source)
        trash.trash(asset.id)
        assertTrue(trash.purge(asset.id))
        val restored = add(source)
        assertEquals(asset.id, restored.id)
        transaction {
            val row = Assets.selectAll().where { Assets.id eq asset.id }.first()
            assertNull(row[Assets.deletedAt])
            assertNull(row[Assets.purgedAt])
            assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(props.storageRoot.resolve(row[Assets.originalPath])))
            assertEquals(3L, Jobs.selectAll().where { Jobs.assetId eq asset.id }.count())
        }
    }

    @Test fun `concurrent queue claims return each pending job exactly once`() {
        val asset = add()
        transaction {
            // One FACE job per asset: create additional assets through SQL copies with unique hashes.
            for (n in 1..12) {
                val id = Assets.insert {
                    it[hash] = "hash-$n"; it[mediaType] = "PHOTO"; it[originalPath] = "unused-$n"
                    it[originalFilename] = "photo.png"; it[fileSize] = 1L
                    it[takenAtSource] = "FILE_MTIME"; it[yearMonth] = "2024-01"; it[createdAt] = "2024-01-01"
                }[Assets.id]
                Jobs.insert { it[assetId] = id; it[jobType] = "FACE"; it[updatedAt] = "2024-01-01" }
            }
        }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val claimed = pool.invokeAll((1..20).map { Callable { queue.claim("FACE") } })
                .mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(13, claimed.size)
            assertEquals(13, claimed.map { it.jobId }.distinct().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `release preserves retry budget and failures stop after three attempts`() {
        add()
        val first = assertNotNull(queue.claim("CAPTION"))
        queue.release(first.jobId, "CAPTION")
        assertEquals(0, assertNotNull(queue.claim("CAPTION")).attempts)
        repeat(3) { n ->
            queue.fail(first.jobId, "CAPTION", "error with ' quote")
            if (n < 2) assertEquals(n + 1, assertNotNull(queue.claim("CAPTION")).attempts)
        }
        assertNull(queue.claim("CAPTION"))
        transaction {
            val row = Jobs.selectAll().where { Jobs.id eq first.jobId }.first()
            assertEquals("FAILED", row[Jobs.status])
            assertEquals(3, row[Jobs.attempts])
            assertEquals("error with ' quote", row[Jobs.lastError])
        }
    }

    @Test fun `trash is not claimed and purged jobs cannot save late results`() {
        val asset = add()
        val job = assertNotNull(queue.claim("CAPTION"))
        trash.trash(asset.id)
        assertNull(queue.claim("FACE"))
        trash.purge(asset.id)
        assertFalse(queue.complete(job.jobId, "CAPTION") { fail("deleted job must not save a result") })
    }

    @Test fun `completion rejects wrong job type and rolls back when result save fails`() {
        add()
        val job = assertNotNull(queue.claim("CAPTION"))
        assertFalse(queue.complete(job.jobId, "FACE"))
        assertFailsWith<IllegalStateException> { queue.complete(job.jobId, "CAPTION") { error("save failed") } }
        transaction { assertEquals("RUNNING", Jobs.selectAll().where { Jobs.id eq job.jobId }.first()[Jobs.status]) }
        assertTrue(queue.complete(job.jobId, "CAPTION"))
        assertFalse(queue.complete(job.jobId, "CAPTION"))
    }

    @Test fun `legacy migration preserves assets and can be repeated`() {
        val asset = add()
        transaction {
            exec("DELETE FROM homephoto_schema_migrations")
            listOf("favorite", "device_id", "purged_at", "source").forEach { exec("ALTER TABLE assets DROP COLUMN $it") }
            exec("ALTER TABLE faces DROP COLUMN hidden")
        }
        repeat(2) { DatabaseMigrations().migrate() }
        transaction {
            assertEquals(asset.hash, Assets.selectAll().where { Assets.id eq asset.id }.first()[Assets.hash])
            assertEquals(3L, Jobs.selectAll().count())
            assertEquals(6, exec("SELECT COUNT(*) FROM homephoto_schema_migrations") { it.next(); it.getInt(1) })
        }
    }

    @Test fun `failed migration does not record success`() {
        transaction {
            exec("DELETE FROM homephoto_schema_migrations")
            exec("DROP INDEX idx_assets_taken_at")
            exec("CREATE TABLE idx_assets_taken_at (dummy INTEGER)")
        }
        assertFailsWith<Exception> { DatabaseMigrations().migrate() }
        transaction {
            assertEquals(0, exec("SELECT COUNT(*) FROM homephoto_schema_migrations") { it.next(); it.getInt(1) })
        }
    }

    @Test fun `JVM transition clears face data once and queues all active photos without deleting originals`() {
        val asset = add()
        transaction {
            val person = Persons.insert { it[name] = "old name" }[Persons.id]
            Faces.insert {
                it[assetId] = asset.id; it[bboxX] = 0.1; it[bboxY] = 0.1; it[bboxW] = 0.3; it[bboxH] = 0.3
                it[embedding] = org.jetbrains.exposed.sql.statements.api.ExposedBlob(ByteArray(2048))
                it[personId] = person; it[clusterId] = 7
            }
            Jobs.update({ Jobs.jobType eq "FACE" }) { it[status] = "DONE" }
            exec("DELETE FROM homephoto_schema_migrations WHERE version=5")
        }
        DatabaseMigrations().migrate()
        transaction {
            assertEquals(0L, Faces.selectAll().count()); assertEquals(0L, Persons.selectAll().count())
            assertEquals("PENDING", Jobs.selectAll().where { Jobs.jobType eq "FACE" }.single()[Jobs.status])
            assertEquals(asset.hash, Assets.selectAll().single()[Assets.hash])
            Persons.insert { it[name] = "new name" }
            Jobs.update({ Jobs.jobType eq "FACE" }) { it[status] = "DONE" }
        }
        DatabaseMigrations().migrate()
        transaction {
            assertEquals("new name", Persons.selectAll().single()[Persons.name])
            assertEquals("DONE", Jobs.selectAll().where { Jobs.jobType eq "FACE" }.single()[Jobs.status])
        }
        val key = transaction { Assets.selectAll().where { Assets.id eq asset.id }.single()[Assets.originalPath] }
        assertNotNull(originals.stat(key))
    }

    @Test fun `incremental faces appear before queue drains and retain manual group changes`() {
        val first = add()
        val secondImage = image("second.png")
        val changed = ImageIO.read(secondImage.toFile()).apply { setRGB(0, 0, 0xffffff) }
        ImageIO.write(changed, "png", secondImage.toFile())
        val second = add(secondImage)
        fun face(asset: Long, hidden: Boolean = false): Long = transaction {
            Faces.insert {
                it[assetId] = asset; it[bboxX] = 0.1; it[bboxY] = 0.1; it[bboxW] = 0.3; it[bboxH] = 0.3
                it[embedding] = org.jetbrains.exposed.sql.statements.api.ExposedBlob(FaceGroupingService.encode(FloatArray(512) { if (it == 0) 1f else 0f }))
                it[Faces.hidden] = hidden
            }[Faces.id]
        }
        val firstFace = face(first.id)
        val hiddenFace = face(first.id, true)
        val grouping = FaceGroupingService()
        assertEquals(1, grouping.assignPending())
        transaction {
            assertNotNull(Faces.selectAll().where { Faces.id eq firstFace }.single()[Faces.clusterId])
            assertTrue(Jobs.selectAll().where { (Jobs.jobType eq "FACE") and (Jobs.status eq "PENDING") }.count() > 0)
            Faces.update({ Faces.id eq firstFace }) { it[clusterId] = 42 }
        }
        val secondFace = face(second.id)
        assertEquals(1, grouping.assignPending())
        transaction {
            assertEquals(42, Faces.selectAll().where { Faces.id eq secondFace }.single()[Faces.clusterId])
            assertEquals(42, Faces.selectAll().where { Faces.id eq firstFace }.single()[Faces.clusterId])
            assertNull(Faces.selectAll().where { Faces.id eq hiddenFace }.single()[Faces.clusterId])
        }
        assertEquals(0, grouping.assignPending())
    }

    @Test fun `JVM worker saves results progressively and disabled worker does not claim jobs`() {
        val asset = add()
        val key = transaction { Assets.selectAll().where { Assets.id eq asset.id }.single()[Assets.originalPath] }
        val imagePath = originals.withReadableFile(key) { it }
        val engine = org.mockito.Mockito.mock(FaceEngine::class.java)
        org.mockito.Mockito.`when`(engine.modelDirectory()).thenReturn(temp)
        org.mockito.Mockito.`when`(engine.analyze(imagePath)).thenReturn(
            listOf(DetectedFace(0.1, 0.2, 0.3, 0.4, FloatArray(512) { if (it == 0) 1f else 0f })))
        val worker = com.homephoto.server.worker.FaceWorker(props, engine, queue, originals, FaceGroupingService(), ServerActivity())
        try {
            worker.tick()
            org.mockito.Mockito.verifyNoInteractions(engine)
            props.face = AppProperties.FaceProperties(true)
            worker.tick()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (worker.status().running && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(worker.status().running)
            assertEquals(1L, worker.status().completed)
            transaction { assertNotNull(Faces.selectAll().single()[Faces.clusterId]) }
        } finally { worker.close() }
    }

    @Test fun `missing face model leaves photos pending and surfaces an actionable error`() {
        add()
        props.face = AppProperties.FaceProperties(true, temp.resolve("missing-model").toString())
        val engine = FaceEngine(props)
        val worker = com.homephoto.server.worker.FaceWorker(props, engine, queue, originals, FaceGroupingService(), ServerActivity())
        try {
            worker.tick()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (worker.status().running && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(worker.status().running)
            assertEquals(1L, worker.status().pending)
            assertEquals(0L, worker.status().failed)
            assertTrue(worker.status().error.orEmpty().contains("얼굴 모델"))
        } finally { worker.close(); engine.close() }
    }

    @Test fun `query preserves stable bidirectional pagination and visibility`() {
        val ids = transaction {
            (1..5).map { n ->
                Assets.insert {
                    it[hash] = "page-$n"; it[mediaType] = "PHOTO"; it[originalPath] = "unused-$n"
                    it[originalFilename] = "photo.png"; it[fileSize] = 1L
                    it[takenAt] = "2024-01-01T12:00:00"; it[takenAtSource] = "FILE_MTIME"
                    it[yearMonth] = "2024-01"; it[createdAt] = "2024-01-01"
                    it[sourceTag] = if (n == 5) "KIDSNOTE" else null
                    it[deletedAt] = if (n == 4) "2024-01-02" else null
                }[Assets.id]
            }
        }
        val service = AssetQueryService()
        val page = service.list(AssetFilter(limit = 2))
        assertEquals(listOf(ids[2], ids[1]), page.items.map { it.id })
        val older = service.list(AssetFilter(cursor = page.nextCursor, limit = 2))
        assertEquals(listOf(ids[0]), older.items.map { it.id })
        val newer = service.list(AssetFilter(after = "2024-01-01T12:00:00~${ids[0]}", limit = 2))
        assertEquals(page.items, newer.items)
        assertNull(newer.nextCursor)
    }

    @Test fun `thumbnail generation is complete and idempotent under concurrency`() {
        val asset = add()
        val rel = transaction { Assets.selectAll().where { Assets.id eq asset.id }.first()[Assets.originalPath] }
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.invokeAll((1..2).map { Callable { thumbs.generate(asset.hash, rel, "PHOTO") } }).forEach { it.get() }
            ThumbnailService.SIZES.forEach { size ->
                val output = ImageIO.read(thumbs.thumbPath(asset.hash, size).toFile())
                assertNotNull(output)
                assertTrue(output.width > 0)
            }
        } finally { pool.shutdownNow() }
    }
}
