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
