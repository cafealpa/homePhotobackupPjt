package com.homephoto.server.publication

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*
import kotlin.test.assertNotNull
import com.homephoto.server.db.GooglePhotosPublications as P

@Timeout(30)
class GooglePhotosPublicationQueueTest {
    @TempDir lateinit var temp: Path
    private lateinit var dataSource: HikariDataSource
    private lateinit var db: Database
    private lateinit var props: AppProperties
    private lateinit var originals: FileSystemAdapter
    private lateinit var ingest: AssetIngestService
    private lateinit var thumbnails: ThumbnailService
    private lateinit var queue: GooglePhotosPublicationQueue
    private lateinit var export: GooglePhotosExport
    private lateinit var publisher: FakePublisher
    private lateinit var processor: GooglePhotosPublicationProcessor
    private var sequence = 0

    @BeforeEach fun setup() {
        dataSource = HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${temp.resolve("test.db")}?journal_mode=WAL&busy_timeout=5000"; maximumPoolSize = 4 })
        db = Database.connect(dataSource); TransactionManager.defaultDatabase = db
        DatabaseMigrations().migrate()
        props = AppProperties(temp.resolve("local"), "test", googlePhotos = AppProperties.GooglePhotosProperties(enabled = true))
        originals = FileSystemAdapter(props); originals.initialize()
        val locks = AssetLocks()
        ingest = AssetIngestService(originals, ExifService(), TakenAtResolver(), locks)
        thumbnails = ThumbnailService(props, ThumbnailStorage(props), locks, MediaProcessRunner(), originals)
        queue = GooglePhotosPublicationQueue(props, jacksonObjectMapper())
        export = GooglePhotosExport(props, thumbnails, PublicationMetadataProvider(originals), ExportExifWriter())
        publisher = FakePublisher()
        processor = GooglePhotosPublicationProcessor(props, queue, export, publisher)
    }
    @AfterEach fun cleanup() { TransactionManager.closeAndUnregister(db); dataSource.close() }
    private fun add(done: Boolean = true): Long {
        val source = temp.resolve("source-${++sequence}.png")
        val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB); image.setRGB(0, 0, sequence)
        ImageIO.write(image, "png", source.toFile())
        val asset = ingest.ingest(source, "IMG_20180405_123456_$sequence.png", null, null).asset
        if (done) {
            val key = transaction { Assets.selectAll().where { Assets.id eq asset.id }.first()[Assets.originalPath] }
            thumbnails.generate(asset.hash, key, "PHOTO")
            transaction { Jobs.update({ (Jobs.assetId eq asset.id) and (Jobs.jobType eq "THUMBNAIL") }) { it[status] = "DONE" } }
        }
        return asset.id
    }
    private fun item(id: Long) = queue.items().single { it.assetId == id }
    private fun run(id: Long) { assertEquals(1, queue.enqueue(listOf(id)).enqueued); processor.process(assertNotNull(queue.claim())) }

    @Test fun `queue eligibility deduplication disabled auto registration and thumbnail readiness`() {
        val id = add(false)
        props.googlePhotos = props.googlePhotos.copy(enabled = false, autoPublishNew = true)
        queue.enqueueNew(id); assertTrue(queue.items().isEmpty())
        assertEquals(1, queue.enqueue(listOf(id)).enqueued); assertEquals(1, queue.enqueue(listOf(id)).existing)
        assertNull(queue.claim())
        val video = add(); transaction { Assets.update({ Assets.id eq video }) { it[mediaType] = "VIDEO" } }
        assertEquals(1, queue.enqueue(listOf(video)).ineligible)
        val deleted = add(); transaction { Assets.update({ Assets.id eq deleted }) { it[deletedAt] = "2026-01-01T00:00:00" } }
        assertEquals(1, queue.enqueue(listOf(deleted)).ineligible)
    }

    @Test fun `concurrent claims permit one global active publication`() {
        queue.enqueue((1..5).map { add() })
        val executor = Executors.newFixedThreadPool(8); val gate = CountDownLatch(1)
        try {
            val futures = (1..8).map { executor.submit<GooglePhotosPublicationQueue.Claimed?> { gate.await(); queue.claim() } }
            gate.countDown()
            val claims = futures.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, claims.size); assertTrue(queue.release(claims.single()))
            assertNotNull(queue.claim())
        } finally { executor.shutdownNow() }
    }

    @Test fun `successful publication saves checkpoint and ID cleans temp and remains immutable`() {
        val id = add()
        val row = transaction { Assets.selectAll().where { Assets.id eq id }.first() }
        val original = props.storageRoot.resolve(row[Assets.originalPath]); val thumbnail = thumbnails.thumbPath(row[Assets.hash], 1600)
        val originalHash = GooglePhotosExport.sha256(original); val thumbHash = GooglePhotosExport.sha256(thumbnail)
        publisher.onUpload = { assertEquals("UPLOADING", item(id).status); assertNotNull(item(id).metadata) }
        publisher.onCreate = { assertEquals("CREATING_MEDIA_ITEM", item(id).status) }
        run(id)
        assertEquals("COMPLETED", item(id).status); assertEquals("google-id", item(id).mediaItemId)
        assertEquals(1, publisher.uploads); assertEquals(1, publisher.creates)
        assertNull(queue.renditionPath(id)); assertEquals(0L, Files.list(props.uploadTmpDir.resolve("google-photos")).use { it.count() })
        assertEquals(originalHash, GooglePhotosExport.sha256(original)); assertEquals(thumbHash, GooglePhotosExport.sha256(thumbnail))
        transaction { Assets.update({ Assets.id eq id }) { it[takenAt] = "2025-01-01T00:00:00" } }
        assertEquals(1, queue.enqueue(listOf(id)).existing); assertNull(queue.claim()); assertFalse(queue.retry(id))
    }

    @Test fun `upload failures back off exhaust budget and startup does not reset terminal failure`() {
        val id = add(); queue.enqueue(listOf(id)); var time = System.currentTimeMillis(); queue.clock = { time }
        publisher.uploadFailure = PublicationFailure(PublicationFailure.Kind.RETRYABLE, "HTTP_429", 90)
        repeat(5) { attempt ->
            processor.process(assertNotNull(queue.claim()))
            assertTrue(item(id).nextAttemptAt >= time + 90_000)
            assertNull(queue.claim()); time = item(id).nextAttemptAt + 1
            assertEquals(attempt + 1, item(id).attempts)
        }
        assertEquals("FAILED", item(id).status); assertEquals(0, publisher.creates)
        StartupJobRecovery().recover(); queue.recover(); assertEquals("FAILED", item(id).status)
        assertTrue(queue.retry(id)); assertEquals(0, item(id).attempts)
        assertEquals(3L, transaction { Jobs.selectAll().count() })
    }

    @Test fun `unknown create is never retried and manual existing item resolution prevents repost`() {
        val id = add(); publisher.createFailure = PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "HTTP_500")
        run(id)
        assertEquals("UNKNOWN", item(id).status); assertTrue(Files.exists(assertNotNull(queue.renditionPath(id))))
        assertFalse(queue.retry(id)); queue.recover(); assertNull(queue.claim()); assertEquals(1, publisher.creates)
        assertTrue(queue.resolve(id, "existing-google-id", "https://photos.google.com/photo/test", false))
        assertEquals("COMPLETED", item(id).status); assertEquals("existing-google-id", item(id).mediaItemId)
        assertEquals(1, queue.enqueue(listOf(id)).existing)
    }

    @Test fun `explicit no creation resolution retains identical rendition bytes for controlled retry`() {
        val id = add(); publisher.createFailure = PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "CREATE_INTERRUPTED")
        run(id)
        val path = assertNotNull(queue.renditionPath(id)); val checksum = GooglePhotosExport.sha256(path)
        assertFailsWith<IllegalArgumentException> { queue.resolve(id, null, null, false) }
        assertTrue(queue.resolve(id, null, null, true)); publisher.createFailure = null
        transaction { Assets.update({ Assets.id eq id }) { it[takenAt] = "2020-01-01T00:00:00" } }
        publisher.onUpload = { assertEquals(checksum, GooglePhotosExport.sha256(path)) }
        processor.process(assertNotNull(queue.claim())); assertEquals("COMPLETED", item(id).status)
    }

    @Test fun `known auth failure pauses and manual retry can bind renewed connection`() {
        val id = add(); publisher.createFailure = PublicationFailure(PublicationFailure.Kind.AUTH, "HTTP_401")
        run(id); assertEquals("AUTH_REQUIRED", item(id).status); assertNull(queue.claim())
        publisher.connection = "renewed-connection"; publisher.createFailure = null
        assertTrue(queue.retry(id)); processor.process(assertNotNull(queue.claim()))
        assertEquals("COMPLETED", item(id).status); assertEquals(2, publisher.uploads)
    }

    @Test fun `recovery retains safe upload tokens but quarantines creation and leaves finished rows`() {
        val states = listOf("PREPARING_METADATA", "UPLOADING", "READY_TO_CREATE", "CREATING_MEDIA_ITEM", "COMPLETED", "FAILED", "AUTH_REQUIRED", "UNKNOWN")
        val ids = states.map { add() }; queue.enqueue(ids)
        transaction { ids.forEachIndexed { index, id -> P.update({ P.assetId eq id }) { it[status] = states[index]; it[attempts] = 3; it[uploadToken] = "token-$index" } } }
        queue.recover()
        ids.forEachIndexed { index, id ->
            assertEquals(if (index < 3) "PENDING" else if (index == 3) "UNKNOWN" else states[index], item(id).status)
            assertEquals(3, item(id).attempts)
        }
        transaction { assertEquals("token-2", P.selectAll().where { P.assetId eq ids[2] }.first()[P.uploadToken]) }
        repeat(2) { DatabaseMigrations().migrate() }
        assertEquals(8, queue.items().size)
    }

    @Test fun `asset deletion after byte upload prevents create and clears unpublished temp`() {
        val id = add()
        publisher.onUpload = { transaction { Assets.update({ Assets.id eq id }) { it[deletedAt] = "2026-01-01T00:00:00" } } }
        run(id)
        assertEquals("CANCELLED", item(id).status); assertEquals(0, publisher.creates); assertNull(queue.renditionPath(id))
    }

    @Test fun `remote success followed by DB result failure remains unknown and cannot be recreated`() {
        val id = add()
        publisher.onCreate = { transaction { exec("CREATE TRIGGER fail_publication_result BEFORE UPDATE ON google_photos_publications WHEN NEW.status='COMPLETED' BEGIN SELECT RAISE(ABORT,'fixture result failure'); END") } }
        run(id)
        assertEquals("UNKNOWN", item(id).status); assertNull(item(id).mediaItemId); assertEquals(1, publisher.creates)
        queue.recover(); assertNull(queue.claim()); assertFalse(queue.retry(id))
    }

    @Test fun `pausing after bytes resumes token checkpoint without reupload`() {
        val id = add(); publisher.onUpload = { props.googlePhotos = props.googlePhotos.copy(enabled = false) }
        run(id); assertEquals("PENDING", item(id).status); assertEquals(0, publisher.creates); assertEquals(0, item(id).attempts)
        props.googlePhotos = props.googlePhotos.copy(enabled = true); publisher.onUpload = {}
        processor.process(assertNotNull(queue.claim()))
        assertEquals("COMPLETED", item(id).status); assertEquals(1, publisher.uploads); assertEquals(1, publisher.creates)
    }

    @Test fun `cancellation before create clears prepared data and cancellation during create retains result history`() {
        val cancelled = add(); publisher.onUpload = { assertTrue(queue.cancel(cancelled)) }
        run(cancelled); assertEquals("CANCELLED", item(cancelled).status); assertEquals(0, publisher.creates)
        assertNull(queue.renditionPath(cancelled))
        val creating = add(); publisher.onUpload = {}
        publisher.onCreate = {
            transaction { Assets.update({ Assets.id eq creating }) { it[deletedAt] = "2026-01-01T00:00:00" } }
            assertFalse(queue.cancel(creating))
        }
        run(creating); assertEquals("COMPLETED", item(creating).status); assertEquals("google-id", item(creating).mediaItemId)
    }

    private class FakePublisher : GooglePhotosPublisher {
        var connection = "connection-fixture"; var uploads = 0; var creates = 0
        var uploadFailure: PublicationFailure? = null; var createFailure: PublicationFailure? = null
        var onUpload: () -> Unit = {}; var onCreate: () -> Unit = {}
        override fun connectionId() = connection
        override fun uploadBytes(file: Path, connectionId: String): String {
            check(Files.exists(file)); uploads++; onUpload(); uploadFailure?.let { throw it }; return "upload-fixture"
        }
        override fun createMediaItem(uploadToken: String, fileName: String, connectionId: String): GooglePhotosPublisher.Published {
            creates++; onCreate(); createFailure?.let { throw it }; return GooglePhotosPublisher.Published("google-id", "https://photos.google.com/photo/test")
        }
    }
}
