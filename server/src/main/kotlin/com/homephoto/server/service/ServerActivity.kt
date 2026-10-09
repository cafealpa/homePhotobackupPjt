package com.homephoto.server.service

import org.springframework.stereotype.Component
import java.time.Duration

/** HTTP 요청과 로컬 작업이 종료 준비와 경쟁하지 않도록 admission과 active count를 같은 락으로 관리한다. */
@Component
class ServerActivity(private val processes: ProcessMonitor? = null) {
    private val monitor = Object()
    @Volatile final var draining = false
        private set
    private var active = 0
    fun blocked(type: String): Boolean = draining || processes?.blocked(type) == true
    fun enter(type: String? = null): Boolean = synchronized(monitor) {
        if (draining || (type != null && processes?.enter(type) == false)) false else { active++; true }
    }
    fun leave(type: String? = null) {
        synchronized(monitor) { check(active > 0); active--; monitor.notifyAll() }
        if(type != null) processes?.leave()
    }
    fun event(type: String, level: String, message: String) { processes?.event(type,level,message) }
    fun issue(type: String, message: String, retryAt: Long? = null) { processes?.issue(type,message,retryAt) }
    fun success(type: String, message: String) { processes?.success(type,message) }
    fun count(): Int = synchronized(monitor) { active }
    fun begin(): Boolean = synchronized(monitor) {
        if (draining) false else { draining = true; processes?.drain(true); true }
    }
    fun resume() = synchronized(monitor) { draining = false; processes?.drain(false); monitor.notifyAll() }
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
