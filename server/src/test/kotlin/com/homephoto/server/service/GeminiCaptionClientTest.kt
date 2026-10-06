package com.homephoto.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.AppProperties
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class GeminiCaptionClientTest {
    @TempDir lateinit var dir: Path
    private fun config() = AppProperties.CaptionProperties(provider = "GEMINI",
        geminiApiKeyFile = dir.resolve("key.txt").also { Files.writeString(it, "test-secret") }.toString())
    private fun image(width: Int = 1600, height: Int = 1200) = dir.resolve("image.jpg").also {
        val source = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        try { ImageIO.write(source, "jpg", it.toFile()) } finally { source.flush() }
    }
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
            "content" to mapOf("parts" to listOf(mapOf("text" to """{"caption":"공원에서 아이들이 놀고 있어요.","tags":["공원","놀이","공원"],"documentClassification":"NOT_DOCUMENT"}""")))))))
        server(200, response, { path = it.requestURI.toString(); key = it.requestHeaders.getFirst("x-goog-api-key"); sent = it.requestBody.reader().readText() }) { client ->
            val result = client.analyze(image(), config())
            assertEquals("공원에서 아이들이 놀고 있어요.", result.caption)
            assertEquals("공원,놀이", result.tags)
            assertEquals("gemini:gemini-2.5-flash", result.model)
            assertEquals("NOT_DOCUMENT", result.documentClassification)
            assertEquals("test-secret", key)
            assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", path)
            assertFalse(sent.contains("test-secret"))
            val request = mapper.readTree(sent)
            val inline = request.path("contents").path(0).path("parts").path(1).path("inlineData")
            assertEquals("image/jpeg", inline.path("mimeType").asText())
            val transmitted = ImageIO.read(Base64.getDecoder().decode(inline.path("data").asText()).inputStream())
            assertEquals(768, transmitted.width)
            assertEquals(576, transmitted.height)
            transmitted.flush()
            assertEquals("application/json", request.path("generationConfig").path("responseMimeType").asText())
            assertEquals("MEDIA_RESOLUTION_MEDIUM", request.path("generationConfig").path("mediaResolution").asText())
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

    @Test fun `document OCR sends high resolution schema and saves structured Korean response`() {
        val mapper=ObjectMapper()
        val json="""{"classification":"DOCUMENT","type":"NOTICE","title":"가정통신문","summary":"준비물 안내","date":null,"issuer":"학교","ocrText":"준비물: 물통","keywords":["준비물"],"needsReview":false,"reviewReason":""}"""
        val response=mapper.writeValueAsString(mapOf("candidates" to listOf(mapOf("finishReason" to "STOP",
            "content" to mapOf("parts" to listOf(mapOf("text" to json)))))))
        var sent=""
        server(200,response,{ sent=it.requestBody.reader().readText() }) { client ->
            val jpeg=com.homephoto.server.document.DocumentImage.jpeg(image(4000,3000))
            val result=com.homephoto.server.document.DocumentAnalysis.parse(client.generate(jpeg,config(),
                com.homephoto.server.document.DocumentAnalysis.PROMPT,com.homephoto.server.document.DocumentAnalysis.schema,
                "MEDIA_RESOLUTION_HIGH",16384))
            assertEquals("준비물: 물통",result.ocrText)
            assertNull(result.date)
            assertContains(result.searchText(),"가정통신문")
            val body=mapper.readTree(sent)
            assertEquals("MEDIA_RESOLUTION_HIGH",body.path("generationConfig").path("mediaResolution").asText())
            assertEquals(16384,body.path("generationConfig").path("maxOutputTokens").asInt())
            val bytes=Base64.getDecoder().decode(body.path("contents").path(0).path("parts").path(1).path("inlineData").path("data").asText())
            val transmitted=ImageIO.read(bytes.inputStream())
            assertEquals(2048,transmitted.width);assertEquals(1536,transmitted.height);transmitted.flush()
        }
    }

    @Test fun `analysis jpeg preserves aspect ratio never enlarges and leaves viewer files unchanged`() {
        for ((width, height) in listOf(1600 to 1200, 1200 to 1600, 1600 to 1600, 320 to 240)) {
            val source = image(width, height)
            val before = Files.readAllBytes(source)
            val encoded = CaptionImage.jpeg(source)
            assertEquals(0xff, encoded[0].toInt() and 0xff)
            assertEquals(0xd8, encoded[1].toInt() and 0xff)
            val resized = ImageIO.read(encoded.inputStream())
            val scale = minOf(1.0, 768.0 / maxOf(width, height))
            assertEquals((width * scale).toInt(), resized.width)
            assertEquals((height * scale).toInt(), resized.height)
            resized.flush()
            assertContentEquals(before, Files.readAllBytes(source))
            assertEquals(1L, Files.list(dir).use { it.count() })
        }
    }
}
