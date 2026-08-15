package com.homephoto.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.AppProperties
import org.springframework.stereotype.Service
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

/** VLM 서버(GB10)에 닿지 못한 경우 — 작업 실패가 아니라 "나중에 다시"로 처리해야 한다. */
class CaptionUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 장면 분석: 이미지를 GB10의 Gemma VLM(Ollama, OpenAI 호환 API)에 보내
 * 한국어 캡션과 태그를 받아온다. 원본 대신 1600px 썸네일을 보낸다 —
 * 전송량을 줄이고 HEIC 등 비표준 포맷도 JPEG로 통일되기 때문.
 */
@Service
class CaptionService(private val props: AppProperties) {

    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    data class CaptionResult(val caption: String, val tags: String?, val model: String)

    fun analyze(image: Path): CaptionResult {
        val cfg = props.caption
        val imageB64 = Base64.getEncoder().encodeToString(Files.readAllBytes(image))

        val body = mapper.createObjectNode().apply {
            put("model", cfg.model)
            put("temperature", 0.2)
            putArray("messages").addObject().apply {
                put("role", "user")
                putArray("content").apply {
                    addObject().apply { put("type", "text"); put("text", PROMPT) }
                    addObject().apply {
                        put("type", "image_url")
                        putObject("image_url").put("url", "data:image/jpeg;base64,$imageB64")
                    }
                }
            }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${cfg.baseUrl.trimEnd('/')}/v1/chat/completions"))
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            // 연결 거부·타임아웃(모델 로딩 중 포함) — GB10이 꺼져 있어도 백업·뷰어는 정상이어야 한다
            throw CaptionUnavailableException("VLM 연결 실패 (${cfg.baseUrl}): ${e.message}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CaptionUnavailableException("VLM 호출 중단", e)
        }
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("VLM HTTP ${response.statusCode()}: ${response.body().take(300)}")
        }

        val content = mapper.readTree(response.body())
            .path("choices").path(0).path("message").path("content").asText("")
        require(content.isNotBlank()) { "VLM이 빈 응답을 반환" }
        return parse(content, cfg.model)
    }

    /** 모델이 JSON 형식을 지키지 않아도 최대한 살린다: JSON 파싱 실패 시 전체 텍스트를 캡션으로. */
    private fun parse(content: String, model: String): CaptionResult {
        val json = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val node = mapper.readTree(json)
            val caption = node.path("caption").asText("").trim()
            require(caption.isNotEmpty())
            val tags = node.path("tags").let { t ->
                when {
                    t.isArray -> t.mapNotNull { it.asText().trim().ifEmpty { null } }.joinToString(",")
                    t.isTextual -> t.asText().trim()
                    else -> null
                }
            }?.ifBlank { null }
            CaptionResult(caption, tags, model)
        } catch (e: Exception) {
            CaptionResult(content.trim().take(500), null, model)
        }
    }

    companion object {
        private val PROMPT = """
            이 사진을 분석해서 아래 JSON 형식으로만 답하세요. 다른 텍스트는 붙이지 마세요.
            {"caption": "사진을 설명하는 자연스러운 한국어 한두 문장", "tags": ["키워드1", "키워드2", ...]}
            tags는 검색에 쓸 한국어 명사 3~8개(장소, 사물, 인물 구성, 활동, 분위기, 음식 이름 등).
        """.trimIndent()
    }
}
