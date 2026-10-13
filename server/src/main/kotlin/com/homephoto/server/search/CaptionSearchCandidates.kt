package com.homephoto.server.search

import org.springframework.stereotype.Service

/** 장면 설명을 우선하는 순위 결합. 각 검색 실패는 다른 검색과 단어 검색을 막지 않는다. */
@Service
class CaptionSearchCandidates(private val search: CaptionTextSearch, private val image: PhotoSemanticSearch) {
    data class Result(val ids: List<Long> = emptyList(), val state: String = "disabled",
                      val textState: String = state, val imageState: String = "disabled")
    private data class Entry(val expires: Long, val result: Result)
    private data class Key(val text: String, val assetIds: Set<Long>?, val revision: Long?, val textEnabled: Boolean, val imageEnabled: Boolean)
    private val cache = LinkedHashMap<Key, Entry>()

    fun status() = search.status()
    fun find(text: String): Result = find(text, null)

    @Synchronized
    fun find(text: String, assetIds: Set<Long>?): Result {
        val textEnabled = search.enabled
        val imageEnabled = image.enabled
        if (!textEnabled && !imageEnabled) return Result()
        val revision = if (!textEnabled) 0L else try { search.revision() } catch (_: Exception) { null }
        val key = Key(text, assetIds?.toSet(), revision, textEnabled, imageEnabled)
        val now = System.nanoTime()
        cache[key]?.takeIf { it.expires > now }?.let { return it.result }
        val scene = if (!textEnabled) Result() else if (revision == null) Result(state = "unavailable") else try {
            val ids = if (key.assetIds == null) search.captionCandidates(text) else search.captionCandidates(text, key.assetIds)
            Result(ids.distinct().take(200), "available")
        } catch (_: Exception) { Result(state = "unavailable") }
        val visual = if (!imageEnabled) Result() else try {
            val ids = if (key.assetIds == null) image.captionCandidates(text) else image.captionCandidates(text, key.assetIds)
            Result(ids.distinct().take(200), "available")
        } catch (_: Exception) { Result(state = "unavailable") }
        // 원시 유사도 대신 E5:SigLIP=2:1 가중 RRF를 사용하고 선택 인물 범위도 마지막에 확인한다.
        val scores = mutableMapOf<Long, Double>()
        listOf(scene.ids to 2.0, visual.ids to 1.0).forEach { (ids, weight) ->
            ids.forEachIndexed { rank, id ->
                if (key.assetIds == null || id in key.assetIds) scores[id] = (scores[id] ?: 0.0) + weight / (60 + rank + 1)
            }
        }
        val ids = scores.keys.sortedWith(compareByDescending<Long> { scores.getValue(it) }.thenBy { it }).take(200)
        val available = scene.state == "available" || visual.state == "available"
        val result = Result(ids, if (available) "available" else "unavailable", scene.state, visual.state)
        cache.entries.removeIf { it.value.expires <= now || it.key.revision != revision || it.key.textEnabled != textEnabled || it.key.imageEnabled != imageEnabled }
        if (cache.size >= 32) cache.remove(cache.keys.first())
        cache[key] = Entry(System.nanoTime() + 30_000_000_000L, result)
        return result
    }
}
