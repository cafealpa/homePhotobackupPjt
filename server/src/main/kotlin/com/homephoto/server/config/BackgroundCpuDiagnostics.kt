package com.homephoto.server.config

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 작업 스케줄러가 긴 작업에 묶여 있어도 독립적으로 CPU 사용 스레드를 관찰한다. */
@Component
class BackgroundCpuDiagnostics {
    private val log = LoggerFactory.getLogger(javaClass)
    private val threads = ManagementFactory.getThreadMXBean()
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "background-cpu-diagnostics").apply { isDaemon = true }
    }
    private var previous = emptyMap<Long, Long>()
    private var sampledAt = System.nanoTime()

    @PostConstruct
    fun start() {
        if (!threads.isThreadCpuTimeSupported) {
            log.info("CPU 진단: JVM이 스레드 CPU 시간 측정을 지원하지 않습니다")
            return
        }
        runCatching {
            if (!threads.isThreadCpuTimeEnabled) threads.isThreadCpuTimeEnabled = true
            previous = snapshot()
            sampledAt = System.nanoTime()
            executor.scheduleWithFixedDelay({
                runCatching { sample() }.onFailure { log.warn("CPU 진단 실패: {}", it.message) }
            }, 30, 30, TimeUnit.SECONDS)
            log.info("CPU 진단 시작: 30초 간격, 구간 CPU 1초 이상 사용한 스레드 상위 5개 기록 (종료된 스레드/GC 제외)")
        }.onFailure { log.warn("CPU 진단 시작 실패: {}", it.message) }
    }

    private fun snapshot() = threads.allThreadIds.asSequence().mapNotNull { id ->
        threads.getThreadCpuTime(id).takeIf { it >= 0 }?.let { id to it }
    }.toMap()

    internal fun sample() {
        val now = System.nanoTime()
        val current = snapshot()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - sampledAt).coerceAtLeast(1)
        current.mapNotNull { (id, cpu) ->
            previous[id]?.let { id to TimeUnit.NANOSECONDS.toMillis(cpu - it) }
        }.filter { it.second >= 1000 }.sortedByDescending { it.second }.take(5).forEach { (id, cpuMs) ->
            val info = threads.getThreadInfo(id, 6) ?: return@forEach
            log.info("CPU 진단: thread={} id={} state={} cpu={}ms/{}ms (코어 1개 기준 {}%) 현재 위치={}",
                info.threadName, id, info.threadState, cpuMs, elapsedMs, cpuMs * 100 / elapsedMs,
                info.stackTrace.joinToString(" <- "))
        }
        previous = current
        sampledAt = now
    }

    @PreDestroy
    fun stop() { executor.shutdownNow() }
}
