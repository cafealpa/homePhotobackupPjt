package com.homephoto.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.AppProperties
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GeminiCaptionClientTest {
    @TempDir lateinit var dir: Path
    private fun config() = AppProperties.CaptionProperties(provider = "GEMINI",
        geminiApiKeyFile = dir.resolve("key.txt").also { Files.writeString(it, "test-secret") }.toString())
    private fun image() = dir.resolve("image.jpg").also { Files.write(it, byteArrayOf(1, 2, 3)) }
    private fun server(code: Int, body: String, check: (com.sun.net.httpserver.HttpExchange) -> Unit = {},
                       action: (GeminiCaptionClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                check(exchange)
                exchange.responseHeaders.add("Retry-After", "120")
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        try { action(GeminiCaptionClient("http://127.0.0.1:${server.address.port}/v1beta")) }
        finally { server.stop(0) }
    }
    @Test fun `sends inline jpeg and schema with secret only in header and parses korean tags`() {
        val mapper = ObjectMapper()
        var path = ""
        var key = ""
        var sent = ""
        val response = mapper.writeValueAsString(mapOf("candidates" to listOf(mapOf("finishReason" to "STOP",
            "content" to mapOf("parts" to listOf(mapOf("text" to """{"caption":"공원에서 아이들이 놀고 있어요.","tags":["공원","놀이","공원"]}""")))))))
        server(200, response, { path = it.requestURI.toString(); key = it.requestHeaders.getFirst("x-goog-api-key"); sent = it.requestBody.reader().readText() }) { client ->
            val result = client.analyze(image(), config())
            assertEquals("공원에서 아이들이 놀고 있어요.", result.caption)
            assertEquals("공원,놀이", result.tags)
            assertEquals("gemini:gemini-2.5-flash", result.model)
            assertEquals("test-secret", key)
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", path)
            assertFalse(sent.contains("test-secret"))
            val request = mapper.readTree(sent)
            assertEquals("AQID", request.path("contents").path(0).path("parts").path(1).path("inlineData").path("data").asText())
            assertEquals("application/json", request.path("generationConfig").path("responseMimeType").asText())
        }
    }
    @Test fun `quota authorization and service errors pause without leaking response secrets`() {
        for (code in listOf(400, 401, 403, 404, 429, 503)) server(code, "test-secret") { client ->
            val error = assertFailsWith<CaptionUnavailableException> { client.analyze(image(), config()) }
            assertEquals(120, error.retrySeconds)
            assertContains(error.message!!, code.toString())
            assertFalse(error.message!!.contains("test-secret"))
        }
    }
    @Test fun `blocked and malformed responses never become successful captions`() {
        for (body in listOf("""{"candidates":[{"finishReason":"SAFETY"}]}""",
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"not-json"}]}}]}""")) {
            server(200, body) { client -> assertFailsWith<IllegalArgumentException> { client.analyze(image(), config()) } }
        }
    }
    @Test fun `missing key file is a configuration wait`() {
        assertFailsWith<CaptionUnavailableException> {
            GeminiCaptionClient().key(AppProperties.CaptionProperties(geminiApiKeyFile = dir.resolve("absent.txt").toString()))
        }
    }
}
