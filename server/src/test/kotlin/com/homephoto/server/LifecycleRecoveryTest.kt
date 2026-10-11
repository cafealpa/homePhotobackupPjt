package com.homephoto.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.homephoto.server.config.*
import com.homephoto.server.db.*
import com.homephoto.server.document.DocumentWorker
import com.homephoto.server.publication.GooglePhotosPublicationQueue
import com.homephoto.server.search.*
import com.homephoto.server.service.*
import com.homephoto.server.storage.FileSystemAdapter
import com.homephoto.server.worker.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import kotlin.test.*

/** 실제 SQLite와 장애 주입으로 작업 제어·복구 경계를 검증한다. 키즈노트는 모의 상태만 제공한다. */
class LifecycleRecoveryTest {
    @TempDir lateinit var temp: Path
    private lateinit var db: Database
    private lateinit var props: AppProperties
    private lateinit var monitor: ProcessMonitor
    private lateinit var activity: ServerActivity
    private lateinit var queue: JobQueueService
    private lateinit var storage: FileSystemAdapter
    private lateinit var photo: PhotoSearchProcess
    private lateinit var service: ProcessManagementService
    private val closers=mutableListOf<() -> Unit>()

    @BeforeEach fun setup() {
        db=Database.connect("jdbc:sqlite:${temp.resolve("test.db")}",driver="org.sqlite.JDBC")
        DatabaseMigrations().migrate()
        props=AppProperties(temp.resolve("storage"),"audit",thumbnailThreads=1,
            face=AppProperties.FaceProperties(enabled=true))
        monitor=ProcessMonitor(props,jacksonObjectMapper()); activity=ServerActivity(monitor)
        storage=FileSystemAdapter(props); storage.initialize(); queue=JobQueueService(activity)
        photo=PhotoSearchProcess(PhotoSearchProperties(true,temp.resolve("missing-model").toString()),props,mock(ThumbnailService::class.java),activity)
        val text=mock(CaptionTextSearch::class.java)
        `when`(text.status()).thenReturn(CaptionTextSearch.Status("disabled",0))
        val caption=mock(CaptionWorker::class.java)
        `when`(caption.status()).thenReturn(CaptionWorker.Status(false,false,null,null,null,"LOCAL","test"))
        val excludedKidsnote=mock(KidsnoteImportService::class.java)
        `when`(excludedKidsnote.status()).thenReturn(com.homephoto.server.api.KidsnoteImportStatusDto(false,0,0,0,0,0,0,0,0,0,null,null))
        service=ProcessManagementService(monitor,activity,props,queue,mock(IncomingUploadService::class.java),
            caption,mock(FaceWorker::class.java),mock(DocumentWorker::class.java),photo,text,
            mock(GooglePhotosPublicationQueue::class.java),ImportService(mock(AssetIngestService::class.java),props,storage,activity),
            excludedKidsnote,ThumbnailStorage(props,activity),mock(TrashService::class.java))
        service.register()
    }
    @AfterEach fun cleanup() {
        activity.begin(); activity.awaitIdle(Duration.ofSeconds(5)); closers.forEach { it() }
        photo.shutdown(); monitor.close(); TransactionManager.closeAndUnregister(db)
    }
    private fun eventually(condition: () -> Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(!condition()) { check(System.nanoTime()<deadline) { "probe timed out" }; Thread.sleep(10) }
    }
    private fun item(id: String)=service.summary().items.single { it.id==id }
    private fun seedJob(type: String, hashValue: String = "a".repeat(64)): Long = transaction {
        val asset=Assets.insert {
            it[hash]=hashValue; it[mediaType]="PHOTO"; it[originalPath]="source.jpg"; it[originalFilename]="source.jpg"
            it[fileSize]=1; it[takenAtSource]="UPLOAD_TIME"; it[yearMonth]="2026-10"; it[createdAt]="2026-10-09T00:00:00"
        }[Assets.id]
        Jobs.insert { it[assetId]=asset; it[jobType]=type; it[status]="PENDING"; it[updatedAt]="2026-10-09T00:00:00" }[Jobs.id]
    }

    @Test fun `missing photo model must appear as an actionable monitoring error`() {
        photo.tick(); eventually { photo.status().state=="model_missing" && activity.count()==0 }
        val actual=item("PHOTO_INDEX")
        assertEquals("NEEDS_ATTENTION",actual.state)
        assertNotNull(actual.lastError)
        assertTrue(actual.logs.none { it.level == "DONE" })
    }

    @Test fun `native face initialization failure must be visible`() {
        val engine=mock(FaceEngine::class.java); val entered=CountDownLatch(1)
        `when`(engine.modelDirectory()).thenReturn(temp.resolve("face-model"))
        doAnswer { entered.countDown(); throw UnsatisfiedLinkError("audit native DLL load failure") }.`when`(engine).prepare()
        val worker=FaceWorker(props,engine,queue,mock(ThumbnailService::class.java),mock(FaceGroupingService::class.java),activity)
        closers += { worker.close() }
        worker.tick(); assertTrue(entered.await(5,TimeUnit.SECONDS)); eventually { !worker.status().running }
        val actual=item("FACE")
        assertNotNull(actual.lastError)
        assertEquals("RETRY_WAIT",actual.state)
    }

