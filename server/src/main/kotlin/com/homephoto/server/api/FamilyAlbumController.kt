package com.homephoto.server.api

import com.homephoto.server.service.FamilyAlbumService
import com.homephoto.server.service.SaveFamilyAlbum
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/family-albums")
class FamilyAlbumController(private val service: FamilyAlbumService) {
    @GetMapping("/home") fun home() = service.home()
    @GetMapping("/{kind}/{date}") fun detail(@PathVariable kind: String, @PathVariable date: String) = service.detail(kind, date)
    @GetMapping("/{kind}/{date}/candidates") fun candidates(
        @PathVariable kind: String, @PathVariable date: String, @RequestParam(required = false) cursor: String?,
    ) = service.candidates(kind, date, cursor)
    @PostMapping("/{kind}/{date}") fun save(@PathVariable kind: String, @PathVariable date: String, @RequestBody request: SaveFamilyAlbum) = service.save(kind, date, request)
}
