package com.homephoto.server.publication

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.service.AtomicFiles
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path

/** 영상 snapshot 옆에 비공개 업로드 세션을 보관한다. 재시도/재시작 시 Google의 수신 offset부터 이어 올린다. */
class GooglePhotosResumableUpload(
    private val mapper: ObjectMapper,
    private val send: (URI, HttpRequest.BodyPublisher, Map<String, String>) -> HttpResponse<String>,
) {
    data class Session(val url: String, val connection: String, val size: Long, val contentType: String,
                       val granularity: Long, val createdAt: Long, val token: String? = null, val tokenCreatedAt: Long = 0)

    fun upload(file: Path, endpoint: URI, connection: String, contentType: String): String {
        val size = Files.size(file)
        val checkpoint = sessionPath(file)
        fun save(session: Session) = AtomicFiles.write(checkpoint) { mapper.writeValue(it.toFile(), session) }
        fun start(): Session {
            val response = send(endpoint.resolve("/v1/uploads"), HttpRequest.BodyPublishers.noBody(), mapOf(
                "X-Goog-Upload-Command" to "start", "X-Goog-Upload-Protocol" to "resumable",
                "X-Goog-Upload-Content-Type" to contentType, "X-Goog-Upload-Raw-Size" to size.toString()))
            val url = response.headers().firstValue("X-Goog-Upload-URL").orElseThrow {
                PublicationFailure(PublicationFailure.Kind.RETRYABLE, "UPLOAD_SESSION_MISSING")
            }
            val granularity = response.headers().firstValue("X-Goog-Upload-Chunk-Granularity").orElse("").toLongOrNull()
                ?.takeIf { it in 1..CHUNK_SIZE }
                ?: throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "UPLOAD_CHUNK_SIZE_UNSUPPORTED")
            validateUrl(url, endpoint)
            return Session(url, connection, size, contentType, granularity, System.currentTimeMillis()).also(::save)
        }
        var session = if (Files.isRegularFile(checkpoint)) mapper.readValue(checkpoint.toFile(), Session::class.java).takeIf {
            it.connection == connection && it.size == size && it.contentType == contentType &&
                System.currentTimeMillis() - it.createdAt in 0 until 7 * 24 * 60 * 60 * 1000L
        } else null
        var offset = 0L
        if (session != null) {
            validateUrl(session.url, endpoint)
            if (session.token != null && System.currentTimeMillis() - session.tokenCreatedAt in 0 until 23 * 60 * 60 * 1000L)
                return session.token
            // 만료된 byte token은 같은 snapshot을 새 세션으로 전송한다. 아직 media item 생성 전이므로 안전하다.
            if (session.token != null) session = null
            else {
                val response = try { send(URI(session.url), HttpRequest.BodyPublishers.noBody(), mapOf("X-Goog-Upload-Command" to "query")) }
                catch (error: PublicationFailure) {
                    if (error.code !in listOf("HTTP_404", "HTTP_410")) throw error
                    null
                }
                if (response?.headers()?.firstValue("X-Goog-Upload-Status")?.orElse("") == "active") {
                    offset = response.headers().firstValue("X-Goog-Upload-Size-Received").orElse("").toLongOrNull()
                        ?.takeIf { it in 0..size }
                        ?: throw PublicationFailure(PublicationFailure.Kind.RETRYABLE, "UPLOAD_OFFSET_INVALID")
                } else session = null // 종료된 세션은 재사용하지 않는다. 응답 유실 시 bytes만 다시 보내고 생성은 기존 큐가 제어한다.
            }
        }
        val active = session ?: start()
        val chunkSize = (CHUNK_SIZE / active.granularity) * active.granularity
        FileChannel.open(file).use { channel ->
            while (true) {
                val count = minOf(chunkSize, size - offset).toInt()
                val bytes = ByteArray(count)
                val buffer = ByteBuffer.wrap(bytes)
                channel.position(offset)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "EXPORT_FILE_CHANGED")
                }
                val final = offset + count == size
                val response = send(URI(active.url), HttpRequest.BodyPublishers.ofByteArray(bytes), mapOf(
                    "X-Goog-Upload-Command" to if (final) "upload, finalize" else "upload",
                    "X-Goog-Upload-Offset" to offset.toString()))
                if (final) {
                    val token = response.body().trim().takeIf { it.isNotEmpty() }
                        ?: throw PublicationFailure(PublicationFailure.Kind.RETRYABLE, "EMPTY_UPLOAD_TOKEN")
                    save(active.copy(token = token, tokenCreatedAt = System.currentTimeMillis()))
                    return token
                }
                offset += count
            }
        }
    }

    private fun validateUrl(value: String, endpoint: URI) {
        val url = URI(value)
        if (url.scheme != endpoint.scheme || url.host != endpoint.host || url.port != endpoint.port || url.userInfo != null)
            throw PublicationFailure(PublicationFailure.Kind.PERMANENT, "UPLOAD_SESSION_URL_INVALID")
    }

    companion object {
        private const val CHUNK_SIZE = 8 * 1024 * 1024L
        fun sessionPath(file: Path): Path = file.resolveSibling("${file.fileName}.upload.json")
    }
}
