package com.homephoto.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.homephoto.server.config.*
import com.homephoto.server.service.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class UploadCapacityTest {
    private val gb = UploadCapacity.GiB
    private val props = AppProperties(Path.of("unused"), "test")
    private val disk = mock(UploadDiskProbe::class.java)
    private val gate = UploadCapacity(props, disk)
    private fun usage(free: Long = 100, incoming: Long = 0, total: Long = 200) {
        `when`(disk.read()).thenReturn(UploadDiskProbe.Usage(total * gb, free * gb, incoming * gb))
    }

    @Test fun `parallel uploads cannot spend the same free space and release is idempotent`() {
        usage(free = 30)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { executor.submit(Callable { start.await(); runCatching { gate.reserve(3 * gb) }.getOrNull() }) }
            start.countDown()
            val leases = futures.mapNotNull { it.get() }
            assertEquals(1, leases.size)
            assertEquals(3 * gb, gate.status().reservedBytes)
            leases.single().close(); leases.single().close()
            assertEquals(0L, gate.status().reservedBytes)
            assertFalse(gate.status().accepting) // 재개 여유 + 거절된 파일 공간까지 필요
            usage(free = 35)
            assertTrue(gate.status().accepting)
        } finally { executor.shutdownNow() }
    }

    @Test fun `queue cap blocks uploads and resumes only below low water mark`() {
        usage(incoming = 98)
        assertFailsWith<UploadSpaceUnavailable> { gate.reserve(3 * gb) }
        usage(incoming = 95)
        assertFalse(gate.status().accepting)
        usage(incoming = 90)
        assertTrue(gate.status().accepting)
    }

    @Test fun `percentage floor protects large drives and measurement failure closes admission`() {
        usage(free = 90, total = 1000)
        assertFalse(gate.status().accepting)
        assertEquals(100 * gb, gate.status().minimumFreeBytes)
        `when`(disk.read()).thenThrow(IllegalStateException("disk unavailable"))
        assertFalse(gate.status().accepting)
        assertFailsWith<UploadSpaceUnavailable> { gate.reserve(1) }
    }

    @Test fun `filter refuses before reading multipart and leaves existing files untouched`() {
        usage(free = 19, incoming = 80)
        val filter = UploadCapacityFilter(gate, jacksonObjectMapper())
        val request = MockHttpServletRequest("POST", "/api/v1/assets").apply { setContent(byteArrayOf(1, 2)) }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response) { _, _ -> fail("multipart must not be parsed") }
        assertEquals(507, response.status)
        assertEquals("300", response.getHeader("Retry-After"))
        assertTrue(response.contentAsString.contains("BACKUP_STORAGE_FULL"))
        assertEquals(80 * gb, gate.status().incomingBytes)
        assertEquals(0L, gate.status().reservedBytes)
    }

    @Test fun `unknown length is rejected and write failure releases reservation with 507`() {
        usage()
        val filter = UploadCapacityFilter(gate, jacksonObjectMapper())
        val unknown = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("POST", "/api/v1/assets"), unknown) { _, _ -> fail("unknown length") }
        assertEquals(411, unknown.status)
        val response = MockHttpServletResponse()
        val request = MockHttpServletRequest("POST", "/api/v1/assets").apply { setContent(byteArrayOf(1)) }
        filter.doFilter(request, response) { _, _ -> throw java.io.IOException("No space left on device") }
        assertEquals(507, response.status)
        assertEquals(0L, gate.status().reservedBytes)
    }

    @Test fun `external settings bind GiB fields consistently`() {
        val source = org.springframework.boot.context.properties.source.MapConfigurationPropertySource(mapOf(
            "homephoto.upload-buffer.min-free-gi-b" to "22", "homephoto.upload-buffer.max-incoming-gi-b" to "120",
            "homephoto.upload-buffer.resume-margin-gi-b" to "7", "homephoto.upload-buffer.min-free-percent" to "15"))
        val policy = org.springframework.boot.context.properties.bind.Binder(source)
            .bind("homephoto.upload-buffer", AppProperties.UploadBufferProperties::class.java).get()
        assertEquals(22L, policy.minFreeGiB); assertEquals(120L, policy.maxIncomingGiB)
        assertEquals(7L, policy.resumeMarginGiB); assertEquals(15, policy.minFreePercent)
    }
}
