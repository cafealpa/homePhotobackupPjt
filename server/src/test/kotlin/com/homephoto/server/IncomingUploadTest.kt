package com.homephoto.server

import com.homephoto.server.config.*
import com.homephoto.server.db.*
import com.homephoto.server.service.*
import com.homephoto.server.storage.FileSystemAdapter
import com.homephoto.server.storage.StorageAdapter
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
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

class IncomingUploadTest {
    @TempDir lateinit var temp: Path
    private lateinit var ds: HikariDataSource
    private lateinit var db: Database
    private lateinit var props: AppProperties
    private lateinit var storage: FileSystemAdapter
    private lateinit var ingest: AssetIngestService
    private lateinit var queue: IncomingUploadService
    private val activity = ServerActivity()
    private lateinit var bytes: ByteArray
    private lateinit var hash: String
    private val archive get() = temp.resolve("archive")

    @BeforeEach fun setup() {
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${temp.resolve("test.db")}?journal_mode=WAL&busy_timeout=5000"
            maximumPoolSize = 4
        })
        db = Database.connect(ds)
        DatabaseMigrations().migrate()
        props = AppProperties(temp.resolve("local"), "test", originalStorage = AppProperties.OriginalStorageProperties(archive))
        storage = FileSystemAdapter(props)
        val locks = AssetLocks()
        ingest = AssetIngestService(storage, ExifService(), TakenAtResolver(), locks)
        queue = IncomingUploadService(props, ingest, locks, ExifService(), TakenAtResolver(), activity)
        queue.recover()
        val image = temp.resolve("sample.png")
        ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", image.toFile())
        bytes = Files.readAllBytes(image)
        hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @AfterEach fun close() { queue.close(); TransactionManager.closeAndUnregister(db); ds.close() }
    private fun accept() = queue.accept(Files.write(Files.createTempFile(temp, "receive", ".png"), bytes),
        "IMG_20240102_030405.png", hash, hash, Instant.parse("2024-01-02T03:04:05Z"), "phone", "휴대폰")
    private fun row() = transaction { IncomingUploads.selectAll().single() }
    private fun staged() = props.incomingDir.resolve(row()[IncomingUploads.localName])
    private fun asset() = transaction { Assets.selectAll().single() }

    private fun dueNow() = transaction {
        IncomingUploads.update({ IncomingUploads.hash eq hash }) { it[nextAttemptAt] = 0 }
    }

    private fun eventually(seconds: Long = 5, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "incoming worker did not reach expected state" }
            Thread.sleep(50)
        }
    }

    @Test fun `scheduled worker resumes after real backoff without a retry request`() {
        Files.writeString(archive, "network drive unavailable")
        accept()
        val local = staged()
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            // 운영과 같은 5초 주기. 재시도 API나 DB 시각 변경 없이 검증한다.
            scheduler.scheduleWithFixedDelay({ queue.tick() }, 0, 5, TimeUnit.SECONDS)
            eventually { row()[IncomingUploads.lastError] != null }
            assertEquals("PENDING", row()[IncomingUploads.status])
            assertEquals(1, row()[IncomingUploads.attempts])
            assertTrue(row()[IncomingUploads.nextAttemptAt] > System.currentTimeMillis())
            assertContentEquals(bytes, Files.readAllBytes(local))
            Files.delete(archive)
            eventually(40) { row()[IncomingUploads.status] == "DONE" && !Files.exists(local) }
            assertEquals(2, row()[IncomingUploads.attempts])
            assertContentEquals(bytes, Files.readAllBytes(archive.resolve(asset()[Assets.originalPath])))
        } finally {
            scheduler.shutdownNow()
            scheduler.awaitTermination(5, TimeUnit.SECONDS)
            assertTrue(activity.awaitIdle(java.time.Duration.ofSeconds(5)))
        }
    }

    @Test fun `restart while archive remains offline preserves receipt and resumes after reconnect`() {
        Files.writeString(archive, "network drive unavailable")
        accept()
        val local = staged()
        transaction { IncomingUploads.update({ IncomingUploads.hash eq hash }) { it[status] = "RUNNING" } }
        queue.close()
        TransactionManager.closeAndUnregister(db)
        ds.close()
        ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${temp.resolve("test.db")}?journal_mode=WAL&busy_timeout=5000"
            maximumPoolSize = 4
        })
        db = Database.connect(ds)
        queue = IncomingUploadService(props, ingest, AssetLocks(), ExifService(), TakenAtResolver(), activity)
        DataInitializer(props, DatabaseMigrations(), StartupJobRecovery(), storage, incoming = queue).afterSingletonsInstantiated()
        assertEquals("PENDING", row()[IncomingUploads.status])
        assertFalse(queue.processNext())
        assertContentEquals(bytes, Files.readAllBytes(local))
        Files.delete(archive)
        dueNow() // 이 테스트에서는 예약 시각만 앞당겨 재기동 복구 경로를 확인한다.
        assertTrue(queue.processNext())
        assertEquals("DONE", row()[IncomingUploads.status])
        assertContentEquals(bytes, Files.readAllBytes(archive.resolve(asset()[Assets.originalPath])))
    }

    @Test fun `disconnect after original copy before database commit reuses verified file`() {
        var disconnect = true
        val interrupted = object : StorageAdapter by storage {
            override fun save(key: String, source: Path, checksum: String, moveSource: Boolean): StorageAdapter.Write {
                val result = storage.save(key, source, checksum, moveSource)
                if (disconnect) throw java.nio.file.FileSystemException(key, null, "network connection lost after copy")
                return result
            }
        }
        val locks = AssetLocks()
        val interruptedIngest = AssetIngestService(interrupted, ExifService(), TakenAtResolver(), locks)
        queue.close()
        queue = IncomingUploadService(props, interruptedIngest, locks, ExifService(), TakenAtResolver(), activity)
        accept()
        val local = staged()
        assertFalse(queue.processNext())
        assertEquals("PENDING", row()[IncomingUploads.status])
        assertContentEquals(bytes, Files.readAllBytes(local))
        transaction { assertEquals(0L, Assets.selectAll().count()) }
        val copied = Files.walk(archive).use { it.filter(Files::isRegularFile).toList().single() }
        assertContentEquals(bytes, Files.readAllBytes(copied))
        disconnect = false
        dueNow()
        assertTrue(queue.processNext())
        assertEquals("DONE", row()[IncomingUploads.status])
        assertFalse(Files.exists(local))
        transaction { assertEquals(1L, Assets.selectAll().count()); assertEquals(3L, Jobs.selectAll().count()) }
        assertContentEquals(bytes, Files.readAllBytes(copied))
    }

    @Test fun `long outage caps backoff at thirty minutes without abandoning the receipt`() {
        Files.writeString(archive, "network drive unavailable")
        accept()
        repeat(9) { index ->
            dueNow()
            val before = System.currentTimeMillis()
            assertFalse(queue.processNext())
            val after = System.currentTimeMillis()
            val delay = minOf(30_000L shl minOf(index, 6), 1_800_000L)
            assertEquals("PENDING", row()[IncomingUploads.status])
            assertTrue(row()[IncomingUploads.nextAttemptAt] in (before + delay)..(after + delay))
        }
        assertEquals(9, row()[IncomingUploads.attempts])
        assertContentEquals(bytes, Files.readAllBytes(staged()))
    }

    @Test fun `offline archive retains accepted bytes and automatically eligible retry completes`() {
        Files.writeString(archive, "unavailable archive")
        assertEquals("QUEUED", accept().receipt.status)
        assertFalse(queue.processNext())
        assertEquals("PENDING", row()[IncomingUploads.status])
        assertTrue(row()[IncomingUploads.nextAttemptAt] > System.currentTimeMillis())
        assertContentEquals(bytes, Files.readAllBytes(staged()))
        transaction {
            assertEquals(0L, Assets.selectAll().count())
            assertEquals(0L, Jobs.selectAll().count())
            assertEquals(setOf(hash), queue.queuedHashes(listOf(hash)))
        }
        Files.delete(archive)
        queue.retry(hash)
        val local = staged()
        assertTrue(queue.processNext())
        assertEquals("DONE", row()[IncomingUploads.status])
        assertFalse(Files.exists(local))
        assertContentEquals(bytes, Files.readAllBytes(archive.resolve(asset()[Assets.originalPath])))
        assertEquals("2024-01-02T03:04:05", asset()[Assets.takenAt])
        transaction { assertEquals(3L, Jobs.selectAll().count()) }
        assertEquals(0L, queue.summary().count)
    }

    @Test fun `startup cleanup preserves incoming files and recovers running job`() {
        accept()
        val local = staged()
        transaction { IncomingUploads.update({ IncomingUploads.hash eq hash }) { it[status] = "RUNNING" } }
        DataInitializer(props, DatabaseMigrations(), StartupJobRecovery(), storage, incoming = queue).afterSingletonsInstantiated()
        assertTrue(Files.exists(local))
        assertEquals("PENDING", row()[IncomingUploads.status])
        assertTrue(queue.processNext())
    }

    @Test fun `failed status persistence recovers without restarting the server`() {
        Files.writeString(archive, "offline")
        accept()
        transaction {
            exec("CREATE TRIGGER reject_incoming_status BEFORE UPDATE ON incoming_uploads WHEN OLD.status='RUNNING' BEGIN SELECT RAISE(FAIL, 'status storage unavailable'); END")
        }
        assertFails { queue.processNext() }
        assertEquals("RUNNING", row()[IncomingUploads.status])
        assertContentEquals(bytes, Files.readAllBytes(staged()))
        transaction { exec("DROP TRIGGER reject_incoming_status") }
        Files.delete(archive)
        queue.retryPending()
        assertEquals("PENDING", row()[IncomingUploads.status])
        assertTrue(queue.processNext())
        assertEquals("DONE", row()[IncomingUploads.status])
        assertContentEquals(bytes, Files.readAllBytes(archive.resolve(asset()[Assets.originalPath])))
    }

    @Test fun `storage outage remains retryable after more than three attempts`() {
        Files.writeString(archive, "offline")
        accept()
        repeat(5) { queue.retry(hash); assertFalse(queue.processNext()) }
        assertEquals(5, row()[IncomingUploads.attempts])
        assertEquals("PENDING", row()[IncomingUploads.status])
        assertContentEquals(bytes, Files.readAllBytes(staged()))
        Files.delete(archive)
        queue.retry(hash); queue.processNext()
        assertEquals("DONE", row()[IncomingUploads.status])
    }

    @Test fun `purge after explicit restore receipt prevents resurrection`() {
        accept(); queue.processNext()
        val id = asset()[Assets.id]
        transaction { Assets.update({ Assets.id eq id }) { it[deletedAt] = "2026-10-05T10:00:00" } }
        accept()
        transaction { Assets.update({ Assets.id eq id }) { it[purgedAt] = "2026-10-05T10:01:00" } }
        transaction { assertTrue(queue.queuedHashes(listOf(hash)).isEmpty()) }
        queue.processNext()
        assertEquals("CANCELLED", row()[IncomingUploads.status])
        assertNotNull(asset()[Assets.purgedAt])
    }

    @Test fun `concurrent same hash submissions create one receipt and one source`() {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val results = pool.invokeAll((1..6).map { Callable { accept() } }).map { it.get(10, TimeUnit.SECONDS) }
            assertTrue(results.all { it.receipt.hash == hash })
            assertEquals(1L, queue.summary().count)
            Files.list(props.incomingDir).use { assertEquals(1L, it.count()) }
            assertTrue(queue.processNext())
            assertFalse(queue.processNext())
        } finally { pool.shutdownNow() }
    }

    @Test fun `crash after asset commit does not duplicate jobs on replay`() {
        accept()
        ingest.ingest(staged(), "sample.png", hash, Instant.now())
        transaction { IncomingUploads.update({ IncomingUploads.hash eq hash }) { it[status] = "RUNNING" } }
        queue.recover()
        queue.processNext()
        assertEquals("DONE", row()[IncomingUploads.status])
        transaction { assertEquals(1L, Assets.selectAll().count()); assertEquals(3L, Jobs.selectAll().count()) }
    }

    @Test fun `deletion after asset commit wins over pending receipt`() {
        accept()
        val id = ingest.ingest(staged(), "sample.png", hash, Instant.now()).asset.id
        transaction { Assets.update({ Assets.id eq id }) { it[deletedAt] = "2026-10-05T10:00:00" } }
        transaction { assertTrue(queue.queuedHashes(listOf(hash)).isEmpty()) }
        queue.processNext()
        assertEquals("CANCELLED", row()[IncomingUploads.status])
        assertNotNull(asset()[Assets.deletedAt])
    }

    @Test fun `explicit reupload restores tombstone after queue wait`() {
        accept(); queue.processNext()
        val id = asset()[Assets.id]
        transaction { Assets.update({ Assets.id eq id }) { it[deletedAt] = "2026-10-05T10:00:00" } }
        accept()
        transaction { assertEquals(setOf(hash), queue.queuedHashes(listOf(hash))) }
        queue.processNext()
        assertNull(asset()[Assets.deletedAt])
        assertEquals(id, asset()[Assets.id])
    }

    @Test fun `missing local receipt allows device reupload`() {
        accept(); Files.delete(staged()); queue.processNext()
        assertEquals("LOST", row()[IncomingUploads.status])
        transaction { assertTrue(queue.queuedHashes(listOf(hash)).isEmpty()) }
        accept(); queue.processNext()
        assertEquals("DONE", row()[IncomingUploads.status])
    }

    @Test fun `changed local bytes are not accepted as an original`() {
        accept(); Files.writeString(staged(), "corruption"); queue.processNext()
        assertEquals("LOST", row()[IncomingUploads.status])
        assertTrue(Files.exists(staged()))
        transaction { assertEquals(0L, Assets.selectAll().count()) }
    }

    @Test fun `database failure during ingest retains source until operator retry`() {
        accept()
        transaction { exec("CREATE TRIGGER reject_asset BEFORE INSERT ON assets BEGIN SELECT RAISE(ABORT, 'test'); END") }
        queue.processNext()
        assertEquals("BLOCKED", row()[IncomingUploads.status])
        assertContentEquals(bytes, Files.readAllBytes(staged()))
        transaction { exec("DROP TRIGGER reject_asset") }
        queue.retry(hash); queue.processNext()
        assertEquals("DONE", row()[IncomingUploads.status])
    }

    @Test fun `receipt database failure never removes the durable copy`() {
        transaction { exec("CREATE TRIGGER reject_receipt BEFORE INSERT ON incoming_uploads BEGIN SELECT RAISE(ABORT, 'test'); END") }
        assertFailsWith<Exception> { accept() }
        transaction { assertEquals(0L, IncomingUploads.selectAll().count()) }
        Files.list(props.incomingDir).use { paths -> assertContentEquals(bytes, Files.readAllBytes(paths.toList().single())) }
    }

    @Test fun `incorrect client hash is rejected before accepting responsibility`() {
        val source = Files.write(temp.resolve("bad.png"), bytes)
        assertFailsWith<IllegalArgumentException> { queue.accept(source, "bad.png", hash, "wrong", null, null, null) }
        assertTrue(Files.exists(source))
        assertEquals(0L, queue.summary().count)
    }

    @Test fun `shutdown leaves queued files pending instead of claiming new work`() {
        accept()
        activity.begin()
        assertFalse(queue.processNext())
        assertEquals("PENDING", row()[IncomingUploads.status])
        assertContentEquals(bytes, Files.readAllBytes(staged()))
        activity.resume()
        assertTrue(queue.processNext())
    }

    @Test fun `due retry drains automatically without operator retry and preserves backoff`() {
        Files.writeString(archive,"offline")
        accept();assertFalse(queue.processNext())
        assertFalse(queue.processNext())
        assertEquals(1,row()[IncomingUploads.attempts])
        Files.delete(archive)
        transaction { IncomingUploads.update({ IncomingUploads.hash eq hash }) { it[nextAttemptAt]=0 } }
        assertTrue(queue.processNext())
        assertEquals("DONE",row()[IncomingUploads.status])
    }

    @Test fun `process pause holds durable receipts and start resumes them`() {
        val monitor=ProcessMonitor(props,com.fasterxml.jackson.databind.ObjectMapper())
        val controlled=IncomingUploadService(props,ingest,AssetLocks(),ExifService(),TakenAtResolver(),ServerActivity(monitor))
        try {
            accept();monitor.stop("INCOMING")
            assertFalse(controlled.processNext());assertEquals("PENDING",row()[IncomingUploads.status])
            assertContentEquals(bytes,Files.readAllBytes(staged()))
            monitor.start("INCOMING");assertTrue(controlled.processNext())
            assertEquals("DONE",row()[IncomingUploads.status])
        } finally { controlled.close();monitor.close() }
    }


    @Test fun `cancelled analysis claim is released without consuming a failure attempt`() {
        accept();queue.processNext()
        val monitor=ProcessMonitor(props,com.fasterxml.jackson.databind.ObjectMapper())
        val activity=ServerActivity(monitor);val jobs=JobQueueService(activity)
        assertTrue(activity.enter("THUMBNAIL"))
        try {
            val claimed=assertNotNull(jobs.claim("THUMBNAIL"))
            monitor.stop("THUMBNAIL")
            jobs.fail(claimed.jobId,"THUMBNAIL","cancelled")
            transaction {
                val job=Jobs.selectAll().where { Jobs.id eq claimed.jobId }.single()
                assertEquals("PENDING",job[Jobs.status]);assertEquals(0,job[Jobs.attempts])
            }
        } finally { activity.leave("THUMBNAIL");monitor.close() }
    }

}
