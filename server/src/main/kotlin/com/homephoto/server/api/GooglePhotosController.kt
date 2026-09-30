package com.homephoto.server.api

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.publication.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** 기존 API 키/세션 인증을 사용한다. GET 응답에는 인증/upload token이 포함되지 않는다. */
@RestController
@RequestMapping("/api/v1/admin/google-photos")
class GooglePhotosController(private val props: AppProperties, private val queue: GooglePhotosPublicationQueue,
                             private val export: GooglePhotosExport) {
    data class Selection(val assetIds: List<Long>)
    data class Recent(val limit: Int = 5)
    data class Resolution(val mediaItemId: String? = null, val productUrl: String? = null, val confirmedNotCreated: Boolean = false)

    @GetMapping fun status(): Map<String, Any> {
        val settings = props.googlePhotos
        return mapOf("enabled" to settings.enabled, "autoPublishNew" to settings.autoPublishNew, "includeVideos" to settings.includeVideos,
            "credentialsConfigured" to (settings.clientFile.isNotBlank() && settings.tokenFile.isNotBlank() &&
                Files.isRegularFile(Path.of(settings.clientFile)) && Files.isRegularFile(Path.of(settings.tokenFile))),
            "counts" to queue.counts(), "items" to queue.items())
    }

    @PostMapping("/enqueue", headers = ["X-HomePhoto-Action=google-photos"])
    fun enqueue(@RequestBody selection: Selection) = queue.enqueue(selection.assetIds)
    @PostMapping("/recent", headers = ["X-HomePhoto-Action=google-photos"])
    fun recent(@RequestBody request: Recent) = queue.enqueueRecent(request.limit)
    @PostMapping("/{id}/retry", headers = ["X-HomePhoto-Action=google-photos"])
    fun retry(@PathVariable id: Long): Map<String, Boolean> = changed(queue.retry(id))
    @PostMapping("/{id}/cancel", headers = ["X-HomePhoto-Action=google-photos"])
    fun cancel(@PathVariable id: Long): Map<String, Boolean> = changed(queue.cancel(id))
    @PostMapping("/{id}/resolve", headers = ["X-HomePhoto-Action=google-photos"])
    fun resolve(@PathVariable id: Long, @RequestBody request: Resolution): Map<String, Boolean> {
        if (!request.productUrl.isNullOrBlank()) {
            val url = runCatching { URI(request.productUrl) }.getOrElse { throw IllegalArgumentException("Google Photos의 HTTPS 항목 주소를 입력하세요.") }
            require(url.scheme == "https" && url.host == "photos.google.com") { "Google Photos의 HTTPS 항목 주소를 입력하세요." }
        }
        val path = queue.renditionPath(id)
        val resolved = queue.resolve(id, request.mediaItemId, request.productUrl, request.confirmedNotCreated)
        if (resolved && !request.mediaItemId.isNullOrBlank()) path?.let { runCatching { export.delete(it) } }
        return changed(resolved)
    }

    /** 로컬 메타데이터/이미지 점검용이며 Google에 접근하거나 게시 작업을 등록하지 않는다. */
    @GetMapping("/{id}/preview")
    fun preview(@PathVariable id: Long): ResponseEntity<ByteArray> {
        val asset = transaction {
            Assets.selectAll().where { (Assets.id eq id) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull() }.firstOrNull()?.let {
                PublicationAsset(it[Assets.id], it[Assets.hash], it[Assets.originalPath], it[Assets.originalFilename], it[Assets.takenAt],
                    it[Assets.takenAtSource], it[Assets.gpsLat], it[Assets.gpsLon])
            }
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "자산을 찾을 수 없습니다.")
        val prepared = export.prepare(asset)
        return try { ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(Files.readAllBytes(prepared.path)) }
        finally { runCatching { export.delete(prepared.path) } }
    }
    private fun changed(result: Boolean): Map<String, Boolean> {
        if (!result) throw ResponseStatusException(HttpStatus.CONFLICT, "현재 상태에서는 이 작업을 진행할 수 없습니다.")
        return mapOf("changed" to true)
    }
}
