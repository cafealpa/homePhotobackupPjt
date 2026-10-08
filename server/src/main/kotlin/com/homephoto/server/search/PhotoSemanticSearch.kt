package com.homephoto.server.search

import com.homephoto.server.api.AssetDto
import com.homephoto.server.api.toAssetDto
import com.homephoto.server.db.Assets
import com.homephoto.server.service.PhotoDateRange
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service

class PhotoSearchUnavailable : RuntimeException("로컬 의미 검색 서비스를 사용할 수 없어요. 실행 상태와 모델 설정을 확인해 주세요.")

data class SemanticResult(val items: List<AssetDto>, val indexedPhotos: Long, val model: String)

@Service
class PhotoSemanticSearch(private val props: PhotoSearchProperties, private val backend: SearchBackend) {
    val enabled get() = props.enabled

    fun search(text: String, range: PhotoDateRange?, limit: Int): SemanticResult {
        require(text.isNotBlank() && text.length <= 500) { "검색 문장은 1~500자여야 합니다." }
        require(limit in 1..24) { "limit은 1~24입니다." }
        return retrieve(text, range, limit)
    }

    /** Bounded candidates for caption search; the public API keeps its 24-result limit. */
    fun captionCandidates(text: String): List<Long> {
        require(text.isNotBlank() && text.length <= 200)
        return retrieve(text, null, 200).items.map { it.id }
    }

    private fun retrieve(text: String, range: PhotoDateRange?, limit: Int): SemanticResult {
        if (!enabled) throw PhotoSearchUnavailable()
        val result = backend.photos(text, range)
        val hits = result.hits.take(200).distinctBy { it.id }
        val ids = hits.map { it.id }
        val fingerprints = hits.associate { it.id to it.fingerprint }
        // 검색 인덱스가 아직 삭제/촬영일 변경을 따라잡지 못해도 현재 SQLite 권한 범위를 재검사한다.
        val byId = transaction {
            var query = Assets.selectAll().where { (Assets.id inList ids) and Assets.deletedAt.isNull() and
                Assets.purgedAt.isNull() and Assets.sourceTag.isNull() and (Assets.mediaType eq "PHOTO") }
            range?.let { r -> query = query.andWhere { (Assets.takenAt greaterEq r.start.toString()) and
                (Assets.takenAt less r.end.plusDays(1).toString()) } }
            query.filter { fingerprints[it[Assets.id]] == it[Assets.hash] }.map { it.toAssetDto() }.associateBy { it.id }
        }
        return SemanticResult(ids.mapNotNull { byId[it] }.take(limit), result.indexed, SiglipEncoder.ID)
    }
}
