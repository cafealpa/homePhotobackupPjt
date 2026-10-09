package com.homephoto.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ProcessMonitorTest {
    @TempDir lateinit var temp: Path
    private fun monitor()=ProcessMonitor(AppProperties(temp,"test"),ObjectMapper())

    @Test fun `CPU wait is visible cancellable and does not leak a permit`() {
        val monitor=ProcessMonitor(AppProperties(temp,"test",backgroundCpuTasks=1),ObjectMapper())
        val activity=ServerActivity(monitor)
        val acquired=CountDownLatch(1); val release=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        try {
            val first=pool.submit {
                check(activity.enter("THUMBNAIL"))
                try { ProcessMonitor.cpu { acquired.countDown(); check(release.await(5,TimeUnit.SECONDS)) } }
                finally { activity.leave("THUMBNAIL") }
            }
            assertTrue(acquired.await(3,TimeUnit.SECONDS))
            val second=pool.submit {
                check(activity.enter("FACE"))
                try { assertFailsWith<ProcessStoppedException> { ProcessMonitor.cpu { fail("cancelled waiter must not run") } } }
                finally { activity.leave("FACE") }
            }
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(monitor.snapshot("FACE").active.none { it.waitingFor!=null } && System.nanoTime()<deadline) Thread.sleep(5)
            assertEquals("CPU 작업 실행 차례 대기",monitor.snapshot("FACE").active.single().waitingFor)
            monitor.stop("FACE"); second.get(3,TimeUnit.SECONDS)
            assertTrue(monitor.snapshot("FACE").active.isEmpty())
            release.countDown(); first.get(3,TimeUnit.SECONDS)
            monitor.start("FACE")
            pool.submit { check(activity.enter("FACE")); try { ProcessMonitor.cpu { 42 } } finally { activity.leave("FACE") } }.get(3,TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow(); monitor.close() }
    }

    @Test fun `reserved task is tracked before worker starts and restart waits for its cleanup`() {
        val monitor=monitor(); val starts=AtomicInteger()
        monitor.register("IMPORT") { starts.incrementAndGet() }
        val token=monitor.reserve("IMPORT")!!
        assertEquals(1,monitor.snapshot("IMPORT").active.size)
        monitor.stop("IMPORT",restart=true)
        assertEquals(0,starts.get())
        monitor.attach(token)
        assertFailsWith<ProcessStoppedException> { ProcessMonitor.checkpoint() }
        monitor.leave()
        assertEquals(1,starts.get())
        assertFalse(monitor.snapshot("IMPORT").paused)
        monitor.close()
    }

    @Test fun `later stop wins over concurrent restart callback`() {
        val monitor=monitor(); val callback=CountDownLatch(1); val release=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(2)
        monitor.register("IMPORT") { callback.countDown(); check(release.await(5,TimeUnit.SECONDS)) }
        try {
            val restart=pool.submit { monitor.stop("IMPORT",restart=true) }
            assertTrue(callback.await(3,TimeUnit.SECONDS))
            val stop=pool.submit { monitor.stop("IMPORT") }
            release.countDown(); restart.get(3,TimeUnit.SECONDS); stop.get(3,TimeUnit.SECONDS)
            assertTrue(monitor.snapshot("IMPORT").paused)
            assertFalse(monitor.snapshot("IMPORT").restarting)
            assertFalse(monitor.enter("IMPORT"))
        } finally { release.countDown(); pool.shutdownNow(); monitor.close() }
    }

    @Test fun `thumbnail relocation preserves pending files while paused and resumes through monitor`() {
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val props=AppProperties(temp.resolve("data"),"test")
        val monitor=ProcessMonitor(props,mapper); val activity=ServerActivity(monitor)
        val storage=ThumbnailStorage(props,activity)
        val old=java.nio.file.Files.createDirectory(temp.resolve("old"))
        val hash="a".repeat(64)
        val source=java.nio.file.Files.writeString(old.resolve("${hash}_400.jpg"),"fixture")
        monitor.register("THUMB_RELOCATE",storage::resumeMigration)
        monitor.stop("THUMB_RELOCATE")
        storage.relocate(old)
        assertTrue(java.nio.file.Files.exists(source)); assertFalse(storage.migrationStatus().running)
        assertEquals(source,storage.thumbPath(hash,400))
        assertTrue(java.nio.file.Files.exists(source))
        monitor.start("THUMB_RELOCATE")
        assertTrue(activity.awaitIdle(Duration.ofSeconds(5)))
        assertEquals("fixture",java.nio.file.Files.readString(props.thumbsDir.resolve("aa/aa/${hash}_400.jpg")))
        assertFalse(java.nio.file.Files.exists(source))
        assertTrue(monitor.snapshot("THUMB_RELOCATE").logs.any { it.level=="DONE" })
        assertEquals(1L,monitor.progress("THUMB_RELOCATE",ProcessManagementService.Counts::class.java)!!.done)
        monitor.close()
    }

    @Test fun `restart recovers saved request progress error and retry time without reviving active threads`() {
        val mapper=com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val monitor=ProcessMonitor(AppProperties(temp,"test"),mapper)
        monitor.remember("IMPORT",mapOf("path" to "C:/photos","mode" to "COPY"))
        monitor.progress("IMPORT",ProcessManagementService.Counts(done=7,pending=3,total=10))
        val retryAt=System.currentTimeMillis()+60000
        monitor.issue("CAPTION","token=secret 연결 실패",retryAt)
        assertTrue(monitor.enter("IMPORT")); monitor.flush()
        val restored=ProcessMonitor(AppProperties(temp,"test"),mapper); restored.load()
        assertEquals("COPY",restored.request("IMPORT")["mode"])
        assertEquals(7L,restored.progress("IMPORT",ProcessManagementService.Counts::class.java)!!.done)
        assertTrue(restored.snapshot("IMPORT").paused)
        assertTrue(restored.snapshot("IMPORT").active.isEmpty())
        assertTrue(restored.snapshot("IMPORT").logs.any { it.level=="RECOVER" })
        assertEquals(retryAt,restored.snapshot("CAPTION").retryAt)
        assertFalse(restored.snapshot("CAPTION").error!!.contains("secret"))
        restored.clearIssue("CAPTION"); restored.flush()
        val clean=ProcessMonitor(AppProperties(temp,"test"),mapper); clean.load()
        assertNull(clean.snapshot("CAPTION").error)
        monitor.leave()
    }

    @Test fun `summary distinguishes queued idle resource wait retry stopped restarting and completed`() {
        val monitor=monitor()
        val snapshot=monitor.snapshot("FACE")
        fun state(c: ProcessManagementService.Counts=ProcessManagementService.Counts(),s: ProcessMonitor.Snapshot=snapshot)=
            ProcessManagementService.state(s,c,true,s.paused,s.retryAt,s.error)
        assertEquals("IDLE",state())
        assertEquals("QUEUED",state(ProcessManagementService.Counts(pending=4)))
        assertEquals("COMPLETED",state(ProcessManagementService.Counts(done=4)))
        assertEquals("NEEDS_ATTENTION",state(ProcessManagementService.Counts(running=1)))
        assertEquals("NEEDS_ATTENTION",state(ProcessManagementService.Counts(failed=1)))
        val active=listOf(ProcessMonitor.Current("photo","encode",0,0,"CPU 작업 실행 차례 대기"))
        assertEquals("WAITING_RESOURCE",state(s=snapshot.copy(active=active)))
        assertEquals("RETRY_WAIT",state(s=snapshot.copy(retryAt=System.currentTimeMillis()+60000)))
        assertEquals("STOPPING",state(s=snapshot.copy(paused=true,active=active)))
        assertEquals("RESTARTING",state(s=snapshot.copy(paused=true,restarting=true,active=active)))
        assertEquals("STOPPED",state(s=snapshot.copy(paused=true)))
        monitor.close()
    }

    @Test fun `stop interrupts only cancellable IO and clears interrupt before queue cleanup`() {
        val monitor=monitor();val activity=ServerActivity(monitor)
        val started=CountDownLatch(1);val pool=Executors.newSingleThreadExecutor()
        try {
            val result=pool.submit<Boolean> {
                check(activity.enter("INCOMING"))
                try {
                    assertFailsWith<ProcessStoppedException> { ProcessMonitor.interruptible { started.countDown(); Thread.sleep(30000) } }
                    assertFalse(Thread.currentThread().isInterrupted)
                    assertTrue(ProcessMonitor.cancelled())
                    true
                } finally { activity.leave("INCOMING") }
            }
            assertTrue(started.await(3,TimeUnit.SECONDS));monitor.stop("INCOMING")
            assertTrue(result.get(3,TimeUnit.SECONDS));assertEquals(0,activity.count())
            assertFalse(activity.enter("INCOMING"));assertTrue(monitor.snapshot("INCOMING").paused)
            monitor.start("INCOMING");assertTrue(activity.enter("INCOMING"));activity.leave("INCOMING")
        } finally { pool.shutdownNow();monitor.close() }
    }

    @Test fun `restart waits for every active task and never interrupts commit sections`() {
        val monitor=monitor();val activity=ServerActivity(monitor);val starts=AtomicInteger()
        val entered=CountDownLatch(2);val release=CountDownLatch(1);val pool=Executors.newFixedThreadPool(2)
        monitor.register("THUMBNAIL") { starts.incrementAndGet() }
        try {
            val work=(1..2).map { pool.submit {
                check(activity.enter("THUMBNAIL"));entered.countDown()
                try { check(release.await(3,TimeUnit.SECONDS));assertFalse(Thread.currentThread().isInterrupted) }
                finally { activity.leave("THUMBNAIL") }
            } }
            assertTrue(entered.await(3,TimeUnit.SECONDS));monitor.stop("THUMBNAIL",restart=true)
            assertEquals(0,starts.get());assertTrue(monitor.snapshot("THUMBNAIL").restarting)
            assertFailsWith<IllegalStateException> { monitor.start("THUMBNAIL") }
            release.countDown();work.forEach { it.get(3,TimeUnit.SECONDS) }
            assertEquals(1,starts.get());assertFalse(monitor.snapshot("THUMBNAIL").paused)
        } finally { release.countDown();pool.shutdownNow();monitor.close() }
    }

    @Test fun `pause and deduplicated history survive server restart without secrets`() {
        val monitor=monitor()
        repeat(3) { monitor.issue("INCOMING","token=abc123 저장 실패 https://example.test/path?secret=xyz") }
        monitor.stop("INCOMING");monitor.close()
        val restored=monitor();restored.load()
        val snapshot=restored.snapshot("INCOMING")
        assertTrue(snapshot.paused);assertTrue(snapshot.logs.any { it.count==3 })
        assertTrue(snapshot.logs.none { "abc123" in it.message || "xyz" in it.message })
        assertFalse(restored.enter("INCOMING"));restored.start("INCOMING")
        assertTrue(restored.enter("INCOMING"));restored.leave();restored.close()
    }

    @Test fun `shutdown cancellation keeps explicit pauses after cancelled shutdown`() {
        val monitor=monitor();val activity=ServerActivity(monitor)
        monitor.stop("FACE")
        assertTrue(activity.enter("CAPTION"));assertTrue(activity.begin())
        assertTrue(ProcessMonitor.cancelled());assertFalse(activity.enter())
        assertFalse(activity.awaitIdle(Duration.ofMillis(5)))
        activity.leave("CAPTION");assertTrue(activity.awaitIdle(Duration.ofMillis(100)))
        activity.resume();assertFalse(activity.enter("FACE"))
        assertTrue(activity.enter("CAPTION"));activity.leave("CAPTION");monitor.close()
    }

    @Test fun `failed restart callback leaves process paused without blocking worker cleanup`() {
        val monitor=monitor();val activity=ServerActivity(monitor)
        monitor.register("IMPORT") { error("cannot resume") }
        assertTrue(activity.enter("IMPORT"))
        monitor.stop("IMPORT",restart=true)
        activity.leave("IMPORT")
        assertEquals(0,activity.count())
        assertTrue(monitor.snapshot("IMPORT").paused)
        assertNotNull(monitor.snapshot("IMPORT").error)
        assertTrue(activity.enter("CAPTION"));activity.leave("CAPTION")
        monitor.close()
    }

}
