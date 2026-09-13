package com.homephoto.server.mcp

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.servlet.function.HandlerFilterFunction
import org.springframework.web.servlet.function.HandlerFunction
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * SDK 0.18.4는 미지원 메서드 오류를 HTTP 500으로 바꾼다.
 * 현대 클라이언트의 discovery probe에는 명시적 legacy 응답을 보내 initialize로 전환할 수 있게 한다.
 * Servlet 인증/프록시 필터 이후에만 실행하며, 새 프로토콜 지원을 광고하지 않는다.
 */
class PhotoMcpDiscoveryCompatibility(private val mapper: ObjectMapper) : HandlerFilterFunction<ServerResponse, ServerResponse> {
    override fun filter(request: ServerRequest, next: HandlerFunction<ServerResponse>): ServerResponse {
        if (request.method() != HttpMethod.POST) return next.handle(request)
        val bytes = request.servletRequest().inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) return ServerResponse.status(413).build()
        val message = try { mapper.readTree(bytes) } catch (_: JsonProcessingException) {
            return error(400, null, -32700, "Parse error")
        }
        if (message?.isObject != true || message.path("jsonrpc").asText() != "2.0" || !message.path("method").isTextual) {
            return error(400, null, -32600, "Invalid Request")
        }
        if (message.path("method").asText() == "server/discover") {
            val id = message.get("id") ?: return ServerResponse.accepted().build()
            if (!id.isTextual && !id.isNumber) return error(400, null, -32600, "Invalid Request")
            return error(404, id, -32601, "Method not found")
        }
        // 필터가 소비한 body를 다시 제공한다. SDK의 기존 도구/초기화 처리와 보안 검증은 그대로 사용한다.
        return next.handle(ServerRequest.from(request).body(bytes).build())
    }

    private fun error(status: Int, id: Any?, code: Int, message: String) = ServerResponse.status(status)
        .contentType(MediaType.APPLICATION_JSON).body(mapOf("jsonrpc" to "2.0", "id" to id,
            "error" to mapOf("code" to code, "message" to message)))

    companion object { private const val MAX_BODY_BYTES = 64 * 1024 }
}
