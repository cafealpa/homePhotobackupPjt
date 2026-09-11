package com.homephoto.server.mcp

import com.homephoto.server.db.Assets
import com.homephoto.server.service.ThumbnailService
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.core.io.FileSystemResource
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PhotoPreviewService(
    private val props: PhotoMcpProperties,
    private val thumbnails: ThumbnailService,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun urls(id: Long): Map<String, Any> {
        val expires = clock.instant().epochSecond + props.previewTtlSeconds
        return mapOf("thumbnailUrl" to url(id, 400, expires), "previewUrl" to url(id, 1600, expires), "expiresAt" to expires)
    }

    private fun url(id: Long, size: Int, expires: Long) =
        "${props.baseUrl}/mcp-media/$id?size=$size&expires=$expires&signature=${signature(id, size, expires)}"

    private fun signature(id: Long, size: Int, expires: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(props.token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal("homephoto-preview-v1:$id:$size:$expires".toByteArray(Charsets.UTF_8)),
        )
    }

    fun read(id: Long, size: Int, expires: Long, signature: String): FileSystemResource {
        val now = clock.instant().epochSecond
        if (id <= 0 || size !in setOf(400, 1600) || expires <= now || expires > now + props.previewTtlSeconds ||
            !MessageDigest.isEqual(signature(id, size, expires).toByteArray(), signature.toByteArray())) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Preview URL is invalid or expired")
        }
        // URL 발급 이후 휴지통 이동/삭제된 사진도 재검사한다.
        val hash = transaction {
            Assets.select(Assets.hash).where {
                (Assets.id eq id) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull() and
                    Assets.sourceTag.isNull() and (Assets.mediaType eq "PHOTO")
            }.firstOrNull()?.get(Assets.hash)
        } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found")
        val path = thumbnails.thumbPath(hash, size)
        if (!Files.isRegularFile(path)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not ready")
        return FileSystemResource(path)
    }
}
