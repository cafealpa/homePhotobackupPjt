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
    private lateinit var filenames: GooglePhotosFilenameExclusions
    private var sequence = 0

    @BeforeEach fun setup() {
        dataSource = HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${temp.resolve("test.db")}?journal_mode=WAL&busy_timeout=5000"; maximumPoolSize = 4 })
        db = Database.connect(dataSource); TransactionManager.defaultDatabase = db
        DatabaseMigrations().migrate()
        props = AppProperties(temp.resolve("local"), "test", googlePhotos = AppProperties.GooglePhotosProperties(enabled = true, tokenFile = temp.resolve("tokens.json").toString()))
        originals = FileSystemAdapter(props); originals.initialize()
        val locks = AssetLocks()
        ingest = AssetIngestService(originals, ExifService(), TakenAtResolver(), locks)
        thumbnails = ThumbnailService(props, ThumbnailStorage(props), locks, MediaProcessRunner(), originals)
        queue = GooglePhotosPublicationQueue(props, jacksonObjectMapper())
        filenames = GooglePhotosFilenameExclusions(jacksonObjectMapper())
        export = GooglePhotosExport(props, thumbnails, PublicationMetadataProvider(originals), ExportExifWriter(), originals)
        publisher = FakePublisher()
        processor = GooglePhotosPublicationProcessor(props, queue, export, publisher, GooglePhotosPublicationAlbum(props, jacksonObjectMapper(), publisher))
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

    private fun addVideo(): Pair<Long, ByteArray> {
        val bytes = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 1, 2, (++sequence).toByte())
        val source = Files.write(temp.resolve("source-video-$sequence.mp4"), bytes)
        val asset = ingest.ingest(source, "VID_20261001_120000_$sequence.mp4", null, null).asset
        return asset.id to bytes
    }

    @Test fun `exact filenames exclude different photo content and videos from selected bulk recent and automatic preparation`() {
        props.googlePhotos = props.googlePhotos.copy(includeVideos = true, autoPublishNew = true)
        val first = add(); val second = add(); val differentCase = add(); val (video, _) = addVideo()
        transaction {
            Assets.update({ Assets.id inList listOf(first, second) }) { it[originalFilename] = "same.JPG" }
            Assets.update({ Assets.id eq differentCase }) { it[originalFilename] = "same.jpg" }
            Assets.update({ Assets.id eq video }) { it[originalFilename] = "clip.mp4" }
        }
        assertEquals(GooglePhotosFilenameExclusions.Added(2, 2), filenames.add(listOf("same.JPG", "same.JPG", "clip.mp4")))
        assertEquals(0L, filenames.add(listOf("same.JPG")).added)
        assertEquals(GooglePhotosPublicationQueue.Enqueued(0, 0, 0, 3), queue.enqueue(listOf(first, second, video)))
        listOf(first, second, video).forEach(queue::enqueueNew)
        assertTrue(queue.items().isEmpty())
        assertEquals(1, queue.enqueueRecent(5).enqueued)
        assertEquals(differentCase, queue.items().single().assetId)
        assertEquals(GooglePhotosPublicationQueue.BulkEnqueued(0, 3), queue.enqueueAll())
        assertEquals(2L, GooglePhotosFilenameExclusions(jacksonObjectMapper()).status().count)
        assertEquals(2L, filenames.clear())
        assertEquals(3, queue.enqueueAll().enqueued)
    }

    @Test fun `Takeout uses original title including long names rather than truncated JSON filenames and ignores albums`() {
        val directory = Files.createDirectories(temp.resolve("Takeout/Google Photos/Photos from 2024"))
        val name = "this-is-a-long-original-name-that-is-longer-than-the-export-json-filename.jpg"
        Files.writeString(directory.resolve("truncated.supplemental-metadata.json"), """{"title":"$name","photoTakenTime":{"timestamp":"1"}}""")
        Files.writeString(directory.resolve("duplicate.json"), """{"title":"$name","photoTakenTime":{"timestamp":"2"}}""")
        Files.writeString(directory.resolve("album.json"), """{"title":"앨범 제목","date":{"timestamp":"3"}}""")
        Files.write(directory.resolve("media.jpg"), byteArrayOf(1, 2))
        assertEquals(GooglePhotosFilenameExclusions.Added(1, 1), filenames.importTakeout(temp.resolve("Takeout").toString()))
        val id = add()
        transaction { Assets.update({ Assets.id eq id }) { it[originalFilename] = name } }
        assertEquals(1, queue.enqueue(listOf(id)).excluded)
        assertEquals(0L, filenames.importTakeout(directory.toString()).added)
        assertFailsWith<IllegalArgumentException> { filenames.importTakeout(temp.resolve("not-a-folder").toString()) }
        Files.writeString(directory.resolve("broken.json"), "{")
        assertFailsWith<Exception> { filenames.importTakeout(directory.toString()) }
        assertEquals(1L, filenames.status().count)
    }

    @Test fun `import during byte upload cancels creation and removes snapshot and session while preserving unknown and completed history`() {
        val id = add(); val unknown = add(); val completed = add()
        queue.enqueue(listOf(id, unknown, completed))
        val name = "existing.jpg"
        transaction {
            Assets.update({ Assets.id inList listOf(id, unknown, completed) }) { it[originalFilename] = name }
            P.update({ P.assetId eq unknown }) { it[status] = "UNKNOWN" }
            P.update({ P.assetId eq completed }) { it[status] = "COMPLETED"; it[mediaItemId] = "keep-id" }
        }
        var path: Path? = null
        publisher.onUpload = {
            path = assertNotNull(queue.renditionPath(id))
            Files.writeString(GooglePhotosResumableUpload.sessionPath(path!!), "private-session")
            filenames.add(listOf(name))
            assertEquals(1, queue.cancelExcluded())
        }
        processor.process(assertNotNull(queue.claim()))
        assertEquals(0, publisher.creates)
        assertEquals("CANCELLED", item(id).status)
        assertEquals("FILENAME_ALREADY_IN_GOOGLE", item(id).lastError)
        assertEquals("UNKNOWN", item(unknown).status); assertEquals("keep-id", item(completed).mediaItemId)
        assertFalse(Files.exists(path!!)); assertFalse(Files.exists(GooglePhotosResumableUpload.sessionPath(path!!)))
        assertFalse(queue.retry(id))
        assertFailsWith<IllegalArgumentException> { queue.resolve(unknown, null, null, true) }
    }

    @Test fun `filename checks block claim and final creation and restart cancels an interrupted import`() {
        val id = add(); queue.enqueue(listOf(id))
        val job = assertNotNull(queue.claim())
        val prepared = export.prepare(job.asset)
        assertTrue(queue.prepared(job, prepared, "test-connection"))
        assertTrue(queue.tokenSaved(job, "byte-token"))
        filenames.add(listOf(job.asset.originalFilename))
        assertFalse(queue.beginCreate(job))
        export.delete(prepared.path)
        assertEquals("FILENAME_ALREADY_IN_GOOGLE", item(id).lastError)
        val waiting = add(); queue.enqueue(listOf(waiting))
        filenames.add(listOf(transaction { Assets.selectAll().where { Assets.id eq waiting }.single()[Assets.originalFilename] }))
        assertNull(queue.claim())
        queue.recover()
        assertEquals("CANCELLED", item(waiting).status)
    }

    @Test fun `successful posting registers filename and cancels another queued asset with the same name`() {
        val first = add(); val duplicate = add()
        transaction {
            val name = Assets.selectAll().where { Assets.id eq first }.single()[Assets.originalFilename]
            Assets.update({ Assets.id eq duplicate }) { it[originalFilename] = name }
        }
        queue.enqueue(listOf(first, duplicate))
        processor.process(assertNotNull(queue.claim()))
        assertEquals(1, publisher.creates)
        assertEquals(1L, queue.counts()["COMPLETED"])
        assertEquals(1L, queue.counts()["CANCELLED"])
        assertEquals(1L, filenames.status().count)
    }

    @Test fun `video publishes original bytes without thumbnail and completed history prevents repost`() {
        props.googlePhotos = props.googlePhotos.copy(includeVideos = true)
        val (id, bytes) = addVideo()
        val original = props.storageRoot.resolve(transaction { Assets.selectAll().where { Assets.id eq id }.first()[Assets.originalPath] })
        publisher.onUpload = {
            assertContentEquals(bytes, Files.readAllBytes(assertNotNull(queue.renditionPath(id))))
            assertEquals("video/mp4", publisher.lastContentType)
        }
        run(id)
        assertEquals("COMPLETED", item(id).status); assertEquals(1, publisher.creates)
        assertEquals(item(id).originalFilename, publisher.lastFilename)
        assertEquals(GooglePhotosExport.VIDEO_VERSION, transaction { P.select(P.renditionVersion).where { P.assetId eq id }.single()[P.renditionVersion] })
        assertContentEquals(bytes, Files.readAllBytes(original))
        assertEquals(0L, Files.list(props.uploadTmpDir.resolve("google-photos")).use { it.count() })
        assertEquals(0, queue.enqueueAll().enqueued); assertNull(queue.claim())
    }

    @Test fun `unpublished legacy video JPEG and token are replaced by original while completed history stays`() {
        props.googlePhotos = props.googlePhotos.copy(includeVideos = true)
        val (id, bytes) = addVideo(); queue.enqueue(listOf(id))
        val oldPath = Files.write(props.uploadTmpDir.resolve("google-photos").also(Files::createDirectories).resolve("$id-legacy.jpg"), byteArrayOf(4, 5))
        transaction { P.update({ P.assetId eq id }) {
            it[renditionPath] = oldPath.toString(); it[renditionSha256] = GooglePhotosExport.sha256(oldPath)
            it[uploadToken] = "legacy-jpeg-token"; it[tokenCreatedAt] = System.currentTimeMillis()
        } }
        publisher.onUpload = { assertContentEquals(bytes, Files.readAllBytes(assertNotNull(queue.renditionPath(id)))) }
        processor.process(assertNotNull(queue.claim()))
        assertEquals("COMPLETED", item(id).status); assertEquals(1, publisher.uploads); assertFalse(Files.exists(oldPath))
        assertEquals("upload-fixture", publisher.lastToken)
    }

    @Test fun `video pause preserves snapshot and cancellation removes resumable checkpoint`() {
        props.googlePhotos = props.googlePhotos.copy(includeVideos = true)
        val (id, _) = addVideo()
        publisher.onUpload = {
            val path = assertNotNull(queue.renditionPath(id))
            Files.writeString(GooglePhotosResumableUpload.sessionPath(path), "private-session-fixture")
        }
        publisher.uploadFailure = PublicationFailure(PublicationFailure.Kind.PERMANENT, "PUBLICATION_DISABLED")
        run(id)
        assertEquals("PENDING", item(id).status); assertEquals(0, publisher.creates)
        val path = assertNotNull(queue.renditionPath(id)); val checkpoint = GooglePhotosResumableUpload.sessionPath(path)
        assertTrue(Files.exists(checkpoint)); assertTrue(checkpoint.toString() in queue.referencedPaths())
        assertTrue(queue.cancel(id)); assertFalse(Files.exists(path)); assertFalse(Files.exists(checkpoint))
    }

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

    @Test fun `bulk registration excludes private deleted and existing assets and follows video settings`() {
        val ready = add(); val waiting = add(false); val video = add(false)
        val deleted = add(false); val purged = add(false); val kidsnote = add(false)
        val states = listOf("PENDING", "PREPARING_METADATA", "UPLOADING", "READY_TO_CREATE", "CREATING_MEDIA_ITEM",
            "COMPLETED", "FAILED", "AUTH_REQUIRED", "UNKNOWN", "CANCELLED")
        val existing = states.map { add(false) }
        queue.enqueue(existing)
        transaction {
            Assets.update({ Assets.id eq video }) { it[mediaType] = "VIDEO" }
            Assets.update({ Assets.id eq deleted }) { it[deletedAt] = "2026-01-01T00:00:00" }
            Assets.update({ Assets.id eq purged }) { it[purgedAt] = "2026-01-01T00:00:00" }
            Assets.update({ Assets.id eq kidsnote }) { it[sourceTag] = "KIDSNOTE" }
            existing.forEachIndexed { index, id -> P.update({ P.assetId eq id }) {
                it[status] = states[index]; it[attempts] = 2; it[lastError] = "fixture-$index"
                if (states[index] == "COMPLETED") it[mediaItemId] = "existing-google-id"
            } }
        }
        val before = queue.items().associateBy { it.assetId }
        props.googlePhotos = props.googlePhotos.copy(enabled = false)
        assertEquals(2, queue.enqueueAll().enqueued)
        assertEquals("PENDING", item(ready).status); assertEquals("PENDING", item(waiting).status)
        assertEquals(before, queue.items().filter { it.assetId in existing }.associateBy { it.assetId })
        assertEquals(0, queue.enqueueAll().enqueued)
        assertTrue(queue.items().none { it.assetId in listOf(video, deleted, purged, kidsnote) })
        props.googlePhotos = props.googlePhotos.copy(includeVideos = true)
        assertEquals(1, queue.enqueueAll().enqueued)
        assertEquals("PENDING", item(video).status)
        assertEquals(0, queue.enqueueAll().enqueued)
        assertEquals(before, queue.items().filter { it.assetId in existing }.associateBy { it.assetId })
    }

    @Test fun `bulk registration supports more than one thousand photos and an empty library`() {
        assertEquals(0, queue.enqueueAll().enqueued)
        transaction {
            Assets.batchInsert(1..1005) { number ->
                this[Assets.hash] = "bulk-$number"; this[Assets.mediaType] = "PHOTO"
                this[Assets.originalPath] = "originals/bulk-$number.jpg"; this[Assets.originalFilename] = "bulk-$number.jpg"
                this[Assets.fileSize] = 1; this[Assets.takenAtSource] = "UPLOAD_TIME"
                this[Assets.yearMonth] = "2026-10"; this[Assets.createdAt] = "2026-10-01T00:00:00"
            }
        }
        assertEquals(1005, queue.enqueueAll().enqueued)
        assertEquals(mapOf("PENDING" to 1005L), queue.counts())
        assertEquals(0, queue.enqueueAll().enqueued)
        assertNull(queue.claim()) // 썸네일이 준비될 때까지 기존 워커 조건을 따른다.
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

    @Test fun `album backfill connects only this account completed IDs in bounded batches without reposting or changing queue`() {
        val ids = (1..52).map { add(false) }
        queue.enqueue(ids)
        transaction {
            ids.forEachIndexed { index, id -> P.update({ P.assetId eq id }) {
                it[status] = "COMPLETED"; it[connectionId] = "connection-fixture"
                it[mediaItemId] = if (index == 1) "missing-google-id" else "existing-$id"
            } }
        }
        val foreign = add(false); queue.enqueue(listOf(foreign))
        transaction { P.update({ P.assetId eq foreign }) { it[status] = "COMPLETED"; it[connectionId] = "different-account"; it[mediaItemId] = "foreign-id" } }
        val pending = add(false); queue.enqueue(listOf(pending))
        val before = queue.items()
        publisher.addFailure = { if ("missing-google-id" in it) PublicationFailure(PublicationFailure.Kind.PERMANENT, "HTTP_404") else null }
        props.googlePhotos = props.googlePhotos.copy(enabled = false)
        val albums = GooglePhotosPublicationAlbum(props, jacksonObjectMapper(), publisher)
        repeat(2) {
            val result = albums.organize(queue)
            assertEquals(51, result.included); assertEquals(listOf(ids[1]), result.failedAssetIds); assertNull(result.stoppedCode)
            assertEquals(before, queue.items())
        }
        assertEquals(1, publisher.albumCreates)
        assertEquals(0, publisher.uploads); assertEquals(0, publisher.creates)
        assertTrue(publisher.albumAdds.all { it.size in 1..50 && "foreign-id" !in it })
        assertTrue(publisher.albumAdds.any { it.size == 50 }); assertTrue(publisher.albumAdds.any { it.size == 2 })
    }

    @Test fun `uncertain album creation blocks image upload and does not mark photo creation as unknown`() {
        publisher.albumFailure = PublicationFailure(PublicationFailure.Kind.UNCERTAIN, "HTTP_500")
        val first = add(); run(first)
        val second = add(); run(second)
        assertEquals("FAILED", item(first).status); assertEquals("FAILED", item(second).status)
        assertEquals("ALBUM_CONFIRMATION_REQUIRED", item(first).lastError)
        assertEquals(1, publisher.albumCreates); assertEquals(0, publisher.uploads); assertEquals(0, publisher.creates)
    }

    private class FakePublisher : GooglePhotosPublisher {
        var connection = "connection-fixture"; var uploads = 0; var creates = 0
        var lastContentType: String? = null; var lastFilename: String? = null; var lastToken: String? = null
        var uploadFailure: PublicationFailure? = null; var createFailure: PublicationFailure? = null
        var albumFailure: PublicationFailure? = null; var albumCreates = 0
        val albumAdds = mutableListOf<List<String>>()
        var addFailure: (List<String>) -> PublicationFailure? = { null }
        var onUpload: () -> Unit = {}; var onCreate: () -> Unit = {}
        override fun connectionId() = connection
        override fun uploadBytes(file: Path, connectionId: String, contentType: String): String {
            check(Files.exists(file)); uploads++; lastContentType = contentType; onUpload(); uploadFailure?.let { throw it }; return "upload-fixture"
        }
        override fun createAlbum(title: String, connectionId: String): GooglePhotosPublisher.Album {
            albumCreates++; albumFailure?.let { throw it }; return GooglePhotosPublisher.Album("album-fixture", "https://photos.google.com/album/test")
        }
        override fun addToAlbum(albumId: String, mediaItemIds: List<String>, connectionId: String) {
            albumAdds += mediaItemIds; addFailure(mediaItemIds)?.let { throw it }
        }
        override fun createMediaItem(uploadToken: String, fileName: String, connectionId: String, albumId: String?): GooglePhotosPublisher.Published {
            assertEquals("album-fixture", albumId)
            lastFilename = fileName; lastToken = uploadToken
            creates++; onCreate(); createFailure?.let { throw it }; return GooglePhotosPublisher.Published("google-id", "https://photos.google.com/photo/test")
        }
    }
}
