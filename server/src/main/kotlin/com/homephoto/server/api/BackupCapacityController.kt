package com.homephoto.server.api

import com.homephoto.server.service.UploadCapacity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class BackupCapacityController(private val capacity: UploadCapacity) {
    /** 조회는 예약이 아니다. 실제 업로드는 수신 필터에서 다시 원자적으로 검사한다. */
    @GetMapping("/api/v1/backup-capacity")
    fun status(@RequestParam(defaultValue = "0") bytes: Long) = capacity.status(bytes)
}
