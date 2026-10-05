package com.homephoto.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.service.UploadCapacity
import com.homephoto.server.service.UploadSpaceUnavailable
import jakarta.servlet.FilterChain
import jakarta.servlet.MultipartConfigElement
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.file.Files

@Configuration
class UploadMultipartConfig {
    @Bean fun multipartConfigElement(props: AppProperties, multipart: MultipartProperties): MultipartConfigElement {
        val directory = props.uploadTmpDir.resolve("multipart").toAbsolutePath()
        Files.createDirectories(directory)
        multipart.location = directory.toString()
        return multipart.createMultipartConfig()
    }
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
class UploadCapacityFilter(private val capacity: UploadCapacity, private val mapper: ObjectMapper) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest) = request.method != "POST" || request.requestURI != "/api/v1/assets"
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val size = request.contentLengthLong
        if (size <= 0 || size > UploadCapacity.MAX_REQUEST_BYTES) {
            response.status = if (size <= 0) 411 else 413
            response.contentType = "application/json;charset=UTF-8"
            mapper.writeValue(response.writer, mapOf("error" to if (size <= 0) "안전한 업로드를 위해 Content-Length가 필요합니다." else "업로드 요청이 너무 큽니다."))
            return
        }
        try { capacity.reserve(size).use { chain.doFilter(request, response) } }
        catch (e: Exception) {
            if (e !is UploadSpaceUnavailable && !UploadCapacity.isDiskFull(e)) throw e
            if (response.isCommitted) throw e
            if (e !is UploadSpaceUnavailable) capacity.writeFailed()
            response.resetBuffer()
            response.status = 507
            response.setHeader("Retry-After", "300")
            response.contentType = "application/json;charset=UTF-8"
            mapper.writeValue(response.writer, mapOf("code" to "BACKUP_STORAGE_FULL", "error" to
                (if (e is UploadSpaceUnavailable) e.reason else "서버 저장 공간이 부족합니다. 공간 확보 후 다시 시도해 주세요.")))
        }
    }
}
