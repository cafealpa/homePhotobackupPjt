package com.homephoto.server.api

import com.homephoto.server.db.Assets
import com.homephoto.server.db.Devices
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class DeviceDto(val id: String, val name: String, val assetCount: Long)
data class RenameDeviceRequest(val name: String)

/** 백업 기기(=사진 소유자) 조회·이름 관리. */
@RestController
@RequestMapping("/api/v1/devices")
class DevicesController {

    @GetMapping
    fun list(): List<DeviceDto> = transaction {
        val cnt = Assets.id.count()
        val counts: Map<String?, Long> = Assets.select(Assets.deviceId, cnt)
            .where { Assets.deviceId.isNotNull() }
            .groupBy(Assets.deviceId)
            .associate { it[Assets.deviceId] to it[cnt] }

        Devices.selectAll().map {
            DeviceDto(
                id = it[Devices.id],
                name = it[Devices.name],
                assetCount = counts[it[Devices.id]] ?: 0,
            )
        }.sortedByDescending { it.assetCount }
    }

    /** 기기 이름(소유자) 변경 — 이 기기의 모든 사진에 소급 반영된다. */
    @PutMapping("/{id}")
    fun rename(@PathVariable id: String, @RequestBody request: RenameDeviceRequest): DeviceDto {
        val name = request.name.trim()
        require(name.isNotEmpty()) { "name must not be empty" }
        return transaction {
            val updated = Devices.update({ Devices.id eq id }) { it[Devices.name] = name }
            if (updated == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND, "device $id not found")
            val cnt = Assets.selectAll().where { Assets.deviceId eq id }.count()
            DeviceDto(id = id, name = name, assetCount = cnt)
        }
    }
}
