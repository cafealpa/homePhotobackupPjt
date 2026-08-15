package com.homephoto.server.api

import com.homephoto.server.service.SettingsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 웹 설정 페이지용 조회/저장 API. 인증은 ApiKeyFilter가 공통 처리.
 * 검증 실패(IllegalArgumentException)는 ApiExceptionHandler가 400 {"error": …}로 변환한다.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
class SettingsController(private val settingsService: SettingsService) {

    @GetMapping
    fun get(): SettingsService.Settings = settingsService.current()

    @PutMapping
    fun put(@RequestBody request: SettingsService.Settings): Map<String, Any> {
        val result = settingsService.save(request)
        return mapOf(
            "saved" to true,
            "restartRequired" to result.restartRequired,
            "configFile" to result.configFile,
        )
    }
}
