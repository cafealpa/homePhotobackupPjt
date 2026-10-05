package com.homephoto.server.service

import org.springframework.stereotype.Component
import java.time.Duration

/** HTTP 요청과 로컬 작업이 종료 준비와 경쟁하지 않도록 admission과 active count를 같은 락으로 관리한다. */
@Component
class ServerActivity {
    private val monitor = Object()
    @Volatile final var draining = false
        private set
    private var active = 0
    fun enter(): Boolean = synchronized(monitor) {
        if (draining) false else { active++; true }
    }
    fun leave() = synchronized(monitor) { check(active > 0); active--; monitor.notifyAll() }
    fun count(): Int = synchronized(monitor) { active }
    fun begin(): Boolean = synchronized(monitor) {
        if (draining) false else { draining = true; true }
    }
    fun resume() = synchronized(monitor) { draining = false; monitor.notifyAll() }
    fun awaitIdle(timeout: Duration): Boolean = synchronized(monitor) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (active > 0) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@synchronized false
            monitor.wait((remaining / 1_000_000).coerceAtLeast(1))
        }
        true
    }
}
