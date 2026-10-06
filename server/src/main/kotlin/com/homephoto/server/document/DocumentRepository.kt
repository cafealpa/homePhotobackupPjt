package com.homephoto.server.document

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.ResultSet

/** Every public operation uses a short SQLite transaction; no network/model work occurs here. */
object DocumentRepository {
    data class Item(val assetId: Long, val filename: String, val classification: String, val type: String,
        val title: String, val summary: String, val date: String?, val issuer: String, val needsReview: Boolean,
        val reviewReason: String, val analyzedAt: String?, val status: String, val error: String?,
        val revision: Long, val indexedRevision: Long, val chunks: Int)
    data class Source(val id: Long, val text: String, val revision: Long, val type: String, val date: String?)
    data class Change(val id: Long, val version: Long)
    const val ACTIVE = "a.media_type='PHOTO' AND a.deleted_at IS NULL AND a.purged_at IS NULL"
    private val columns = "a.id,a.original_filename,d.*,COALESCE(j.status,'NONE') AS job_status,j.last_error"
    private val joins = "assets a LEFT JOIN document_analysis d ON d.asset_id=a.id LEFT JOIN jobs j ON j.asset_id=a.id AND j.job_type='DOCUMENT'"
    private fun args(values: List<String>) = values.map { TextColumnType() to it }
    private fun Transaction.run(sql: String, values: List<String> = emptyList()) { exec(sql, args = args(values)) }
    private fun item(r: ResultSet) = Item(r.getLong("id"), r.getString("original_filename"),
        r.getString("classification") ?: "UNKNOWN", r.getString("document_type") ?: "OTHER",
        r.getString("title") ?: "", r.getString("summary") ?: "", r.getString("document_date"),
        r.getString("issuer") ?: "", r.getBoolean("needs_review"), r.getString("review_reason") ?: "",
        r.getString("analyzed_at"), r.getString("job_status"), r.getString("last_error"),
        r.getLong("revision"), r.getLong("indexed_revision"), r.getInt("chunk_count"))

