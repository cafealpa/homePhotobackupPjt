package com.homephoto.server.publication

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.time.Duration
import java.time.Instant

/** PoC 전용 일회성 OAuth/업로드. 운영 인증정보나 refresh token을 저장하지 않는다. */
object GooglePhotosPocUpload {
    private val mapper = jacksonObjectMapper()
    fun upload(dir: Path, clientFile: Path) {
        val manifest = mapper.readTree(dir.resolve("manifest.json").toFile())
        val items = manifest.path("items").toList()
        require(items.size in 3..5) { "PoC 업로드는 3~5장으로 제한합니다." }
        val marker = dir.resolve("upload-started.txt")
        check(!Files.exists(marker)) { "이미 업로드를 시작한 세트입니다. api-results.json과 Google Photos를 먼저 확인하세요." }
        val files = items.map { item ->
            val path = dir.resolve(item.path("file").asText()).normalize()
            require(path.parent == dir.normalize())
            check(GooglePhotosMetadataPoc.checksum(path) == item.path("sha256").asText()) { "준비한 이미지가 변경되었습니다." }
            path
        }
        val token = authorize(clientFile)
        Files.writeString(marker, "시작: ${Instant.now()}\n오류 후 자동 재실행 금지. 실제 생성 여부를 먼저 확인하세요.\n", CREATE_NEW)
        val client = GooglePhotosPocClient(token)
        val result = client.publish(files.zip(items.map { it.path("sample").path("fileName").asText() }))
        mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("api-results.json").toFile(), result)
        println("API 결과 저장 완료: ${dir.resolve("api-results.json").toAbsolutePath()}")
        println("실제 날짜·GPS·파일명·화질은 Google Photos에서 확인하고 UI-RESULTS.md에 기록하세요.")
    }

    private fun authorize(clientFile: Path): String = GooglePhotosDesktopOAuth.authorize(clientFile).accessToken
}

/** byte 업로드 후 batchCreate를 한 번 호출한다. 불확실한 결과를 자동 재시도하지 않는다. */
class GooglePhotosPocClient(
    private val accessToken: String,
    private val endpoint: URI = URI("https://photoslibrary.googleapis.com"),
) {
    private val mapper = jacksonObjectMapper()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    fun publish(items: List<Pair<Path, String>>): List<Map<String, Any?>> {
        require(items.size in 3..5)
        val tokens = items.map { (file, _) ->
            val response = send("/v1/uploads", HttpRequest.BodyPublishers.ofFile(file), "application/octet-stream", true)
            response.body().trim().also { check(it.isNotBlank()) { "upload token이 없습니다." } }
        }
        val payload = mapOf("newMediaItems" to items.mapIndexed { index, (_, name) ->
            mapOf("simpleMediaItem" to mapOf("uploadToken" to tokens[index], "fileName" to name))
        })
        val response = send("/v1/mediaItems:batchCreate", HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)), "application/json")
        val results = mapper.readTree(response.body()).path("newMediaItemResults").toList()
        check(results.size == items.size) { "응답 항목 수가 일치하지 않습니다. 실제 생성 결과를 확인하세요." }
        return tokens.mapIndexed { index, token ->
            val row = results.singleOrNull { it.path("uploadToken").asText() == token }
                ?: error("업로드 token에 대응하는 응답이 없습니다. 실제 생성 결과를 확인하세요.")
            val media = row.path("mediaItem")
            mapOf("requestedFileName" to items[index].second, "statusCode" to row.path("status").path("code").asInt(),
                "mediaItemId" to media.text("id"), "productUrl" to media.text("productUrl"),
                "fileName" to media.text("filename"), "creationTime" to media.path("mediaMetadata").text("creationTime"),
                "width" to media.path("mediaMetadata").text("width"), "height" to media.path("mediaMetadata").text("height"))
        }
    }

    private fun send(path: String, body: HttpRequest.BodyPublisher, type: String, bytes: Boolean = false): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer $accessToken").header("Content-Type", type).POST(body)
        if (bytes) builder.header("X-Goog-Upload-Content-Type", "image/jpeg").header("X-Goog-Upload-Protocol", "raw")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Google Photos $path 실패 (HTTP ${response.statusCode()}). 자동 재시도하지 않습니다." }
        return response
    }

    private fun JsonNode.text(field: String): String? = path(field).takeUnless { it.isMissingNode || it.isNull }?.asText()
}
