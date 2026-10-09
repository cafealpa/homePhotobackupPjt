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
        try { if(type != null) processes?.leave() }
        finally { synchronized(monitor) { check(active > 0); active--; monitor.notifyAll() } }
    }
    /** 요청 접수부터 스레드 종료까지 동일한 작업으로 추적해 시작 직후 중지/재시작의 빈틈을 없앤다. */
    fun launch(type: String, name: String, block: () -> Unit): Boolean {
        val token = synchronized(monitor) {
            if (draining) return false
            val reserved=processes?.reserve(type)
            if(processes != null && reserved == null) return false
            active++; reserved
        }
        processes?.flush()
        try {
            kotlin.concurrent.thread(name=name,isDaemon=true) {
                if(token != null) processes?.attach(token)
                try { block() } finally { leave(type); processes?.flush() }
            }
        } catch(e: Exception) {
            if(token != null) processes?.attach(token)
            leave(type); throw e
        }
        return true
    }
    fun request(type: String): Map<String,String> = processes?.request(type).orEmpty()
    fun retryWaiting(type: String): Boolean = (processes?.snapshot(type)?.retryAt ?: 0)>System.currentTimeMillis()
    fun clearIssue(type: String) { processes?.clearIssue(type) }
    fun remember(type: String, request: Map<String,String>) { processes?.remember(type,request) }
    fun progress(type: String, value: Any) { processes?.progress(type,value) }
    fun <T> progress(type: String, clazz: Class<T>): T? = processes?.progress(type,clazz)
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
