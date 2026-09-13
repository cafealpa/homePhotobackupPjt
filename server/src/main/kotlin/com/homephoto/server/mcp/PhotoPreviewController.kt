package com.homephoto.server.mcp

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.Resource
import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@ConditionalOnProperty(prefix = "homephoto.mcp", name = ["enabled"], havingValue = "true")
class PhotoPreviewController(private val previews: PhotoPreviewService) {
    @GetMapping("/mcp-dev", produces = [MediaType.TEXT_HTML_VALUE])
    fun dev(): ResponseEntity<Resource> = ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-src 'self' about:; img-src 'self'; frame-ancestors 'none'")
        .body(ClassPathResource("mcp/dev.html"))

    @GetMapping("/mcp-media/{id}")
    fun read(@PathVariable id: Long, @RequestParam size: Int, @RequestParam expires: Long,
             @RequestParam signature: String, @RequestParam(defaultValue = "") grant: String): ResponseEntity<Resource> =
        ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer").header("X-Content-Type-Options", "nosniff")
            .body(previews.read(id, size, expires, signature, grant))
}
