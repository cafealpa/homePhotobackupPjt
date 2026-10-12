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
        val result = generate(CaptionImage.jpeg(image), cfg,
            CaptionService.PROMPT + "\n문서 여부도 documentClassification: DOCUMENT/NOT_DOCUMENT/UNCERTAIN으로 반환하세요. 문서는 영수증·안내문·계약서·증명서·학습 자료·문서 화면 캡처이며 일반 장면의 간판과 구분합니다.",
            """{"type":"OBJECT","properties":{"caption":{"type":"STRING"},"tags":{"type":"ARRAY","items":{"type":"STRING"}},"documentClassification":{"type":"STRING","enum":["DOCUMENT","NOT_DOCUMENT","UNCERTAIN"]}},"required":["caption","tags","documentClassification"]}""")
        return captionResult(result, cfg.geminiModel)
    }

    fun captionResult(result: com.fasterxml.jackson.databind.JsonNode, model: String): CaptionService.CaptionResult {
        require(result.path("caption").isTextual && result.path("caption").asText().isNotBlank() &&
            result.path("tags").isArray && result.path("tags").all { it.isTextual }) { "Gemini 설명·태그 응답 형식이 올바르지 않습니다." }
        val classification = result.path("documentClassification").takeIf { !it.isMissingNode }?.asText()
        require(classification in com.homephoto.server.document.DocumentAnalysis.classifications) { "Gemini 문서 판별 결과가 올바르지 않습니다." }
        return CaptionService.CaptionResult(result.path("caption").asText().trim(),
            result.path("tags").map { it.asText().trim().replace(',', ' ') }.filter { it.isNotBlank() }.distinct().joinToString(","),
            "gemini:$model", classification)
    }

    /** 실시간 OCR 호출의 동시 실행과 할당량 재시도 간격을 제한한다. */
    fun generate(jpeg: ByteArray, cfg: AppProperties.CaptionProperties, prompt: String, schema: String,
                 resolution: String = "MEDIA_RESOLUTION_MEDIUM", maxOutputTokens: Int = 4096): com.fasterxml.jackson.databind.JsonNode = ProcessMonitor.locked(Gate.lock,"Gemini 호출 차례 대기") {
        ProcessMonitor.checkpoint()
        ProcessMonitor.stage("Gemini 응답 대기")
        val remaining = (Gate.retryAt - System.currentTimeMillis()) / 1000
        if (Gate.retryEndpoint == endpoint && remaining > 0) throw CaptionUnavailableException("Gemini 재시도 대기 중입니다.", retrySeconds = remaining)
        if (!cfg.geminiModel.matches(Regex("[a-zA-Z0-9._-]+")))
            throw CaptionUnavailableException("Gemini 모델명을 확인하세요.")
        val body = requestBody(jpeg, prompt, schema, resolution, maxOutputTokens)
        val response = call("models/${cfg.geminiModel}:generateContent", cfg, body)
        parseResponse(response)
    }

    fun captionRequest(jpeg: ByteArray) = requestBody(jpeg,
        CaptionService.PROMPT + "\n문서 여부도 documentClassification: DOCUMENT/NOT_DOCUMENT/UNCERTAIN으로 반환하세요. 문서는 영수증·안내문·계약서·증명서·학습 자료·문서 화면 캡처이며 일반 장면의 간판과 구분합니다.",
        """{"type":"OBJECT","properties":{"caption":{"type":"STRING"},"tags":{"type":"ARRAY","items":{"type":"STRING"}},"documentClassification":{"type":"STRING","enum":["DOCUMENT","NOT_DOCUMENT","UNCERTAIN"]}},"required":["caption","tags","documentClassification"]}""",
        "MEDIA_RESOLUTION_MEDIUM", 4096)

    private fun requestBody(jpeg: ByteArray, prompt: String, schema: String, resolution: String, maxOutputTokens: Int) =
        mapper.createObjectNode().apply {
            putArray("contents").addObject().put("role", "user").putArray("parts").apply {
                addObject().put("text", prompt)
                addObject().putObject("inlineData").apply {
                    put("mimeType", "image/jpeg")
                    put("data", Base64.getEncoder().encodeToString(jpeg))
                }
            }
            putObject("generationConfig").apply {
                put("mediaResolution", resolution)
                put("maxOutputTokens", maxOutputTokens)
                put("responseMimeType", "application/json")
                set<com.fasterxml.jackson.databind.JsonNode>("responseSchema", mapper.readTree(schema))
            }
        }
    fun submitBatch(id: String, requests: List<Pair<Long, com.fasterxml.jackson.databind.JsonNode>>, cfg: AppProperties.CaptionProperties): String {
        require(cfg.geminiModel.matches(Regex("[a-zA-Z0-9._-]+"))) { "Gemini 모델명을 확인하세요." }
        val body = mapper.createObjectNode()
        val entries = body.putObject("batch").apply { put("displayName", "homephoto-$id") }
            .putObject("inputConfig").putObject("requests").putArray("requests")
        requests.forEach { (jobId, request) -> entries.addObject().apply {
            set<com.fasterxml.jackson.databind.JsonNode>("request", request)
            putObject("metadata").put("key", jobId.toString())
        } }
        require(mapper.writeValueAsBytes(body).size < 20_000_000) { "Gemini 배치 요청 크기가 제한을 넘었습니다." }
        val result = ProcessMonitor.locked(Gate.lock, "Gemini 배치 제출 차례 대기") {
            call("models/${cfg.geminiModel}:batchGenerateContent", cfg, body)
        }
        val name = result.path("name").asText()
        require(name.matches(Regex("batches/[a-zA-Z0-9._-]+"))) { "Gemini 배치 접수 응답을 확인할 수 없습니다." }
        return name
    }

    fun pollBatch(name: String, cfg: AppProperties.CaptionProperties): com.fasterxml.jackson.databind.JsonNode {
        require(name.matches(Regex("batches/[a-zA-Z0-9._-]+")))
        return ProcessMonitor.locked(Gate.lock, "Gemini 배치 조회 차례 대기") { call(name, cfg) }
    }

    /** HTTP 거절과 접수 여부를 알 수 없는 전송 오류를 구분한다. 응답 원문은 노출하지 않는다. */
    class HttpFailure(val code: Int, val waitSeconds: Long) : RuntimeException("Gemini HTTP $code: 키·모델·할당량 또는 서비스 상태를 확인하세요.")

    private fun call(path: String, cfg: AppProperties.CaptionProperties, body: com.fasterxml.jackson.databind.JsonNode? = null): com.fasterxml.jackson.databind.JsonNode {
        val request = HttpRequest.newBuilder(URI.create("$endpoint/$path"))
            .header("x-goog-api-key", key(cfg)).header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds))
            .apply { if (body == null) GET() else POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))) }.build()
        val response = try { ProcessMonitor.interruptible { http.send(request, HttpResponse.BodyHandlers.ofString()) } }
        catch (_: IOException) { throw CaptionUnavailableException("Gemini 연결 실패 또는 응답 시간 초과. 잠시 후 재시도합니다.") }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CaptionUnavailableException("Gemini 호출이 중단되었습니다.")
        }
        val code = response.statusCode()
        if (code !in 200..299) {
            val wait = response.headers().firstValue("Retry-After").orElse("").toLongOrNull()?.coerceIn(60, 86400) ?: 60L
            if (!path.endsWith(":generateContent")) throw HttpFailure(code, wait)
            if (code in setOf(400, 401, 403, 404, 408, 429) || code >= 500) {
                Gate.retryEndpoint = endpoint
                Gate.retryAt = System.currentTimeMillis() + wait * 1000
                throw CaptionUnavailableException("Gemini HTTP $code: 키·모델·할당량 또는 서비스 상태를 확인하세요.", retrySeconds = wait)
            }
            throw IllegalStateException("Gemini HTTP $code")
        }
        return try { mapper.readTree(response.body()) ?: error("empty") }
        catch (_: Exception) { throw IllegalArgumentException("Gemini 응답 형식이 올바르지 않습니다.") }
    }

    fun parseResponse(response: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode {
        val candidate = response.path("candidates").path(0)
        require(candidate.path("finishReason").asText() == "STOP") { "Gemini 분석이 완료되지 않았습니다 (안전 필터 또는 응답 제한)." }
        val content = candidate.path("content").path("parts").filter { !it.path("thought").asBoolean(false) }
            .joinToString("") { it.path("text").asText("") }
        val result = try { mapper.readTree(content) } catch (_: Exception) { null }
        require(result != null && result.isObject) { "Gemini JSON 응답 형식이 올바르지 않습니다." }
        return result
    }
    private companion object Gate { val lock=java.util.concurrent.locks.ReentrantLock(); var retryAt = 0L; var retryEndpoint = "" }
}
