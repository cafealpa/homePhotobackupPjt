package com.homephoto.server.search

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class CaptionVectorIndexTest {
    @TempDir lateinit var dir: Path
    private fun vector(axis: Int) = FloatArray(384) { if(it == axis) 1f else 0f }
    @Test fun `index persists updates deletes and keeps model text fingerprints`() {
        CaptionVectorIndex(dir).use { index ->
            index.put(1, "old", vector(0)); index.put(2, "other", vector(1)); index.commit()
            assertEquals(1L, index.search(vector(0), 2).first().id)
            index.put(1, "new", vector(2)); index.delete(2); index.commit()
            assertEquals(1, index.count()); assertEquals("new", index.fingerprint(1))
        }
        CaptionVectorIndex(dir).use { index ->
            assertEquals(1, index.count())
            val hit = index.search(vector(2), 1).single()
            assertEquals(1L, hit.id); assertEquals("new", hit.fingerprint)
            assertTrue(hit.score > .99f)
        }
    }
    @Test fun `semantic cutoff removes weak unrelated candidates`() {
        val hits = listOf(CaptionVectorIndex.Hit(1, "a", .95f), CaptionVectorIndex.Hit(2, "b", .93f), CaptionVectorIndex.Hit(3, "c", .85f))
        assertEquals(listOf(1L, 2L), CaptionTextSearch.select(hits, .80, .06))
        assertTrue(CaptionTextSearch.select(emptyList(), .80, .06).isEmpty())
    }
    @Test fun `invalid dimensions and nonfinite vectors fail before indexing`() {
        CaptionVectorIndex(dir).use { index ->
            assertFailsWith<IllegalArgumentException> { index.put(1, "x", FloatArray(512)) }
            assertFailsWith<IllegalArgumentException> { index.put(1, "x", FloatArray(384) { Float.NaN }) }
            assertEquals(0, index.count())
        }
    }
}
