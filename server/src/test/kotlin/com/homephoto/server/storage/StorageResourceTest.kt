package com.homephoto.server.storage

import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.*

class StorageResourceTest {
    @Test fun `range prefix is skipped before opening storage and each stream is independent`() {
        val storage = mock(StorageAdapter::class.java)
        val bytes = byteArrayOf(7, 8, 9)
        `when`(storage.open("originals/test.mp4", 7, null)).thenReturn(ByteArrayInputStream(bytes))
        val resource = StorageResource(storage, "originals/test.mp4", 10)
        assertEquals(10L, resource.contentLength())
        assertEquals("test.mp4", resource.filename)
        val input = resource.inputStream
        assertEquals(7L, input.skip(7))
        verifyNoInteractions(storage)
        assertContentEquals(bytes, input.readAllBytes())
        input.close()
        assertFailsWith<IOException> { input.read() }
        verify(storage).open("originals/test.mp4", 7, null)
        resource.inputStream.use {
            assertEquals(10L, it.skip(100))
            assertEquals(0L, it.skip(-1))
        }
        verifyNoMoreInteractions(storage)
    }
}
