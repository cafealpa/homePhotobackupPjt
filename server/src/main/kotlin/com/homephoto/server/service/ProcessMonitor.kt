package com.homephoto.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.AppProperties
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.util.ArrayDeque

/** 작업 취소는 안전한 경계에서만 확인한다. DB 커밋·외부 게시 확정 중에는 interrupt하지 않는다. */
@Service
class ProcessMonitor(private val props: AppProperties, private val mapper: ObjectMapper) {
    data class Event(val at: Long, val level: String, val message: String, val count: Int = 1)
    data class Current(val item: String?, val stage: String, val startedAt: Long, val stageAt: Long)
    data class Snapshot(val paused: Boolean, val restarting: Boolean, val active: List<Current>,
        val error: String?, val retryAt: Long?, val lastSuccessAt: Long?, val logs: List<Event>)
    private class State {
        var paused = false; var restarting = false
        var error: String? = null; var retryAt: Long? = null; var lastSuccessAt: Long? = null
        val tokens = mutableSetOf<Token>(); val logs = ArrayDeque<Event>()
    }
    private class Token(val owner: ProcessMonitor, val id: String, val thread: Thread = Thread.currentThread()) {
        @Volatile var cancelled = false
        var interruptible = false
        var current = Current(null, "작업 준비", System.currentTimeMillis(), System.currentTimeMillis())
    }
    private val lock = Any()
    private val states = NAMES.keys.associateWith { State() }
    private val starters = mutableMapOf<String, () -> Unit>()
    private var draining = false
    private var revision = 0L
    private var savedRevision = 0L
    @Volatile final var historyError: String? = null; private set
    private val history = props.storageRoot.resolve("process-monitor/state.json")

    @PostConstruct fun load() {
        if (!Files.exists(history)) return
        try {
            val json = mapper.readTree(Files.readString(history))
            synchronized(lock) {
                json.path("paused").forEach { states[it.asText()]?.paused = true }
                json.path("processes").properties().forEach { (id, node) ->
                    states[id]?.let { s ->
                        node.path("logs").toList().takeLast(80).forEach { e -> s.logs.add(Event(e.path("at").asLong(),
                            e.path("level").asText(), clean(e.path("message").asText()), e.path("count").asInt(1))) }
                        s.lastSuccessAt = node.path("lastSuccessAt").takeIf { it.isNumber }?.asLong()
                    }
                }
            }
        } catch (_: Exception) { historyError = "이전 작업 기록을 읽지 못했습니다. process-monitor/state.json을 확인하세요." }
    }

