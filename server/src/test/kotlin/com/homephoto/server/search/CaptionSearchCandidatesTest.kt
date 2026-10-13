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
        val service = CaptionSearchCandidates(search)
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
        val service = CaptionSearchCandidates(search)
        assertEquals(listOf(1L), service.find("승연 자전거").ids)
        `when`(search.revision()).thenReturn(2L)
        `when`(search.captionCandidates("승연 자전거")).thenReturn(emptyList())
        assertTrue(service.find("승연 자전거").ids.isEmpty())
        `when`(search.revision()).thenReturn(3L)
        `when`(search.captionCandidates("승연 자전거")).thenReturn(listOf(2L))
        assertEquals(listOf(2L), service.find("승연 자전거").ids)
        verify(search, times(3)).captionCandidates("승연 자전거")
    }
    @Test fun `disabled worker is never called`() {
        val search = mock(CaptionTextSearch::class.java)
        val result = CaptionSearchCandidates(search).find("공원")
        assertEquals("disabled", result.state)
        verify(search, never()).captionCandidates(anyString())
    }
    @Test fun `failures are cached and recoverable as text fallback`() {
        val search = mock(CaptionTextSearch::class.java)
        `when`(search.enabled).thenReturn(true)
        `when`(search.captionCandidates("공원")).thenThrow(PhotoSearchUnavailable())
        val service = CaptionSearchCandidates(search)
        repeat(2) { assertEquals("unavailable", service.find("공원").state) }
        verify(search, times(1)).captionCandidates("공원")
    }
    @Test fun `success caches deduplicates bounds and evicts old queries`() {
        val search = mock(CaptionTextSearch::class.java)
        `when`(search.enabled).thenReturn(true)
        `when`(search.captionCandidates(anyString())).thenReturn(listOf(1L, 1L) + (2L..220L))
        val service = CaptionSearchCandidates(search)
        repeat(2) { assertEquals((1L..200L).toList(), service.find("공원").ids) }
        verify(search, times(1)).captionCandidates("공원")
        repeat(32) { service.find("query-$it") }
        service.find("공원")
        verify(search, times(2)).captionCandidates("공원")
    }
}
