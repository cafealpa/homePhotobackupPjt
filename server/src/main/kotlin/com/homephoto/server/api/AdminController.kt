package com.homephoto.server.api

import com.homephoto.server.db.Assets
import com.homephoto.server.db.Devices
import com.homephoto.server.service.ImportService
import com.homephoto.server.service.KidsnoteImportService
import com.homephoto.server.service.SettingsService
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val importService: ImportService,
    private val kidsnoteImportService: KidsnoteImportService,
    private val settingsService: SettingsService,
    private val applicationContext: org.springframework.context.ApplicationContext,
) {

    data class ServerInfoDto(val port: Int, val addresses: List<String>)

    /** 설정 페이지 표시용: 서버가 현재 열려 있는 LAN IP들과 포트. */
    @GetMapping("/server-info")
    fun serverInfo(): ServerInfoDto {
        val port = (applicationContext as? org.springframework.boot.web.context.WebServerApplicationContext)
            ?.webServer?.port ?: 8080
        val addresses = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
            .distinct()
            .sorted()
        return ServerInfoDto(port = port, addresses = addresses)
    }

    /** 웹 설정 페이지의 재시작 버튼. 응답을 보낸 뒤 프로세스를 교체한다. */
    @PostMapping("/restart")
    fun restart(): Map<String, Any> {
        settingsService.scheduleRestart()
        return mapOf("restarting" to true)
    }

    @PostMapping("/import")
    fun startImport(@RequestBody request: ImportRequest): ResponseEntity<ImportStatusDto> {
        val started = importService.start(request.sourcePath)
        val status = if (started) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(status).body(importService.status())
    }

    @GetMapping("/import/status")
    fun importStatus(): ImportStatusDto = importService.status()

    @PostMapping("/kidsnote/import")
    fun startKidsnoteImport(@RequestBody request: ImportRequest): ResponseEntity<KidsnoteImportStatusDto> {
        val started = kidsnoteImportService.start(request.sourcePath)
        val status = if (started) HttpStatus.ACCEPTED else HttpStatus.CONFLICT
        return ResponseEntity.status(status).body(kidsnoteImportService.status())
    }

    @GetMapping("/kidsnote/import/status")
    fun kidsnoteImportStatus(): KidsnoteImportStatusDto = kidsnoteImportService.status()

    data class BackfillDeviceRequest(val deviceId: String)

    /** 기기 정보 없이 백업된 기존 사진들을 특정 기기 소유로 일괄 지정. */
    @PostMapping("/assets/backfill-device")
    fun backfillDevice(@RequestBody request: BackfillDeviceRequest): Map<String, Any> {
        val updated = transaction {
            val exists = Devices.selectAll().where { Devices.id eq request.deviceId }.count() > 0
            require(exists) { "unknown device: ${request.deviceId} (해당 기기로 한 번이라도 업로드해야 등록됩니다)" }
            Assets.update({ Assets.deviceId.isNull() }) { it[Assets.deviceId] = request.deviceId }
        }
        return mapOf("updated" to updated, "deviceId" to request.deviceId)
    }
}
