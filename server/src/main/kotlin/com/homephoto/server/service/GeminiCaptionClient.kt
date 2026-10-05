package com.homephoto.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.AppProperties
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/** API 키는 헤더로만 전달한다. 응답 원문/키를 예외나 로그에 포함하지 않는다. */
internal class GeminiCaptionClient(
    private val endpoint: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    fun key(cfg: AppProperties.CaptionProperties): String {
        val value = try {
            if (cfg.geminiApiKeyFile.isBlank()) System.getenv("GEMINI_API_KEY").orEmpty()
            else Files.readString(Path.of(cfg.geminiApiKeyFile))
        } catch (_: Exception) {
            throw CaptionUnavailableException("Gemini API 키 파일을 읽을 수 없습니다. 서버 파일 경로와 권한을 확인하세요.")
        }.trim()
        if (value.isBlank() || value.any { it.isWhitespace() })
            throw CaptionUnavailableException("Gemini API 키 파일(키 한 줄) 또는 GEMINI_API_KEY 환경변수를 설정하세요.")
        return value
    }

    fun analyze(image: Path, cfg: AppProperties.CaptionProperties): CaptionService.CaptionResult {
        val apiKey = key(cfg)
        if (!cfg.geminiModel.matches(Regex("[a-zA-Z0-9._-]+")))
            throw CaptionUnavailableException("Gemini 모델명을 확인하세요.")
        val body = mapper.createObjectNode().apply {
            putArray("contents").addObject().put("role", "user").putArray("parts").apply {
                addObject().put("text", CaptionService.PROMPT)
                addObject().putObject("inlineData").apply {
                    put("mimeType", "image/jpeg")
                    put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(image)))
                }
            }
            putObject("generationConfig").apply {
                put("responseMimeType", "application/json")
                set<com.fasterxml.jackson.databind.JsonNode>("responseSchema", mapper.readTree("""
                    {"type":"OBJECT","properties":{"caption":{"type":"STRING"},"tags":{"type":"ARRAY","items":{"type":"STRING"}}},"required":["caption","tags"]}
                """.trimIndent()))
            }
        }
        val request = HttpRequest.newBuilder(URI.create("$endpoint/models/${cfg.geminiModel}:generateContent"))
            .header("x-goog-api-key", apiKey).header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofString()) }
        catch (_: IOException) { throw CaptionUnavailableException("Gemini 연결 실패 또는 응답 시간 초과. 잠시 후 재시도합니다.") }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CaptionUnavailableException("Gemini 호출이 중단되었습니다.")
        }
        val code = response.statusCode()
        if (code !in 200..299) {
            val wait = response.headers().firstValue("Retry-After").orElse("").toLongOrNull()?.coerceIn(60, 86400) ?: 60L
            if (code in setOf(400, 401, 403, 404, 408, 429) || code >= 500)
                throw CaptionUnavailableException("Gemini HTTP $code: 키·모델·할당량 또는 서비스 상태를 확인하세요.", retrySeconds = wait)
            throw IllegalStateException("Gemini HTTP $code")
        }
        val candidate = mapper.readTree(response.body()).path("candidates").path(0)
        require(candidate.path("finishReason").asText() == "STOP") { "Gemini 분석이 완료되지 않았습니다 (안전 필터 또는 응답 제한)." }
        val content = candidate.path("content").path("parts").filter { !it.path("thought").asBoolean(false) }
            .joinToString("") { it.path("text").asText("") }
        val result = try { mapper.readTree(content) } catch (_: Exception) { null }
        require(result != null && result.path("caption").isTextual && result.path("caption").asText().isNotBlank() &&
            result.path("tags").isArray && result.path("tags").all { it.isTextual }) { "Gemini 설명·태그 응답 형식이 올바르지 않습니다." }
        return CaptionService.CaptionResult(result.path("caption").asText().trim(),
            result.path("tags").map { it.asText().trim().replace(',', ' ') }.filter { it.isNotBlank() }.distinct().joinToString(","),
            "gemini:${cfg.geminiModel}")
    }
}