    @Test fun `job stranded by failure persistence must be recoverable without server restart`() {
        val id=seedJob("THUMBNAIL")
        val thumb=mock(ThumbnailService::class.java)
        doAnswer {
            transaction { exec("CREATE TRIGGER audit_fail_status BEFORE UPDATE ON jobs WHEN OLD.status='RUNNING' BEGIN SELECT RAISE(FAIL, 'audit status write failure'); END") }
            throw IllegalStateException("audit conversion failure")
        }.`when`(thumb).generate("a".repeat(64),"source.jpg","PHOTO")
        val worker=ThumbnailWorker(thumb,props,queue,null,activity); closers += { worker.shutdown() }
        worker.tick(); eventually { monitor.snapshot("THUMBNAIL").error!=null && activity.count()==0 }
        transaction { exec("DROP TRIGGER audit_fail_status") }
        reset(thumb)
        service.action("THUMBNAIL","restart")
        service.action("THUMBNAIL","retry")
        val current=transaction { Jobs.selectAll().where { Jobs.id eq id }.single()[Jobs.status] }
        assertEquals("PENDING",current)
        assertEquals(id,queue.claim("THUMBNAIL")?.jobId)
        queue.release(id,"THUMBNAIL")
    }

    @Test fun `unreadable monitor history must not disappear after an ordinary flush`() {
        val path=props.storageRoot.resolve("process-monitor/state.json")
        Files.createDirectories(path.parent); Files.writeString(path,"{broken-history")
        val next=ProcessMonitor(props,jacksonObjectMapper()); next.load(); assertNotNull(next.historyError)
        assertFalse(next.enter("FACE")); next.event("FACE","START","ordinary event"); next.flush()
        assertNotNull(next.historyError)
        assertEquals("{broken-history",Files.readString(path))
        assertFailsWith<IllegalStateException> { next.start("FACE") }
    }

    @Test fun `temporarily unavailable migration source must remain recoverable`() {
        val source=Files.createDirectory(temp.resolve("old-thumbs"))
        val hash="b".repeat(64); Files.writeString(source.resolve("${hash}_400.jpg"),"fixture")
        Files.createDirectories(props.thumbsDir)
        val thumbs=ThumbnailStorage(props,activity)
        monitor.stop("THUMB_RELOCATE"); thumbs.relocate(source)
        val offline=temp.resolve("offline-thumbs"); Files.move(source,offline)
        monitor.register("THUMB_RELOCATE",thumbs::resumeMigration)
        monitor.start("THUMB_RELOCATE"); assertTrue(activity.awaitIdle(Duration.ofSeconds(5)))
        val restarted=ThumbnailStorage(props,activity)
        monitor.register("THUMB_RELOCATE",restarted::resumeMigration)
        monitor.start("THUMB_RELOCATE"); assertTrue(activity.awaitIdle(Duration.ofSeconds(5)))
        Files.move(offline,source)
        monitor.start("THUMB_RELOCATE"); assertTrue(activity.awaitIdle(Duration.ofSeconds(5)))
        assertTrue(Files.exists(props.thumbsDir.resolve("bb/bb/${hash}_400.jpg")))
    }

    @Test fun `settings photo stop must survive server restart like monitor stop`() {
        photo.stop(); monitor.flush()
        assertEquals("STOPPED",item("PHOTO_INDEX").state)
        val nextMonitor=ProcessMonitor(props,jacksonObjectMapper()); nextMonitor.load()
        val next=PhotoSearchProcess(PhotoSearchProperties(true,temp.resolve("missing-model").toString()),props,
            mock(ThumbnailService::class.java),ServerActivity(nextMonitor))
        try {
            assertTrue(nextMonitor.snapshot("PHOTO_INDEX").paused)
        } finally { next.shutdown() }
    }

    @Test fun `temporary local caption HTTP outage must not exhaust photo retries`() {
        val called=CountDownLatch(1)
        val endpoint=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        endpoint.createContext("/v1/chat/completions") { exchange ->
            val body="service temporarily unavailable".toByteArray()
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(503,body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            called.countDown()
        }
        endpoint.start(); closers += { endpoint.stop(0) }
        props.caption=AppProperties.CaptionProperties(enabled=true,provider="LOCAL",baseUrl="http://127.0.0.1:${endpoint.address.port}")
        val id=seedJob("CAPTION")
        val image=temp.resolve("fixture.jpg")
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8,8,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpg",image.toFile())
        val thumb=mock(ThumbnailService::class.java)
        `when`(thumb.thumbPath("a".repeat(64),1600)).thenReturn(image)
        val worker=CaptionWorker(props,CaptionService(props),thumb,queue,activity); closers += { worker.close() }
        worker.tick()
        assertTrue(called.await(5,TimeUnit.SECONDS)); eventually { !worker.status().running }
        val row=transaction { Jobs.selectAll().where { Jobs.id eq id }.single() }
        assertEquals("PENDING",row[Jobs.status])
        assertEquals(0,row[Jobs.attempts])
        assertNotNull(monitor.snapshot("CAPTION").retryAt)
    }

    @Test fun `abandoned recovery never steals another active worker job`() {
        val first=seedJob("THUMBNAIL")
        val second=seedJob("THUMBNAIL","b".repeat(64))
        assertEquals(first,queue.claim("THUMBNAIL")!!.jobId)
        val pool=Executors.newSingleThreadExecutor()
        try {
            pool.submit {
                assertEquals(second,queue.claim("THUMBNAIL")!!.jobId)
                queue.finishWorker("THUMBNAIL")
            }.get(5,TimeUnit.SECONDS)
            val rows=transaction { Jobs.selectAll().associate { it[Jobs.id] to it[Jobs.status] } }
            assertEquals("RUNNING",rows[first]); assertEquals("PENDING",rows[second])
            queue.complete(first,"THUMBNAIL")
        } finally { pool.shutdownNow() }
    }
}
