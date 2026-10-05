package com.homephoto.server.search

import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import kotlin.test.*

class CaptionSearchCandidatesTest {
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
