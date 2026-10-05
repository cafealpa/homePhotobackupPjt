package com.homephoto.server.search

import org.springframework.stereotype.Service

/** Short bounded cache also backs off failures during the scene page's polling. */
@Service
class CaptionSearchCandidates(private val search: CaptionTextSearch) {
    data class Result(val ids: List<Long> = emptyList(), val state: String = "disabled")
    private data class Entry(val expires: Long, val result: Result)
    private val cache = LinkedHashMap<String, Entry>()

    fun status() = search.status()

    @Synchronized
    fun find(text: String): Result {
        if (!search.enabled) return Result()
        val now = System.nanoTime()
        cache[text]?.takeIf { it.expires > now }?.let { return it.result }
        val result = try {
            Result(search.captionCandidates(text).distinct().take(200), "available")
        } catch (_: Exception) {
            Result(state = "unavailable")
        }
        cache.entries.removeIf { it.value.expires <= now }
        if (cache.size >= 32) cache.remove(cache.keys.first())
        cache[text] = Entry(System.nanoTime() + 30_000_000_000L, result)
        return result
    }
}
