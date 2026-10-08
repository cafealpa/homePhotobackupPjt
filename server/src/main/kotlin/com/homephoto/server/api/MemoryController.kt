package com.homephoto.server.api

import com.homephoto.server.service.MemoryService
import com.homephoto.server.service.SaveMemoryAlbum
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/memories")
class MemoryController(private val service: MemoryService) {
    @GetMapping("/home") fun home() = service.home()
    @GetMapping("/{id}") fun detail(@PathVariable id: Long) = service.detail(id)
    @GetMapping("/albums/{id}") fun album(@PathVariable id: Long) = service.savedAlbum(id)
    @PostMapping("/{id}/album") fun save(@PathVariable id: Long, @RequestBody request: SaveMemoryAlbum) = service.save(id, request)
}
