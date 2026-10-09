package com.homephoto.server.api

import com.homephoto.server.service.ProcessManagementService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/admin/processes")
class ProcessMonitorController(private val service: ProcessManagementService) {
    @GetMapping fun status() = service.summary()
    @PostMapping("/all/{action}", headers=["X-HomePhoto-Action=process-control"])
    fun all(@PathVariable action: String) = service.all(action)
    @PostMapping("/{id}/{action}", headers=["X-HomePhoto-Action=process-control"])
    fun action(@PathVariable id: String,@PathVariable action: String) = service.action(id,action)
}
