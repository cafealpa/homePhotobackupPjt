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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import kotlin.test.*

class ProcessControlIntegrationTest {
    @TempDir lateinit var temp: Path
    private lateinit var db: Database
    private lateinit var props: AppProperties
    private lateinit var monitor: ProcessMonitor
    private lateinit var activity: ServerActivity
    private lateinit var importer: ImportService
    private lateinit var kidsnote: KidsnoteImportService
    private lateinit var storage: FileSystemAdapter
    private lateinit var service: ProcessManagementService

    @BeforeEach fun setup() {
        db=Database.connect("jdbc:sqlite:${temp.resolve("test.db")}",driver="org.sqlite.JDBC")
        DatabaseMigrations().migrate()
        props=AppProperties(temp.resolve("storage"),"test",caption=AppProperties.CaptionProperties(enabled=true),face=AppProperties.FaceProperties(enabled=true))
        monitor=ProcessMonitor(props,jacksonObjectMapper()); activity=ServerActivity(monitor)
        storage=FileSystemAdapter(props)
        storage.initialize()
        importer=ImportService(mock(AssetIngestService::class.java),props,storage,activity)
        kidsnote=KidsnoteImportService(mock(AssetIngestService::class.java),props,jacksonObjectMapper(),activity)
        val photo=mock(PhotoSearchProcess::class.java)
        `when`(photo.status()).thenReturn(PhotoSearchProcess.Status("ready","ready"))
        val text=mock(CaptionTextSearch::class.java)
        `when`(text.enabled).thenReturn(true)
        `when`(text.status()).thenReturn(CaptionTextSearch.Status("ready",0,"test"))
        val caption=mock(CaptionWorker::class.java)
        `when`(caption.status()).thenReturn(CaptionWorker.Status(true,false,null,null,null,"LOCAL","test"))
        service=ProcessManagementService(monitor,activity,props,JobQueueService(activity),mock(IncomingUploadService::class.java),
            caption,mock(FaceWorker::class.java),mock(DocumentWorker::class.java),photo,text,
            mock(GooglePhotosPublicationQueue::class.java),importer,kidsnote,ThumbnailStorage(props,activity),mock(TrashService::class.java))
        service.register()
    }
    @AfterEach fun cleanup() {
        activity.begin(); assertTrue(activity.awaitIdle(Duration.ofSeconds(5)))
        monitor.close(); TransactionManager.closeAndUnregister(db)
    }
    private fun asset(name: String): Long = transaction {
        Assets.insert {
            it[hash]=name; it[mediaType]="PHOTO"; it[originalPath]=name; it[originalFilename]=name
            it[fileSize]=1; it[takenAtSource]="UPLOAD_TIME"; it[yearMonth]="2026-10"; it[createdAt]="2026-10-09T00:00:00"
        }[Assets.id]
    }
    private fun job(id: Long,type: String,status: String,error: String?=null) = transaction {
        Jobs.insert { it[assetId]=id; it[jobType]=type; it[Jobs.status]=status; it[attempts]=2
            it[lastError]=error; it[updatedAt]="2026-10-09T00:00:00" }
    }
    private fun item(id: String)=service.summary().items.single { it.id==id }

    @Test fun `every registered process appears and DB failures survive application restart`() {
        val id=asset("one"); job(id,"FACE","FAILED","face error"); job(id,"CAPTION","RUNNING")
        StartupJobRecovery().recover()
        assertEquals(ProcessMonitor.NAMES.keys,service.summary().items.map { it.id }.toSet())
        assertEquals("NEEDS_ATTENTION",item("FACE").state)
        assertEquals("face error",item("FACE").lastError)
        assertEquals(1L,item("FACE").counts.failed)
        assertEquals("QUEUED",item("CAPTION").state)
        transaction { assertEquals(2,Jobs.selectAll().first { it[Jobs.jobType]=="FACE" }[Jobs.attempts]) }
        service.action("FACE","stop"); assertEquals("STOPPED",item("FACE").state)
        service.action("FACE","start"); assertEquals("NEEDS_ATTENTION",item("FACE").state)
        service.action("FACE","retry"); assertEquals("QUEUED",item("FACE").state)
    }

    @Test fun `text index pending is unknown before initialization and accurate afterwards`() {
        assertFalse(item("TEXT_INDEX").counts.pendingKnown)
        assertFalse(item("PHOTO_INDEX").counts.pendingKnown)
        CaptionIndexQueue.initialize(false)
        val id=asset("text")
        transaction { exec("INSERT INTO caption_search_changes(asset_id) VALUES($id)") }
        assertTrue(item("TEXT_INDEX").counts.pendingKnown)
        assertEquals(1L,item("TEXT_INDEX").counts.pending)
        assertEquals("QUEUED",item("TEXT_INDEX").state)
    }

    @Test fun `import request and final progress can be restored and resumed after server restart`() {
        val source=Files.createDirectory(temp.resolve("source"))
        Files.write(source.resolve("photo.jpg"),byteArrayOf(1))
        assertTrue(importer.start(source.toString(),"SCAN"))
        assertTrue(activity.awaitIdle(Duration.ofSeconds(5)))
        assertEquals("DONE",importer.status().phase)
        assertEquals("COMPLETED",item("IMPORT").state)
        monitor.flush()
        val restored=ProcessMonitor(props,jacksonObjectMapper()); restored.load()
        val restoredActivity=ServerActivity(restored)
        val next=ImportService(mock(AssetIngestService::class.java),props,storage,restoredActivity); next.restore()
        assertTrue(next.canResume()); assertEquals(1,next.status().total)
        assertEquals("SCAN",next.status().mode)
        next.resumeLast(); assertTrue(restoredActivity.awaitIdle(Duration.ofSeconds(5)))
        assertEquals("DONE",next.status().phase)
        restored.close()
    }

    @Test fun `kidsnote restart waits for prior worker and preserves its saved source`() {
        val root=Files.createDirectory(temp.resolve("kidsnote"))
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        monitor.remember("KIDSNOTE",mapOf("path" to root.toString())); kidsnote.restore()
        assertTrue(activity.launch("KIDSNOTE","controlled-import") { entered.countDown(); release.await(5,TimeUnit.SECONDS) })
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            service.action("KIDSNOTE","restart")
            assertEquals("RESTARTING",item("KIDSNOTE").state)
            assertFalse(item("KIDSNOTE").canStart)
        } finally { release.countDown() }
        assertTrue(activity.awaitIdle(Duration.ofSeconds(5)))
        assertFalse(monitor.snapshot("KIDSNOTE").paused)
        assertNull(monitor.snapshot("KIDSNOTE").error)
        assertTrue(monitor.snapshot("KIDSNOTE").logs.any { it.level=="DONE" })
        monitor.flush()
        val restored=ProcessMonitor(props,jacksonObjectMapper()); restored.load()
        val next=KidsnoteImportService(mock(AssetIngestService::class.java),props,jacksonObjectMapper(),ServerActivity(restored))
        next.restore(); assertTrue(next.canResume()); assertFalse(next.status().running)
        restored.close()
    }
}
