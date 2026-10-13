package com.homephoto.server.search

import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import kotlin.test.*

class CaptionSearchCandidatesTest {
    @Test fun `person membership is part of cache key including edits and empty groups`() {
        val search = mock(CaptionTextSearch::class.java)
        `when`(search.enabled).thenReturn(true)
        for (ids in listOf(setOf(1L), setOf(2L), setOf(1L, 3L), emptySet())) {
            `when`(search.captionCandidates("공원", ids)).thenReturn(ids.toList())
        }
        val service = CaptionSearchCandidates(search, mock(PhotoSemanticSearch::class.java))
        for (ids in listOf(setOf(1L), setOf(2L), setOf(1L, 3L), emptySet())) {
            repeat(2) { assertEquals(ids.toList(), service.find("공원", ids).ids) }
            verify(search, times(1)).captionCandidates("공원", ids)
        }
    }
    @Test fun `database changes and index commits invalidate cached candidates immediately`() {
        val search = mock(CaptionTextSearch::class.java)
        `when`(search.enabled).thenReturn(true)
        `when`(search.revision()).thenReturn(1L)
        `when`(search.captionCandidates("승연 자전거")).thenReturn(listOf(1L))
        val service = CaptionSearchCandidates(search, mock(PhotoSemanticSearch::class.java))
        assertEquals(listOf(1L), service.find("승연 자전거").ids)
        `when`(search.revision()).thenReturn(2L)
        `when`(search.captionCandidates("승연 자전거")).thenReturn(emptyList())
        assertTrue(service.find("승연 자전거").ids.isEmpty())
        `when`(search.revision()).thenReturn(3L)
        `when`(search.captionCandidates("승연 자전거")).thenReturn(listOf(2L))
        assertEquals(listOf(2L), service.find("승연 자전거").ids)
        verify(search, times(3)).captionCandidates("승연 자전거")
    }
    @Test fun `combines independent ranks prefers agreement and scene results and bounds the union`() {
        val search = mock(CaptionTextSearch::class.java); val image = mock(PhotoSemanticSearch::class.java)
        `when`(search.enabled).thenReturn(true); `when`(image.enabled).thenReturn(true)
        `when`(search.captionCandidates("자전거")).thenReturn(listOf(1L, 2L, 2L))
        `when`(image.captionCandidates("자전거")).thenReturn(listOf(2L, 3L, 3L))
        val service = CaptionSearchCandidates(search, image)
        val result = service.find("자전거")
        assertEquals(listOf(2L, 1L, 3L), result.ids)
        assertEquals("available", result.textState); assertEquals("available", result.imageState)
        assertEquals(result, service.find("자전거"))
        verify(search, times(1)).captionCandidates("자전거"); verify(image, times(1)).captionCandidates("자전거")
        `when`(search.captionCandidates("많은 사진")).thenReturn((1L..200L).toList())
        `when`(image.captionCandidates("많은 사진")).thenReturn((151L..350L).toList())
        val many = service.find("많은 사진").ids
        assertEquals(200, many.size); assertEquals(200, many.distinct().size)
        assertTrue(many.indexOf(151L) in 0 until 150) // 양쪽 일치는 가산되지만 낮은 순위가 항상 최상위가 되지는 않는다.
    }
    @Test fun `each provider can fail independently and enable toggles invalidate the combined cache`() {
        val search = mock(CaptionTextSearch::class.java); val image = mock(PhotoSemanticSearch::class.java)
        `when`(search.enabled).thenReturn(true); `when`(image.enabled).thenReturn(true)
        `when`(search.captionCandidates("공원")).thenReturn(listOf(1L))
        `when`(image.captionCandidates("공원")).thenThrow(PhotoSearchUnavailable())
        val service = CaptionSearchCandidates(search, image)
        assertEquals(listOf(1L), service.find("공원").ids)
        assertEquals("unavailable", service.find("공원").imageState)
        `when`(image.enabled).thenReturn(false)
        assertEquals("disabled", service.find("공원").imageState)
        `when`(image.enabled).thenReturn(true)
        doReturn(listOf(2L)).`when`(image).captionCandidates("공원")
        assertEquals(listOf(1L, 2L), service.find("공원").ids)
        `when`(search.enabled).thenReturn(false)
        assertEquals(listOf(2L), service.find("공원").ids)
        assertEquals("disabled", service.find("공원").textState)
        `when`(search.enabled).thenReturn(true)
        `when`(search.revision()).thenThrow(IllegalStateException("DB revision unavailable"))
        assertEquals(listOf(2L), service.find("공원").ids)
        assertEquals("unavailable", service.find("공원").textState)
        `when`(image.captionCandidates("둘 다 실패")).thenThrow(PhotoSearchUnavailable())
        assertEquals("unavailable", service.find("둘 다 실패").state)
    }
    @Test fun `person scope reaches both providers and an empty scope never widens results`() {
        val search = mock(CaptionTextSearch::class.java); val image = mock(PhotoSemanticSearch::class.java)
        `when`(search.enabled).thenReturn(true); `when`(image.enabled).thenReturn(true)
        val service = CaptionSearchCandidates(search, image)
        for (ids in listOf(setOf(1L, 2L), emptySet())) {
            `when`(search.captionCandidates("자전거", ids)).thenReturn(listOf(1L, 9L))
            `when`(image.captionCandidates("자전거", ids)).thenReturn(listOf(2L, 9L))
            assertEquals(ids.toList(), service.find("자전거", ids).ids)
            verify(search).captionCandidates("자전거", ids); verify(image).captionCandidates("자전거", ids)
        }
        verify(search, never()).captionCandidates("자전거"); verify(image, never()).captionCandidates("자전거")
    }

    @Test fun `disabled worker is never called`() {
        val search = mock(CaptionTextSearch::class.java)
        val result = CaptionSearchCandidates(search, mock(PhotoSemanticSearch::class.java)).find("공원")
        assertEquals("disabled", result.state)
        verify(search, never()).captionCandidates(anyString())
    }
    @Test fun `failures are cached and recoverable as text fallback`() {
        val search = mock(CaptionTextSearch::class.java)
        `when`(search.enabled).thenReturn(true)
        `when`(search.captionCandidates("공원")).thenThrow(PhotoSearchUnavailable())
        val service = CaptionSearchCandidates(search, mock(PhotoSemanticSearch::class.java))
        repeat(2) { assertEquals("unavailable", service.find("공원").state) }
        verify(search, times(1)).captionCandidates("공원")
    }
    @Test fun `success caches deduplicates bounds and evicts old queries`() {
        val search = mock(CaptionTextSearch::class.java)
        `when`(search.enabled).thenReturn(true)
        `when`(search.captionCandidates(anyString())).thenReturn(listOf(1L, 1L) + (2L..220L))
        val service = CaptionSearchCandidates(search, mock(PhotoSemanticSearch::class.java))
        repeat(2) { assertEquals((1L..200L).toList(), service.find("공원").ids) }
        verify(search, times(1)).captionCandidates("공원")
        repeat(32) { service.find("query-$it") }
        service.find("공원")
        verify(search, times(2)).captionCandidates("공원")
    }
}
