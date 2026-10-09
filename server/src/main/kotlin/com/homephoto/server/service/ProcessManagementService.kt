package com.homephoto.server.service

import com.homephoto.server.config.AppProperties
import com.homephoto.server.document.DocumentRepository
import com.homephoto.server.document.DocumentWorker
import com.homephoto.server.publication.GooglePhotosPublicationQueue
import com.homephoto.server.search.CaptionTextSearch
import com.homephoto.server.search.PhotoSearchProcess
import com.homephoto.server.worker.*
import jakarta.annotation.PostConstruct
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

@Service
class ProcessManagementService(private val monitor: ProcessMonitor, private val activity: ServerActivity,
    private val props: AppProperties, private val queue: JobQueueService, private val incoming: IncomingUploadService,
    private val caption: CaptionWorker, private val face: FaceWorker, private val document: DocumentWorker,
    private val photoIndex: PhotoSearchProcess, private val textIndex: CaptionTextSearch,
    private val publications: GooglePhotosPublicationQueue, private val importer: ImportService,
    private val kidsnote: KidsnoteImportService, private val thumbnailStorage: ThumbnailStorage, private val trash: TrashService) {
    data class Counts(val done: Long=0, val pending: Long=0, val running: Long=0, val failed: Long=0,
        val total: Long?=null, val bytes: Long?=null, val retryAt: Long?=null, val lastError: String?=null,
        val pendingKnown: Boolean=true)
    data class Item(val id: String, val name: String, val state: String, val enabled: Boolean, val counts: Counts,
        val current: List<ProcessMonitor.Current>, val lastError: String?, val retryAt: Long?, val lastSuccessAt: Long?,
        val logs: List<ProcessMonitor.Event>, val canStart: Boolean, val canStop: Boolean, val canRestart: Boolean,
        val canRetry: Boolean, val note: String?, val location: String?=null, val waitingReason: String?=null)
    data class Summary(val at: Long, val draining: Boolean, val active: Int, val historyError: String?, val items: List<Item>)

    @PostConstruct fun register() {
        monitor.register("INCOMING") { incoming.retryPending(); monitor.clearIssue("INCOMING") }
        monitor.register("FACE",face::retryNow)
        monitor.register("CAPTION",caption::retryNow)
        monitor.register("TEXT_INDEX",textIndex::retryNow)
        monitor.register("IMPORT",importer::resumeLast)
        monitor.register("KIDSNOTE",kidsnote::resumeLast)
        monitor.register("PHOTO_INDEX") { photoIndex.start() }
        monitor.register("DOCUMENT") { DocumentRepository.enabled(true); document.retryNow() }
        monitor.register("THUMB_RELOCATE",thumbnailStorage::resumeMigration)
        monitor.register("THUMB_SHARD",thumbnailStorage::migrateLegacyThumbs)
        monitor.register("TRASH_CLEANUP",trash::purgeExpired)
    }
    fun summary(): Summary {
        val counts=counts()
        val photo=photoIndex.status(); val text=textIndex.status()
        val cap=caption.status()
        val docEnabled=DocumentRepository.enabled()
        val items=ProcessMonitor.NAMES.map { (id,name) ->
            val s=monitor.snapshot(id)
            val enabled=when(id) {
                "CAPTION" -> props.caption.enabled; "FACE" -> props.face.enabled
                "DOCUMENT" -> docEnabled; "GOOGLE_PHOTOS" -> props.googlePhotos.enabled
                "PHOTO_INDEX" -> photo.state != "disabled"; "TEXT_INDEX" -> textIndex.enabled
                else -> true
            }
            val c=when(id) {
                "IMPORT" -> importer.status().let { Counts((if(it.mode=="SCAN" && it.phase=="DONE") it.total else it.imported+it.duplicates).toLong(),
                    (if(it.phase=="DONE") 0 else (it.total-it.processed).coerceAtLeast(0)).toLong(),if(it.running) 1 else 0,it.failed.toLong(),
                    it.total.toLong().takeIf { _ -> it.phase != "SCANNING" },lastError=it.lastError ?: it.message.takeIf { _ -> it.phase=="ERROR" },pendingKnown=it.phase!="SCANNING") }
                "KIDSNOTE" -> kidsnote.status().let { Counts(it.processedDays.toLong(),
                    (it.totalDays-it.processedDays).coerceAtLeast(0).toLong(),if(it.running) 1 else 0,it.failed.toLong(),
                    null,lastError=it.lastError) }
                "PHOTO_INDEX" -> Counts(photo.indexedPhotos,failed=photo.failed,lastError=photo.lastError,pendingKnown=false)
                "TEXT_INDEX" -> (counts[id] ?: Counts(pendingKnown=false)).copy(done=text.indexed.toLong(),lastError=if(text.state in setOf("model_missing","unavailable"))
                    "텍스트 검색 모델 또는 인덱스를 준비하지 못했습니다." else null)
                "THUMB_RELOCATE", "THUMB_SHARD", "TRASH_CLEANUP" -> monitor.progress(id,Counts::class.java) ?: Counts(pendingKnown=false)
                else -> counts[id] ?: Counts(total=0)
            }
            val error=(c.lastError ?: s.error ?: if(id=="CAPTION") cap.error else null)?.let(ProcessMonitor::clean)
            val retryAt=(if(id in setOf("INCOMING","GOOGLE_PHOTOS")) c.retryAt else s.retryAt ?: c.retryAt)?.takeIf { it>0 }
            val explicitlyStopped=s.paused || (id=="PHOTO_INDEX" && photo.state in setOf("stopped","stopping")) ||
                (id=="DOCUMENT" && !docEnabled) || (id=="IMPORT" && importer.isStopping())
            val state=state(s,c,enabled,explicitlyStopped,retryAt,error)
            val waitingReason=when(state) {
                "RESTARTING" -> "현재 실행을 정리한 뒤 남은 작업을 재개합니다."
                "STOPPING" -> "진행 중인 파일·외부 호출을 안전하게 정리하고 있습니다."
                "WAITING_RESOURCE" -> s.active.mapNotNull { it.waitingFor }.distinct().joinToString(" · ")
                "RETRY_WAIT" -> "저장된 재시도 시각까지 대기합니다."
                "QUEUED" -> "작업이 등록돼 있습니다. 다음 처리 주기에 실행합니다."
                "STOPPED" -> "새 작업과 자동 재시도가 중지돼 있습니다."
                "DISABLED" -> "설정에서 기능이 비활성화돼 있습니다."
                "IDLE" -> if(c.pendingKnown) "처리할 대기 작업이 없습니다." else "다음 실행 또는 점검 주기를 기다립니다."
                else -> null
            }
            val configured=when(id) { "IMPORT" -> importer.canResume(); "KIDSNOTE" -> kidsnote.canResume(); else -> true }
            val allowed=!activity.draining && configured && (enabled || id=="DOCUMENT")
            val note=when {
                !configured -> "메인 화면에서 원본 폴더를 선택해 한 번 실행하면 같은 설정으로 재개할 수 있어요."
                id=="IMPORT" -> "중지 후 시작하면 마지막 폴더·모드로 다시 스캔하며 완료 파일은 중복 처리하지 않아요."
                id=="KIDSNOTE" -> "완료 수는 일자 폴더 기준이에요. 시작하면 마지막 폴더를 다시 확인해요."
                id=="GOOGLE_PHOTOS" -> "게시 결과 미확인(UNKNOWN)은 자동 재시도하지 않아요. Google Photos 화면에서 확인해 주세요."
                id=="INCOMING" -> "저장 실패 파일은 서버 로컬에 보관돼요. I/O 장애는 최대 30분 간격으로 계속 재시도해요. 수신 파일 유실(LOST)은 기기에서 다시 보내야 해요."
                id=="TRASH_CLEANUP" -> "설정된 보관 기간이 지난 휴지통 항목만 정리해요. 중지는 현재 항목 정리 후 적용돼요."
                id in setOf("THUMB_RELOCATE","THUMB_SHARD") -> "중지는 현재 파일 이동 후 적용돼요. 시작하면 남은 파일을 다시 확인해요."
                !enabled -> "설정에서 이 기능을 활성화해 주세요."
                id.endsWith("INDEX") -> "완료 수는 현재 인덱스 수예요. 전체 처리량을 확정할 수 없어 백분율은 표시하지 않아요."
                else -> null
            }
            Item(id,name,state,enabled,c,s.active,error,if(explicitlyStopped) null else retryAt,s.lastSuccessAt,s.logs.take(30),
                allowed && s.active.isEmpty() && state in setOf("STOPPED","IDLE","COMPLETED","NEEDS_ATTENTION"),
                !activity.draining && (!s.paused || s.restarting) && (enabled || s.active.isNotEmpty()),allowed && state !in setOf("STOPPING","RESTARTING"),
                allowed && !s.paused && id in RETRYABLE && (c.failed>0 || c.pending>0 || error!=null),note,
                if(id=="INCOMING") props.originalStorageRoot.toAbsolutePath().normalize().toString() else null,waitingReason)
        }
        return Summary(System.currentTimeMillis(),activity.draining,activity.count(),monitor.historyError,items)
    }
    fun action(id: String, action: String): Map<String,Any> {
        require(id in ProcessMonitor.NAMES) { "알 수 없는 작업입니다." }
        require(action in setOf("start","stop","restart","retry")) { "알 수 없는 작업 명령입니다." }
        if(activity.draining) throw ResponseStatusException(HttpStatus.CONFLICT,"서버 종료 준비 중입니다.")
        if(action=="stop") { monitor.stop(id); return mapOf("accepted" to true) }
        val item=summary().items.first { it.id==id }
        val allowed=when(action) { "start" -> item.canStart; "restart" -> item.canRestart; else -> item.canRetry }
        if(!allowed) throw ResponseStatusException(HttpStatus.CONFLICT,item.note ?: "현재 상태에서는 실행할 수 없습니다.")
        try {
            when(action) {
                "start" -> monitor.start(id)
                "restart" -> monitor.stop(id,restart=true)
                "retry" -> {
                    val count=when(id) {
                        "INCOMING" -> incoming.retryAll()
                        "GOOGLE_PHOTOS" -> publications.retryAll()
                        else -> queue.retryAll(id)
                    }
                    when(id) { "CAPTION" -> caption.retryNow(); "FACE" -> face.retryNow(); "DOCUMENT" -> document.retryNow() }
                    monitor.clearIssue(id); monitor.event(id,"RETRY","${count}개 재시도 요청 · 외부 서비스 제한 시간은 유지")
                    return mapOf("accepted" to true,"updated" to count)
                }
            }
        } catch(e: IllegalStateException) { throw ResponseStatusException(HttpStatus.CONFLICT,e.message) }
        return mapOf("accepted" to true)
    }
    fun all(action: String): Map<String,Any> {
        require(action in setOf("start","stop"))
        if(activity.draining) throw ResponseStatusException(HttpStatus.CONFLICT,"서버 종료 준비 중입니다.")
        if(action=="stop") { ProcessMonitor.NAMES.keys.forEach { monitor.stop(it) }; return mapOf("accepted" to true) }
        val failures=linkedMapOf<String,String>()
        summary().items.filter { it.state=="STOPPED" && it.canStart && it.enabled }.forEach {
            try { monitor.start(it.id) } catch(e: Exception) { failures[it.id]=ProcessMonitor.clean(e.message ?: "시작 실패") }
        }
        return mapOf("accepted" to failures.isEmpty(),"errors" to failures)
    }
    private fun counts(): Map<String,Counts> = transaction {
        val result=mutableMapOf<String,Counts>()
        exec("""SELECT j.job_type,j.status,COUNT(*) AS n FROM jobs j JOIN assets a ON a.id=j.asset_id
            WHERE a.deleted_at IS NULL AND a.purged_at IS NULL GROUP BY j.job_type,j.status""") { r ->
            val groups=mutableMapOf<String,MutableMap<String,Long>>()
            while(r.next()) groups.getOrPut(r.getString(1)) { mutableMapOf() }[r.getString(2)]=r.getLong(3)
            groups.forEach { (id,g) -> result[id]=Counts(g["DONE"]?:0,g["PENDING"]?:0,g["RUNNING"]?:0,g["FAILED"]?:0,g.values.sum()) }
        }
        exec("""SELECT j.job_type,j.last_error FROM jobs j JOIN assets a ON a.id=j.asset_id
            WHERE a.deleted_at IS NULL AND a.purged_at IS NULL AND j.status IN ('PENDING','FAILED')
              AND j.last_error IS NOT NULL ORDER BY j.updated_at,j.id""") { r ->
            while(r.next()) result[r.getString(1)]?.let { result[r.getString(1)]=it.copy(lastError=r.getString(2)) }
        }
        val textQueueExists=exec("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='caption_search_changes'") { it.next(); it.getLong(1)>0 } == true
        if(textQueueExists) exec("SELECT (SELECT COUNT(*) FROM caption_search_changes)+(SELECT COUNT(*) FROM document_search_changes)") {
            it.next(); result["TEXT_INDEX"]=Counts(pending=it.getLong(1))
        }
        exec("""SELECT status,COUNT(*),COALESCE(SUM(bytes),0),MIN(CASE WHEN status='PENDING' THEN next_attempt_at END)
            FROM incoming_uploads WHERE status!='CANCELLED' GROUP BY status""") { r ->
            var c=Counts(total=0,bytes=0)
            while(r.next()) {
                val s=r.getString(1); val n=r.getLong(2); val next=r.getLong(4).takeIf { !r.wasNull() && it>0 }
                c=c.copy(done=c.done+if(s=="DONE") n else 0,pending=c.pending+if(s=="PENDING") n else 0,
                    running=c.running+if(s=="RUNNING") n else 0,failed=c.failed+if(s in setOf("BLOCKED","LOST")) n else 0,
                    total=c.total!!+n,bytes=c.bytes!!+if(s!="DONE") r.getLong(3) else 0,retryAt=next ?: c.retryAt)
            }; result["INCOMING"]=c
        }
        exec("SELECT last_error FROM incoming_uploads WHERE status IN ('PENDING','BLOCKED','LOST') AND last_error IS NOT NULL ORDER BY next_attempt_at DESC LIMIT 1") {
            if(it.next()) result["INCOMING"]=result.getValue("INCOMING").copy(lastError=it.getString(1))
        }
        exec("SELECT status,COUNT(*),MIN(CASE WHEN status='PENDING' THEN next_attempt_at END) FROM google_photos_publications WHERE status!='CANCELLED' GROUP BY status") { r ->
            var c=Counts(total=0)
            while(r.next()) {
                val s=r.getString(1); val n=r.getLong(2); val next=r.getLong(3).takeIf { !r.wasNull() && it>0 }
                c=c.copy(done=c.done+if(s=="COMPLETED") n else 0,pending=c.pending+if(s=="PENDING") n else 0,
                    failed=c.failed+if(s in setOf("FAILED","AUTH_REQUIRED","UNKNOWN")) n else 0,
                    running=c.running+if(s in setOf("PREPARING_METADATA","UPLOADING","READY_TO_CREATE","CREATING_MEDIA_ITEM")) n else 0,
                    total=c.total!!+n,retryAt=next ?: c.retryAt)
            }; result["GOOGLE_PHOTOS"]=c
        }
        exec("SELECT last_error FROM google_photos_publications WHERE last_error IS NOT NULL AND status NOT IN ('COMPLETED','CANCELLED') ORDER BY updated_at DESC LIMIT 1") {
            if(it.next()) result["GOOGLE_PHOTOS"]=result.getValue("GOOGLE_PHOTOS").copy(lastError=it.getString(1))
        }
        result
    }
    companion object {
        private val RETRYABLE=setOf("INCOMING","THUMBNAIL","CAPTION","FACE","DOCUMENT","GOOGLE_PHOTOS")
        internal fun state(s: ProcessMonitor.Snapshot,c: Counts,enabled: Boolean,stopped: Boolean,retryAt: Long?,error: String?): String = when {
            s.restarting -> "RESTARTING"
            stopped -> if(s.active.isNotEmpty()) "STOPPING" else "STOPPED"
            s.active.isNotEmpty() -> if(s.active.all { it.waitingFor!=null }) "WAITING_RESOURCE" else "RUNNING"
            !enabled -> "DISABLED"
            retryAt != null && retryAt>System.currentTimeMillis() -> "RETRY_WAIT"
            error != null || c.failed>0 || c.running>0 -> "NEEDS_ATTENTION"
            c.pending>0 -> "QUEUED"
            c.done>0 && c.pendingKnown -> "COMPLETED"
            else -> "IDLE"
        }
    }
}
