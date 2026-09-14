package com.homephoto.server.search

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.api.AssetDto
import com.homephoto.server.api.toAssetDto
import com.homephoto.server.db.Assets
import com.homephoto.server.service.PhotoDateRange
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class PhotoSearchUnavailable : RuntimeException("로컬 의미 검색 서비스를 사용할 수 없어요. 실행 상태와 모델 설정을 확인해 주세요.")

data class SemanticResult(val items: List<AssetDto>, val indexedPhotos: Long, val model: String)

@Service
class PhotoSemanticSearch(private val props: PhotoSearchProperties, private val mapper: ObjectMapper) {
    val enabled get() = props.enabled
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    init { if (props.enabled) props.validate() }

    fun search(text: String, range: PhotoDateRange?, limit: Int): SemanticResult {
        require(text.isNotBlank() && text.length <= 500) { "검색 문장은 1~500자여야 합니다." }
        require(limit in 1..24) { "limit은 1~24입니다." }
        if (!enabled) throw PhotoSearchUnavailable()
        val response = try {
            http.send(HttpRequest.newBuilder(URI("${props.baseUrl}/search"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer ${props.token}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(mapOf(
                    "query" to text, "start_date" to range?.start?.toString(), "end_date" to range?.end?.toString(),
                    "limit" to 200)))).build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw PhotoSearchUnavailable()
        } catch (e: java.io.IOException) { throw PhotoSearchUnavailable() }
        if (response.statusCode() != 200) throw PhotoSearchUnavailable()
        val body = mapper.readTree(response.body())
        val ids = body.path("ids").take(200).map { it.asLong() }.filter { it > 0 }.distinct()
        // 검색 인덱스가 아직 삭제/촬영일 변경을 따라잡지 못해도 현재 SQLite 권한 범위를 재검사한다.
        val byId = transaction {
            var query = Assets.selectAll().where { (Assets.id inList ids) and Assets.deletedAt.isNull() and
                Assets.purgedAt.isNull() and Assets.sourceTag.isNull() and (Assets.mediaType eq "PHOTO") }
            range?.let { r -> query = query.andWhere { (Assets.takenAt greaterEq r.start.toString()) and
                (Assets.takenAt less r.end.plusDays(1).toString()) } }
            query.map { it.toAssetDto() }.associateBy { it.id }
        }
        return SemanticResult(ids.mapNotNull { byId[it] }.take(limit), body.path("indexed_photos").asLong(), body.path("model").asText())
    }
}
