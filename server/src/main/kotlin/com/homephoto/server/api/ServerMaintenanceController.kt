package com.homephoto.server.api

import com.homephoto.server.service.ReleaseUpdateService
import com.homephoto.server.service.ServerMaintenanceService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/maintenance")
class ServerMaintenanceController(private val maintenance: ServerMaintenanceService, private val updates: ReleaseUpdateService) {
    data class Prepare(val tag: String)
    @GetMapping("/status") fun status() = maintenance.status()
    @GetMapping("/releases") fun releases(@RequestParam(defaultValue = "true") includePrerelease: Boolean) = updates.check(includePrerelease)
    @PostMapping("/prepare") fun prepare(@RequestHeader("X-HomePhoto-Action") action: String, @RequestBody body: Prepare): ReleaseUpdateService.State {
        require(action == "maintenance"); return updates.prepare(body.tag)
    }
    @PostMapping("/{operation:shutdown|restart|update}")
    fun action(@PathVariable operation: String, @RequestHeader("X-HomePhoto-Action") action: String): ServerMaintenanceService.Status {
        require(action == "maintenance"); return maintenance.request(operation)
    }
}
