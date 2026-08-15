package com.homephoto.server.api

import com.homephoto.server.service.UnsupportedMediaException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(e: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<Map<String, String?>> {
        log.warn("잘못된 요청 [{} {}]: {}", request.method, request.requestURI, e.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message))
    }

    /** 필수 쿼리 파라미터 누락·타입 불일치 — 클라이언트 잘못이므로 400. */
    @ExceptionHandler(
        org.springframework.web.bind.MissingServletRequestParameterException::class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class,
    )
    fun badParam(e: Exception, request: HttpServletRequest): ResponseEntity<Map<String, String?>> {
        log.warn("잘못된 파라미터 [{} {}]: {}", request.method, request.requestURI, e.message)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message))
    }

    @ExceptionHandler(UnsupportedMediaException::class)
    fun unsupportedMedia(e: UnsupportedMediaException, request: HttpServletRequest): ResponseEntity<Map<String, String?>> {
        log.warn("미지원 형식 [{} {}]: {}", request.method, request.requestURI, e.message)
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(mapOf("error" to e.message))
    }

    /** 컨트롤러가 의도적으로 던진 상태 코드 — 4xx는 경고, 5xx는 에러로 남긴다. */
    @ExceptionHandler(ResponseStatusException::class)
    fun statusException(e: ResponseStatusException, request: HttpServletRequest): ResponseEntity<Map<String, String?>> {
        if (e.statusCode.is5xxServerError) {
            log.error("서버 오류 [{} {}]: {}", request.method, request.requestURI, e.reason, e)
        } else if (e.statusCode.value() != 404) { // 404(썸네일 미생성 등)는 정상 흐름이라 조용히
            log.warn("[{} {}] {} {}", request.method, request.requestURI, e.statusCode.value(), e.reason)
        }
        return ResponseEntity.status(e.statusCode).body(mapOf("error" to e.reason))
    }

    /** 예상 못 한 모든 예외 — 반드시 스택트레이스와 함께 기록한다. */
    @ExceptionHandler(Exception::class)
    fun unhandled(e: Exception, request: HttpServletRequest): ResponseEntity<Map<String, String?>> {
        log.error("처리되지 않은 오류 [{} {}]", request.method, request.requestURI, e)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to (e.message ?: e.javaClass.simpleName)))
    }
}
