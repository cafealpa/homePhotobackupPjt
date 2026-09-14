package com.homephoto.server.search

import org.springframework.web.bind.annotation.*

/** 기존 관리자 API 인증 + 브라우저 form으로 보낼 수 없는 전용 요청 헤더. */
@RestController
@RequestMapping("/api/v1/admin/search-service")
class PhotoSearchProcessController(private val service: PhotoSearchProcess) {
    @GetMapping fun status() = service.status()
    @PostMapping("/start", headers = ["X-HomePhoto-Action=search-service"]) fun start() = service.start()
    @PostMapping("/stop", headers = ["X-HomePhoto-Action=search-service"]) fun stop() = service.stop()
}
