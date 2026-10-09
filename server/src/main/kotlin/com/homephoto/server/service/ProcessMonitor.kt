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
    data class Current(val item: String?, val stage: String, val startedAt: Long, val stageAt: Long,
        val waitingFor: String? = null)
    data class Snapshot(val paused: Boolean, val restarting: Boolean, val active: List<Current>,
        val error: String?, val retryAt: Long?, val lastSuccessAt: Long?, val logs: List<Event>)
    private class State {
        var paused = false; var restarting = false
        var generation = 0L
        var error: String? = null; var retryAt: Long? = null; var lastSuccessAt: Long? = null
        var request: Map<String,String> = emptyMap()
        var progress: com.fasterxml.jackson.databind.JsonNode? = null
        val tokens = mutableSetOf<Token>(); val logs = ArrayDeque<Event>()
    }
    internal class Token(val owner: ProcessMonitor, val id: String, var thread: Thread? = null) {
        @Volatile var cancelled = false
        var interruptible = false
        var current = Current(null, "작업 준비", System.currentTimeMillis(), System.currentTimeMillis())
    }
    private val lock = Any()
    private val cpu = java.util.concurrent.Semaphore(props.backgroundCpuTasks.also { require(it in 1..64) }, true)
    private val states = NAMES.keys.associateWith { State() }
    private val controls = NAMES.keys.associateWith { Any() }
    private val starters = mutableMapOf<String, () -> Unit>()
    private data class StartRequest(val id: String,val generation: Long,val action: () -> Unit)
    private fun startRequest(id: String) = starters[id]?.let { StartRequest(id,states.getValue(id).generation,it) }
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
                        s.error = node.path("error").takeIf { it.isTextual }?.asText()?.let(::clean)
                        s.retryAt = node.path("retryAt").takeIf { it.isNumber }?.asLong()
                        s.request = node.path("request").properties().associate { it.key to it.value.asText() }
                        s.progress = node.path("progress").takeIf { it.isObject }
                        val progress=node.path("progress")
                        if ((id=="IMPORT" && progress.path("phase").asText() in setOf("CANCELLED","SCANNING","IMPORTING")) ||
                            (id=="KIDSNOTE" && (progress.path("running").asBoolean() || progress.path("totalDays").asLong()>progress.path("processedDays").asLong()))) s.paused=true
                        if (node.path("active").asBoolean()) {
                            eventLocked(id,"RECOVER","서버 종료로 끊긴 작업 · 저장된 결과를 확인해 남은 작업을 복구합니다.")
                            if (id in setOf("IMPORT","KIDSNOTE")) {
                                s.paused = true
                                eventLocked(id,"STOP","이전 가져오기 설정과 진행 기록 복구 · 시작 버튼으로 재개할 수 있어요.")
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { historyError = "이전 작업 기록을 읽지 못했습니다. process-monitor/state.json을 확인하세요." }
    }

    fun register(id: String, start: () -> Unit) = synchronized(lock) { require(id in states); starters[id] = start }
    fun blocked(id: String): Boolean = synchronized(lock) { draining || states.getValue(id).paused || context.get()?.cancelled == true }
    internal fun reserve(id: String): Token? = synchronized(lock) {
        val s = states.getValue(id)
        if (draining || s.paused) return null
        Token(this, id).also { s.tokens.add(it); revision++ }
    }
    internal fun attach(token: Token) = synchronized(lock) {
        check(context.get() == null) { "nested process scope" }
        token.thread=Thread.currentThread(); context.set(token)
    }
    fun enter(id: String): Boolean {
        check(context.get() == null) { "nested process scope" }
        val token=reserve(id) ?: return false
        attach(token); return true
    }
    fun leave() {
        val token = context.get() ?: return
        context.remove()
        val starter = synchronized(lock) {
            val s = states.getValue(token.id); s.tokens.remove(token); revision++
            if (s.tokens.isEmpty() && s.paused) eventLocked(token.id, "STOP", "실행 중 작업 정리 완료 · 대기 항목 보존")
            if (s.tokens.isEmpty() && s.restarting && !draining) {
                s.restarting = false; s.paused = false; revision++
                eventLocked(token.id, "START", "중지 완료 후 남은 작업 재개")
                startRequest(token.id)
            } else null
        }
        starter?.let { runCatching { invokeStarter(it) } } // 오류는 기록하되 워커 finally 정리를 방해하지 않는다.
    }
    fun stop(id: String, restart: Boolean = false): Unit = synchronized(controls.getValue(id)) {
        val starter = synchronized(lock) {
            check(!restart || !draining) { "서버 종료 준비 중에는 재시작할 수 없습니다." }
            val s = states.getValue(id); s.generation++; s.paused = true; s.restarting = restart; revision++
            eventLocked(id, "STOP", if (restart) "중지 후 재시작 요청" else "새 작업·자동 재시도 중지 요청")
            s.tokens.forEach { it.cancelled = true; if (it.interruptible) it.thread?.interrupt() }
            if (restart && s.tokens.isEmpty()) {
                s.paused = false; s.restarting = false
                eventLocked(id,"START","실행 중 작업 없음 · 남은 작업 재개")
                startRequest(id)
            } else null
        }
        try { starter?.let { invokeStarter(it) } } finally { flush() }
    }
    fun start(id: String): Unit = synchronized(controls.getValue(id)) {
        val starter = synchronized(lock) {
            check(!draining) { "서버 종료 준비 중입니다." }
            val s = states.getValue(id)
            check(s.tokens.none { it.cancelled }) { "현재 작업을 정리 중입니다. 중지 완료 후 시작하세요." }
            s.generation++; s.paused = false; s.restarting = false; revision++
            eventLocked(id, "START", "남은 작업 시작 요청"); startRequest(id)
        }
        try { starter?.let { invokeStarter(it) } } finally { flush() }
    }
    private fun invokeStarter(request: StartRequest): Unit = synchronized(controls.getValue(request.id)) {
        val id=request.id
        val valid=synchronized(lock) { states.getValue(id).let { !draining && !it.paused && it.generation==request.generation } }
        if(!valid) return@synchronized
        try { request.action() } catch (e: Exception) {
            synchronized(lock) { states.getValue(id).apply { paused=true; restarting=false }; revision++ }
            issue(id, "작업 시작 실패: ${e.javaClass.simpleName}"); throw e
        }
    }
    fun drain(value: Boolean) = synchronized(lock) {
        draining = value
        if (value) states.forEach { (id,s) ->
            s.restarting = false
            s.tokens.forEach { it.cancelled = true; if(it.interruptible) it.thread?.interrupt() }
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
    fun clearIssue(id: String) { synchronized(lock) { states.getValue(id).apply { error=null; retryAt=null }; revision++ } }
    fun request(id: String): Map<String,String> = synchronized(lock) { states.getValue(id).request }
    fun remember(id: String, request: Map<String,String>) {
        synchronized(lock) { states.getValue(id).request=request.toMap(); revision++ }
        flush()
        check(historyError == null) { historyError!! }
    }
    fun progress(id: String, value: Any) = synchronized(lock) {
        states.getValue(id).progress=mapper.valueToTree(value); revision++
    }
    fun <T> progress(id: String, type: Class<T>): T? = synchronized(lock) {
        states.getValue(id).progress?.let { mapper.treeToValue(it,type) }
    }
    @Scheduled(fixedDelay=5000) @Synchronized fun flush() {
        val (version, data) = synchronized(lock) {
            if(revision == savedRevision) return
            revision to mapOf("paused" to states.filterValues { it.paused }.keys,
                "processes" to states.mapValues { (_,s) -> mapOf("logs" to s.logs.toList(), "lastSuccessAt" to s.lastSuccessAt,
                    "error" to s.error, "retryAt" to s.retryAt, "active" to s.tokens.isNotEmpty(),
                    "request" to s.request, "progress" to s.progress) })
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
            "IMPORT" to "폴더 가져오기", "KIDSNOTE" to "키즈노트 가져오기",
            "THUMB_RELOCATE" to "썸네일 폴더 이전", "THUMB_SHARD" to "썸네일 폴더 정리", "TRASH_CLEANUP" to "휴지통 자동 정리")
        private val context = ThreadLocal<Token>()
        fun cancelled(): Boolean = context.get()?.cancelled == true
        fun checkpoint() { if(cancelled()) throw ProcessStoppedException() }
        fun stage(stage: String, item: String? = null) {
            val t=context.get() ?: return
            synchronized(t.owner.lock) {
                t.current=t.current.copy(stage=stage, item=item ?: t.current.item, stageAt=System.currentTimeMillis())
            }
        }
        /** 대기도 활성 작업으로 추적한다. 실행권 대기는 중지 가능하고 실제 계산은 안전 경계에서만 중지한다. */
        fun <T> waiting(reason: String, block: () -> T): T {
            val t=context.get() ?: return block()
            synchronized(t.owner.lock) { t.current=t.current.copy(waitingFor=reason) }
            try { return interruptible(block) }
            finally { synchronized(t.owner.lock) { t.current=t.current.copy(waitingFor=null) } }
        }
        fun <T> cpu(block: () -> T): T {
            val t=context.get() ?: return block()
            waiting("CPU 작업 실행 차례 대기") { t.owner.cpu.acquire() }
            try { checkpoint(); return block() } finally { t.owner.cpu.release() }
        }
        /** 이 범위에 네트워크 I/O/미디어 대기만 넣는다. DB와 확정된 외부 게시 처리에는 사용하지 않는다. */
        fun <T> interruptible(block: () -> T): T {
            val t=context.get() ?: return block()
            synchronized(t.owner.lock) { checkpoint(); t.interruptible=true }
            try { return block() }
            catch(e: Exception) { if(t.cancelled) throw ProcessStoppedException(); throw e }
            finally { synchronized(t.owner.lock) { t.interruptible=false; if(t.cancelled) Thread.interrupted() } }
        }
        fun <T> locked(lock: java.util.concurrent.locks.ReentrantLock, reason: String = "공유 자원 사용 대기", block: () -> T): T {
            waiting(reason) { lock.lockInterruptibly() }
            try { checkpoint(); return block() } finally { lock.unlock() }
        }
        fun clean(value: String): String = value
            .replace(Regex("(?i)(bearer\\s+)[^\\s,;]+"), "$1[비공개]")
            .replace(Regex("(?i)((?:api[_-]?key|token|password|secret)\\s*[:=]\\s*)[^\\s,;]+"), "$1[비공개]")
            .replace(Regex("https?://[^\\s]+"), "[외부 주소]").take(500)
    }
}
class ProcessStoppedException : RuntimeException("사용자 요청으로 작업을 중지했습니다.")
