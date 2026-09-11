package com.homephoto.server.mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class PhotoMcpAccessTest {
    @Test fun `disabled feature blocks every MCP route while leaving existing API alone`() {
        val filter = PhotoMcpAccessFilter(PhotoMcpProperties())
        for (path in listOf("/mcp", "/mcp/", "/mcp-media/1", "/mcp-dev")) {
            val request = MockHttpServletRequest("GET", path).apply { servletPath = path }
            val response = MockHttpServletResponse()
            filter.doFilter(request, response, MockFilterChain())
            assertEquals(404, response.status, path)
        }
        val chain = MockFilterChain()
        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/assets").apply { servletPath = "/api/v1/assets" },
            MockHttpServletResponse(), chain)
        assertNotNull(chain.request)
    }

    @Test fun `remote clients and weak or externally hosted development configuration are rejected`() {
        val filter = PhotoMcpAccessFilter(PhotoMcpProperties(true, TEST_TOKEN))
        val request = MockHttpServletRequest("POST", "/mcp").apply {
            servletPath = "/mcp"; remoteAddr = "192.168.1.20"; addHeader("Authorization", "Bearer $TEST_TOKEN")
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        assertEquals(403, response.status)
        assertThrows(IllegalArgumentException::class.java) { PhotoMcpProperties(true, "short").validate() }
        assertThrows(IllegalArgumentException::class.java) { PhotoMcpProperties(true, TEST_TOKEN, "https://example.com").validate() }
        assertThrows(IllegalArgumentException::class.java) { PhotoMcpProperties(true, TEST_TOKEN, "http://localhost:8080/path").validate() }
        PhotoMcpProperties(true, TEST_TOKEN).validate()
    }
}
