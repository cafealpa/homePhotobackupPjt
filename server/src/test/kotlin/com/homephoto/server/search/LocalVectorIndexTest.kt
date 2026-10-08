package com.homephoto.server.search

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LocalVectorIndexTest {
    @TempDir lateinit var root: Path
    @Test fun `cosine threshold and date exclusion are applied before nearest neighbor limit`() {
        LocalVectorIndex(root, 3).use { store ->
            store.put(1, 1, "a", 100, floatArrayOf(1f, 0f, 0f))
            store.put(2, 2, "b", 101, floatArrayOf(.6f, .8f, 0f))
            store.put(3, 3, "c", Long.MIN_VALUE, floatArrayOf(-1f, 0f, 0f))
            store.commit()
            val hits = store.search(floatArrayOf(1f, 0f, 0f))
            assertEquals(listOf(1L, 2L, 3L), hits.map { it.id })
            assertEquals(.6, hits[1].similarity, .00001)
            assertEquals(-1.0, hits[2].similarity, .00001)
            assertEquals(2L, store.search(floatArrayOf(1f, 0f, 0f), 1, 101, 101).single().id)
            assertEquals(2L, store.search(floatArrayOf(1f, 0f, 0f), 1, excludeAsset = 1).single().id)
        }
    }
    @Test fun `updates cleanup and reopen preserve the correct index`() {
        LocalVectorIndex(root, 3).use { store ->
            for (id in 1L..520L) store.put(id, id, "old", 1, floatArrayOf(1f, 0f, 0f))
            store.commit()
            store.put(1, 1, "new", 2, floatArrayOf(0f, 1f, 0f))
            store.retain(setOf(1, 520))
            store.commit()
            assertEquals(2L, store.count())
        }
        LocalVectorIndex(root, 3).use { store ->
            assertEquals("new", store.lookup(1)!!.fingerprint)
            assertEquals(2L, store.lookup(1)!!.day)
            assertEquals(1L, store.search(floatArrayOf(0f, 1f, 0f)).first().id)
            assertNull(store.lookup(2))
        }
    }
    @Test fun `face vectors are little endian normalized and reject invalid data`() {
        val bytes = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).putFloat(3f).putFloat(4f).array()
        assertEquals(.6f, faceVector(bytes)[0], .00001f)
        assertEquals(.8f, faceVector(bytes)[1], .00001f)
        for (invalid in listOf(ByteArray(4), ByteArray(2048), ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array()))
            assertThrows(IllegalArgumentException::class.java) { faceVector(invalid) }
    }
}