    fun enabled() = transaction { exec("SELECT enabled FROM document_control WHERE id=1") { it.next(); it.getBoolean(1) } ?: false }
    fun enabled(value: Boolean) = transaction { run("UPDATE document_control SET enabled=? WHERE id=1", listOf(if (value) "1" else "0")) }
    fun detected(id: Long, classification: String) = transaction {
        require(classification in DocumentAnalysis.classifications)
        run("INSERT OR IGNORE INTO document_analysis(asset_id,classification) VALUES(?,?)", listOf(id.toString(), classification))
        if (classification != "NOT_DOCUMENT") enqueueIds(listOf(id), false)
    }
    fun enqueue(mode: String, limit: Int, id: Long? = null): Int = transaction {
        require(mode in setOf("missing", "failed", "reanalyze") && limit in 1..500)
        require(mode != "reanalyze" || id != null) { "재분석할 사진을 지정하세요." }
        val condition = when(mode) {
            "missing" -> "d.asset_id IS NULL AND j.id IS NULL"
            "failed" -> "j.status='FAILED'"
            else -> "(j.id IS NULL OR j.status NOT IN ('RUNNING','PENDING'))"
        }
        val ids = exec("SELECT a.id FROM $joins WHERE $ACTIVE AND $condition ${if(id != null) "AND a.id=?" else ""} ORDER BY a.id DESC LIMIT $limit",
            args = args(if(id != null) listOf(id.toString()) else emptyList())) { r -> buildList { while(r.next()) add(r.getLong(1)) } }.orEmpty()
        enqueueIds(ids, true)
        ids.size
    }
    private fun Transaction.enqueueIds(ids: List<Long>, retry: Boolean) {
        ids.forEach { id -> run("""INSERT INTO jobs(asset_id,job_type,status,attempts,priority,updated_at)
            VALUES(?,'DOCUMENT','PENDING',0,0,strftime('%Y-%m-%dT%H:%M:%f','now'))
            ON CONFLICT(asset_id,job_type) DO ${if(retry) "UPDATE SET status='PENDING',attempts=0,last_error=NULL,updated_at=excluded.updated_at WHERE jobs.status NOT IN ('RUNNING','PENDING')" else "NOTHING"}""", listOf(id.toString())) }
    }
    fun save(id: Long, result: DocumentAnalysis, model: String) = transaction {
        val text = if(result.classification != "NOT_DOCUMENT") result.searchText() else ""
        run("""INSERT INTO document_analysis(asset_id,classification,document_type,title,summary,document_date,issuer,ocr_text,
            analysis_json,search_text,needs_review,review_reason,model,analyzed_at,revision)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,strftime('%Y-%m-%dT%H:%M:%f','now'),1)
            ON CONFLICT(asset_id) DO UPDATE SET classification=excluded.classification,document_type=excluded.document_type,
            title=excluded.title,summary=excluded.summary,document_date=excluded.document_date,issuer=excluded.issuer,
            ocr_text=excluded.ocr_text,analysis_json=excluded.analysis_json,search_text=excluded.search_text,
            needs_review=excluded.needs_review,review_reason=excluded.review_reason,model=excluded.model,
            analyzed_at=excluded.analyzed_at,revision=document_analysis.revision+1""",
            listOf(id.toString(), result.classification, result.type, result.title, result.summary, result.date.orEmpty(), result.issuer,
                result.ocrText, result.json, text, if(result.needsReview) "1" else "0", result.reviewReason, model))
        run("UPDATE document_analysis SET document_date=NULL WHERE asset_id=? AND document_date=''", listOf(id.toString()))
    }
    fun counts(): Map<String, Long> = transaction {
        val predicates = linkedMapOf("total" to "1=1", "unknown" to "d.asset_id IS NULL AND j.id IS NULL",
            "documents" to "d.classification='DOCUMENT' AND d.analyzed_at IS NOT NULL",
            "notDocument" to "d.classification='NOT_DOCUMENT'", "review" to "d.needs_review=1 OR d.classification='UNCERTAIN'",
            "pending" to "j.status='PENDING'", "running" to "j.status='RUNNING'", "failed" to "j.status='FAILED'",
            "indexPending" to "d.analyzed_at IS NOT NULL AND d.classification!='NOT_DOCUMENT' AND d.indexed_revision<d.revision",
            "indexed" to "d.analyzed_at IS NOT NULL AND d.classification!='NOT_DOCUMENT' AND d.indexed_revision=d.revision")
        val expressions = predicates.entries.joinToString(",") { "COALESCE(SUM(CASE WHEN (${it.value}) THEN 1 ELSE 0 END),0) AS ${it.key}" }
        exec("SELECT $expressions FROM $joins WHERE $ACTIVE") { r -> r.next(); predicates.keys.associateWith { r.getLong(it) } }!!
    }
    private fun condition(filter: String): String = when(filter) {
        "documents" -> "d.analyzed_at IS NOT NULL AND d.classification IN ('DOCUMENT','UNCERTAIN')"
        "pending" -> "j.status IN ('PENDING','RUNNING')"
        "failed" -> "j.status='FAILED'"
        "review" -> "d.needs_review=1 OR d.classification='UNCERTAIN'"
        "unknown" -> "d.asset_id IS NULL AND j.id IS NULL"
        "notDocument" -> "d.classification='NOT_DOCUMENT'"
        else -> throw IllegalArgumentException("지원하지 않는 문서 상태입니다.")
    }
    private fun constraints(type: String, from: String, to: String): Pair<String,List<String>> {
        val parts=mutableListOf<String>(); val values=mutableListOf<String>()
        if(type.isNotBlank()) { parts.add("d.document_type=?"); values.add(type) }
        if(from.isNotBlank()) { parts.add("d.document_date>=?"); values.add(from) }
        if(to.isNotBlank()) { parts.add("d.document_date<=?"); values.add(to) }
        return (if(parts.isEmpty()) "" else " AND "+parts.joinToString(" AND ")) to values
    }
    fun list(filter: String, page: Int, type: String = "", from: String = "", to: String = ""): Pair<Long,List<Item>> = transaction {
        require(page in 0..100000)
        val (extra,values)=constraints(type,from,to)
        val where = "$ACTIVE AND (${condition(filter)})$extra"
        val count = exec("SELECT COUNT(*) FROM $joins WHERE $where",args=args(values)) { it.next(); it.getLong(1) }!!
        val rows = exec("SELECT $columns FROM $joins WHERE $where ORDER BY a.id DESC LIMIT 40 OFFSET ${page.toLong()*40}",args=args(values)) {
            r -> buildList { while(r.next()) add(item(r)) }
        }.orEmpty()
        count to rows
    }
    fun items(ids: List<Long>): List<Item> = transaction {
        if(ids.isEmpty()) return@transaction emptyList()
        require(ids.size <= 400)
        exec("SELECT $columns FROM $joins WHERE $ACTIVE AND d.analyzed_at IS NOT NULL AND d.classification!='NOT_DOCUMENT' AND a.id IN (${ids.joinToString(",")})") {
            r -> buildList { while(r.next()) add(item(r)) }
        }.orEmpty()
    }
    fun detail(id: Long): Map<String,Any?>? = transaction {
        exec("SELECT $columns FROM $joins WHERE $ACTIVE AND a.id=?", args=args(listOf(id.toString()))) { r ->
            if(!r.next()) null else mapOf("item" to item(r), "ocrText" to r.getString("ocr_text"),
                "analysisJson" to r.getString("analysis_json"), "model" to r.getString("model"))
        }
    }
    /** Literal substring matching: user wildcards never turn into SQL patterns. */
    fun lexical(query: String, type: String = "", from: String = "", to: String = ""): List<Long> = transaction {
        val (extra,values)=constraints(type,from,to)
        val words = query.trim().split(Regex("\\s+")).distinct()
        fun escaped(s: String) = "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val terms = words.joinToString(" AND ") { "(d.search_text LIKE ? ESCAPE '\\' OR a.original_filename LIKE ? ESCAPE '\\')" }
        exec("SELECT a.id FROM $joins WHERE $ACTIVE AND d.analyzed_at IS NOT NULL AND d.classification!='NOT_DOCUMENT' AND $terms$extra ORDER BY CASE WHEN d.title LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END,a.id DESC LIMIT 200",
            args=args(words.flatMap { listOf(escaped(it),escaped(it)) } + values + escaped(query))) { r -> buildList { while(r.next()) add(r.getLong(1)) } }.orEmpty()
    }
    fun pending(): List<Change> = transaction {
        exec("SELECT asset_id,version FROM document_search_changes ORDER BY asset_id LIMIT 2") { r -> buildList { while(r.next()) add(Change(r.getLong(1),r.getLong(2))) } }.orEmpty()
    }
    fun source(id: Long): Source? = transaction {
        exec("SELECT d.* FROM document_analysis d JOIN assets a ON a.id=d.asset_id WHERE $ACTIVE AND d.analyzed_at IS NOT NULL AND d.classification!='NOT_DOCUMENT' AND a.id=?", args=args(listOf(id.toString()))) {
            r -> if(r.next()) Source(id,r.getString("search_text"),r.getLong("revision"),r.getString("document_type"),r.getString("document_date")) else null
        }
    }
    fun acknowledge(change: Change, source: Source?, chunks: List<String>) = transaction {
        val version = exec("SELECT version FROM document_search_changes WHERE asset_id=${change.id}") { if(it.next()) it.getLong(1) else null }
        if(version != change.version) return@transaction
        run("DELETE FROM document_search_chunks WHERE asset_id=${change.id}")
        chunks.forEachIndexed { i,text -> run("INSERT INTO document_search_chunks(asset_id,chunk_no,text,source_revision) VALUES(?,?,?,?)",
            listOf(change.id.toString(),i.toString(),text,source!!.revision.toString())) }
        if(source != null) run("UPDATE document_analysis SET indexed_revision=?,chunk_count=? WHERE asset_id=? AND revision=?",
            listOf(source.revision.toString(),chunks.size.toString(),source.id.toString(),source.revision.toString()))
        run("DELETE FROM document_search_changes WHERE asset_id=${change.id} AND version=${change.version}")
    }
    fun seedIndex() = transaction {
        run("INSERT INTO document_search_changes(asset_id) SELECT asset_id FROM document_analysis WHERE 1 ON CONFLICT(asset_id) DO UPDATE SET version=version+1")
        run("UPDATE document_analysis SET indexed_revision=0")
    }
}
