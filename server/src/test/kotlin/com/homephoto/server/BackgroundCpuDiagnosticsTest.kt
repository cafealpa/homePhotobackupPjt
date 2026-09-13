package com.homephoto.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.homephoto.server.config.BackgroundCpuDiagnostics
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import kotlin.test.assertTrue

class BackgroundCpuDiagnosticsTest {
    @Test
    @Timeout(15)
    fun `reports CPU consuming thread but stays quiet when idle`() {
        val bean = ManagementFactory.getThreadMXBean()
        assumeTrue(bean.isThreadCpuTimeSupported)
        val logger = LoggerFactory.getLogger(BackgroundCpuDiagnostics::class.java) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(events)
        val diagnostics = BackgroundCpuDiagnostics()
        try {
            diagnostics.start()
            assumeTrue(bean.isThreadCpuTimeEnabled)
            events.list.clear()
            diagnostics.sample()
            assertTrue(events.list.isEmpty(), "Idle sample should not emit periodic noise")
            val startCpu = bean.currentThreadCpuTime
            val deadline = System.nanoTime() + 10_000_000_000L
            while (bean.currentThreadCpuTime - startCpu < 1_100_000_000L) {
                check(System.nanoTime() < deadline) { "CPU measurement deadline exceeded" }
            }
            diagnostics.sample()
            assertTrue(events.list.any {
                it.level.toString() == "INFO" && it.formattedMessage.contains("thread=${Thread.currentThread().name}") &&
                    it.formattedMessage.contains("현재 위치=")
            }, "Busy thread and current stack must be visible at INFO")
        } finally {
            diagnostics.stop()
            logger.detachAppender(events)
            events.stop()
        }
    }
}
