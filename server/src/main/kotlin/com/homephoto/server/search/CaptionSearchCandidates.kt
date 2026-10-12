package com.homephoto.server.search

import org.springframework.stereotype.Service

/** Short bounded cache also backs off failures during the scene page's polling. */
@Service
class CaptionSearchCandidates(private val search: CaptionTextSearch) {
    data class Result(val ids: List<Long> = emptyList(), val state: String = "disabled")
    private data class Entry(val expires: Long, val result: Result)
    private data class Key(val text: String, val assetIds: Set<Long>?)
    private val cache = LinkedHashMap<Key, Entry>()

    fun status() = search.status()

    fun find(text: String): Result = find(text, null)

    @Synchronized
    fun find(text: String, assetIds: Set<Long>?): Result {
        if (!search.enabled) return Result()
        val key = Key(text, assetIds?.toSet())
        val now = System.nanoTime()
        cache[key]?.takeIf { it.expires > now }?.let { return it.result }
        val result = try {
            val ids = if (assetIds == null) search.captionCandidates(text) else search.captionCandidates(text, key.assetIds)
            Result(ids.distinct().take(200), "available")
        } catch (_: Exception) {
            Result(state = "unavailable")
        }
        cache.entries.removeIf { it.value.expires <= now }
        if (cache.size >= 32) cache.remove(cache.keys.first())
        cache[key] = Entry(System.nanoTime() + 30_000_000_000L, result)
        return result
    }
}
