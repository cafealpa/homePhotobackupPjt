package com.homephoto.server.api

import com.homephoto.server.service.IncomingUploadService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/incoming-uploads")
class IncomingUploadController(private val incoming: IncomingUploadService) {
    @GetMapping fun summary() = incoming.summary()
    @PostMapping("/{hash}/retry") fun retry(@PathVariable hash: String): Map<String, Int> =
        mapOf("updated" to incoming.retry(hash))
}
