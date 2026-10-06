package com.homephoto.server.document

import com.homephoto.server.search.CaptionTextSearch
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/admin/documents")
class DocumentController(private val worker: DocumentWorker, private val search: CaptionTextSearch) {
    @GetMapping("/status") fun status() = mapOf("worker" to worker.status(), "counts" to DocumentRepository.counts(),
        "index" to search.documentIndexStatus())
    data class Control(val enabled: Boolean)
    @PostMapping("/control") fun control(@RequestBody body: Control): Map<String, Boolean> {
        DocumentRepository.enabled(body.enabled)
        return mapOf("enabled" to body.enabled)
    }
    data class Enqueue(val mode: String = "missing", val limit: Int = 100, val assetId: Long? = null)
    @PostMapping("/enqueue") fun enqueue(@RequestBody body: Enqueue): Map<String,Int> {
        require(body.assetId==null || body.assetId>0)
        return mapOf("queued" to DocumentRepository.enqueue(body.mode,body.limit,body.assetId))
    }
    @GetMapping("/{id}") fun detail(@PathVariable id: Long) = DocumentRepository.detail(id)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"문서를 찾을 수 없습니다.")

    @GetMapping fun list(@RequestParam(defaultValue="documents") filter: String,
        @RequestParam(defaultValue="") q: String, @RequestParam(defaultValue="0") page: Int,
        @RequestParam(defaultValue="") type: String, @RequestParam(defaultValue="") from: String, @RequestParam(defaultValue="") to: String): Map<String,Any> {
        require(q.length<=200 && page in 0..100000)
        require(type.isEmpty() || type in DocumentAnalysis.types)
        listOf(from,to).filter { it.isNotEmpty() }.forEach { value ->
            require(runCatching { java.time.LocalDate.parse(value).toString()==value }.getOrDefault(false)) { "문서 날짜 형식을 확인하세요." }
        }
        require(from.isEmpty() || to.isEmpty() || from<=to) { "문서 날짜 범위를 확인하세요." }
        if(q.isBlank()) {
            val (total,items)=DocumentRepository.list(filter,page,type,from,to)
            return mapOf("total" to total,"items" to items,"candidateLimited" to false,"semanticState" to "idle")
        }
        require(filter=="documents") { "문서 결과에서 검색해 주세요." }
        val lexical=DocumentRepository.lexical(q.trim(),type,from,to)
        var state="available"
        val semantic=try { search.documentCandidates(q.trim(),type,from,to) } catch(_: Exception) { state="unavailable"; emptyList() }
        val ranked=rrf(lexical,semantic)
        // Never trust cached/index visibility. Deleted or reclassified results are removed at response time.
        val current=DocumentRepository.items(ranked).associateBy { it.assetId }
        val items=ranked.mapNotNull { current[it] }
        return mapOf("total" to items.size,"items" to items.drop(page*40).take(40),
            "candidateLimited" to true,"semanticState" to state)
    }
    companion object {
        fun rrf(lexical: List<Long>, semantic: List<Long>): List<Long> {
            val scores=mutableMapOf<Long,Double>()
            listOf(lexical,semantic).forEach { ranking -> ranking.distinct().forEachIndexed { index,id ->
                scores[id]=(scores[id] ?: 0.0)+1.0/(60+index+1)
            } }
            return scores.keys.sortedWith(compareByDescending<Long> { scores[it] }.thenByDescending { it })
        }
    }
}
