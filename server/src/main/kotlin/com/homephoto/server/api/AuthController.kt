package com.homephoto.server.api

import com.homephoto.server.config.ApiKeyFilter
import com.homephoto.server.config.AppProperties
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

data class LoginRequest(val key: String)

/** 웹 뷰어용 쿠키 인증. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val props: AppProperties) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest, response: HttpServletResponse): ResponseEntity<Map<String, String>> {
        if (request.key.trim() != props.apiKey) {
            log.warn("웹 로그인 실패 (잘못된 키)")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "invalid key"))
        }
        log.info("웹 로그인 성공 — 인증 쿠키 발급 (90일)")
        val cookie = ResponseCookie.from(ApiKeyFilter.AUTH_COOKIE, props.apiKey)
            .httpOnly(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(Duration.ofDays(90))
            .build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
        return ResponseEntity.ok(mapOf("status" to "ok"))
    }

    /** 필터를 통과했다면 인증된 상태 — 웹이 로그인 화면 표시 여부를 판단하는 용도. */
    @GetMapping("/check")
    fun check(): Map<String, String> = mapOf("status" to "ok")
}