    fun register(id: String, start: () -> Unit) = synchronized(lock) { require(id in states); starters[id] = start }
    fun blocked(id: String): Boolean = synchronized(lock) { draining || states.getValue(id).paused }
    fun enter(id: String): Boolean = synchronized(lock) {
        val s = states.getValue(id)
        if (draining || s.paused) return false
        check(context.get() == null) { "nested process scope" }
        val token = Token(this, id); s.tokens.add(token); context.set(token); true
    }
    fun leave() {
        val token = context.get() ?: return
        context.remove()
        val starter = synchronized(lock) {
            val s = states.getValue(token.id); s.tokens.remove(token)
            if (s.tokens.isEmpty() && s.paused) eventLocked(token.id, "STOP", "실행 중 작업 정리 완료 · 대기 항목 보존")
            if (s.tokens.isEmpty() && s.restarting && !draining) {
                s.restarting = false; s.paused = false; revision++
                eventLocked(token.id, "START", "중지 완료 후 남은 작업 재개")
                starters[token.id]
            } else null
        }
        starter?.let { runCatching { invokeStarter(token.id, it) } } // 오류는 기록하되 워커 finally 정리를 방해하지 않는다.
    }
    fun stop(id: String, restart: Boolean = false) {
        val starter = synchronized(lock) {
            check(!restart || !draining) { "서버 종료 준비 중에는 재시작할 수 없습니다." }
            val s = states.getValue(id); s.paused = true; s.restarting = restart; revision++
            eventLocked(id, "STOP", if (restart) "중지 후 재시작 요청" else "새 작업·자동 재시도 중지 요청")
            s.tokens.forEach { it.cancelled = true; if (it.interruptible) it.thread.interrupt() }
            if (restart && s.tokens.isEmpty()) { s.paused = false; s.restarting = false; starters[id] } else null
        }
        starter?.let { invokeStarter(id, it) }; flush()
    }
    fun start(id: String) {
        val starter = synchronized(lock) {
            check(!draining) { "서버 종료 준비 중입니다." }
            val s = states.getValue(id)
            check(s.tokens.none { it.cancelled }) { "현재 작업을 정리 중입니다. 중지 완료 후 시작하세요." }
            s.paused = false; s.restarting = false; revision++
            eventLocked(id, "START", "남은 작업 시작 요청"); starters[id]
        }
        starter?.let { invokeStarter(id, it) }; flush()
    }
    private fun invokeStarter(id: String, start: () -> Unit) {
        try { start() } catch (e: Exception) {
            synchronized(lock) { states.getValue(id).apply { paused=true; restarting=false }; revision++ }
            issue(id, "작업 시작 실패: ${e.javaClass.simpleName}"); throw e
        }
    }
    fun drain(value: Boolean) = synchronized(lock) {
        draining = value
        if (value) states.forEach { (id,s) ->
            s.restarting = false
            s.tokens.forEach { it.cancelled = true; if(it.interruptible) it.thread.interrupt() }
            if(s.tokens.isNotEmpty()) eventLocked(id,"STOP","서버 종료 준비 · 현재 작업 정리 요청")
        }
    }
    fun snapshot(id: String): Snapshot = synchronized(lock) { states.getValue(id).let { s ->
        Snapshot(s.paused || draining, s.restarting, s.tokens.map { it.current }, s.error, s.retryAt,
            s.lastSuccessAt, s.logs.toList().asReversed())
    } }
    fun event(id: String, level: String, message: String) = synchronized(lock) { eventLocked(id, level, message) }
    private fun eventLocked(id: String, level: String, message: String) {
        val s = states.getValue(id); val text = clean(message); val now = System.currentTimeMillis()
        val last = s.logs.peekLast()
        if(last?.message == text && last.level == level) { s.logs.removeLast(); s.logs.add(Event(now,level,text,last.count+1)) }
        else s.logs.add(Event(now,level,text))
        while(s.logs.size > 80) s.logs.removeFirst()
        revision++
    }
    fun issue(id: String, message: String, retryAt: Long? = null) = synchronized(lock) {
        val s=states.getValue(id); s.error=clean(message); s.retryAt=retryAt
        eventLocked(id, if(retryAt == null) "ERROR" else "RETRY", message)
    }
    fun success(id: String, message: String) = synchronized(lock) {
        val s=states.getValue(id); s.error=null; s.retryAt=null; s.lastSuccessAt=System.currentTimeMillis()
        eventLocked(id,"DONE",message)
    }
    fun clearIssue(id: String) { synchronized(lock) { states.getValue(id).apply { error=null; retryAt=null } } }
    @Scheduled(fixedDelay=5000) @Synchronized fun flush() {
        val (version, data) = synchronized(lock) {
            if(revision == savedRevision) return
            revision to mapOf("paused" to states.filterValues { it.paused }.keys,
                "processes" to states.mapValues { (_,s) -> mapOf("logs" to s.logs.toList(), "lastSuccessAt" to s.lastSuccessAt) })
        }
        try {
            val bytes=mapper.writeValueAsBytes(data)
            AtomicFiles.write(history) { Files.write(it,bytes) }
            savedRevision=version; historyError=null
        } catch (_: Exception) { historyError="작업 기록 저장 실패 · 서버 로컬 저장 공간과 권한을 확인하세요." }
    }
    @PreDestroy fun close() { drain(true); flush() }

    companion object {
        val NAMES = linkedMapOf("INCOMING" to "원본 저장", "THUMBNAIL" to "썸네일 생성", "FACE" to "얼굴 인식",
            "CAPTION" to "장면 분석", "DOCUMENT" to "문서 OCR", "PHOTO_INDEX" to "사진 검색 인덱싱",
            "TEXT_INDEX" to "문서·장면 검색 인덱싱", "GOOGLE_PHOTOS" to "Google Photos 전송",
            "IMPORT" to "폴더 가져오기", "KIDSNOTE" to "키즈노트 가져오기")
        private val context = ThreadLocal<Token>()
        fun cancelled(): Boolean = context.get()?.cancelled == true
        fun checkpoint() { if(cancelled()) throw ProcessStoppedException() }
        fun stage(stage: String, item: String? = null) {
            val t=context.get() ?: return
            synchronized(t.owner.lock) {
                t.current=t.current.copy(stage=stage, item=item ?: t.current.item, stageAt=System.currentTimeMillis())
            }
        }
        /** 이 범위에 네트워크 I/O/미디어 대기만 넣는다. DB와 확정된 외부 게시 처리에는 사용하지 않는다. */
        fun <T> interruptible(block: () -> T): T {
            val t=context.get() ?: return block()
            synchronized(t.owner.lock) { checkpoint(); t.interruptible=true }
            try { return block() }
            catch(e: Exception) { if(t.cancelled) throw ProcessStoppedException(); throw e }
            finally { synchronized(t.owner.lock) { t.interruptible=false; if(t.cancelled) Thread.interrupted() } }
        }
        fun <T> locked(lock: java.util.concurrent.locks.ReentrantLock, block: () -> T): T {
            interruptible { lock.lockInterruptibly() }
            try { checkpoint(); return block() } finally { lock.unlock() }
        }
        fun clean(value: String): String = value
            .replace(Regex("(?i)(bearer\\s+)[^\\s,;]+"), "$1[비공개]")
            .replace(Regex("(?i)((?:api[_-]?key|token|password|secret)\\s*[:=]\\s*)[^\\s,;]+"), "$1[비공개]")
            .replace(Regex("https?://[^\\s]+"), "[외부 주소]").take(500)
    }
}
class ProcessStoppedException : RuntimeException("사용자 요청으로 작업을 중지했습니다.")
