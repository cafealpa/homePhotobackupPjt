package com.homephoto.server.api

import com.homephoto.server.db.Assets
import com.homephoto.server.db.KidsnoteChildren
import com.homephoto.server.db.KidsnotePostImages
import com.homephoto.server.db.KidsnotePosts
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.min
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** 키즈노트 뷰어(kidsnote.html) 조회 API. 사진/영상 바이트는 기존 /assets/{id}/thumb·file을 재사용한다. */
@RestController
@RequestMapping("/api/v1/kidsnote")
class KidsnoteController {

    data class ChildDto(
        val id: Long,
        val folderName: String,
        val childName: String,
        val firstDate: String?,
        val lastDate: String?,
        val postCount: Long,
        val imageCount: Long,
    )

    data class KnMonthDto(val yearMonth: String, val postCount: Long, val imageCount: Long)

    data class PostImageDto(val assetId: Long, val filename: String, val width: Int?, val height: Int?)

    data class PostVideoDto(val status: String, val assetId: Long?, val originalFileName: String?)

    data class PostDto(
        val postId: Long,
        val dateWritten: String,
        val content: String,
        val authorName: String,
        val createdAt: String,
        val images: List<PostImageDto>,
        val video: PostVideoDto?,
    )

    @GetMapping("/children")
    fun children(): List<ChildDto> = transaction {
        val postCnt = KidsnotePosts.id.count()
        val firstDate = KidsnotePosts.dateWritten.min()
        val lastDate = KidsnotePosts.dateWritten.max()
        val imageCounts = imageCountByChild()

        KidsnoteChildren.join(
            KidsnotePosts, JoinType.LEFT,
            onColumn = KidsnoteChildren.id, otherColumn = KidsnotePosts.childId,
        )
            .select(KidsnoteChildren.id, KidsnoteChildren.folderName, KidsnoteChildren.childName, postCnt, firstDate, lastDate)
            .groupBy(KidsnoteChildren.id, KidsnoteChildren.folderName, KidsnoteChildren.childName)
            .orderBy(KidsnoteChildren.id to SortOrder.ASC)
            .map {
                ChildDto(
                    id = it[KidsnoteChildren.id],
                    folderName = it[KidsnoteChildren.folderName],
                    childName = it[KidsnoteChildren.childName],
                    firstDate = it[firstDate],
                    lastDate = it[lastDate],
                    postCount = it[postCnt],
                    imageCount = imageCounts[it[KidsnoteChildren.id]] ?: 0L,
                )
            }
    }

    @GetMapping("/children/{id}/months")
    fun months(@PathVariable id: Long): List<KnMonthDto> = transaction {
        val postCnt = KidsnotePosts.id.count()
        val imageCounts = imageCountByChildMonth(id)
        KidsnotePosts.select(KidsnotePosts.yearMonth, postCnt)
            .where { KidsnotePosts.childId eq id }
            .groupBy(KidsnotePosts.yearMonth)
            .orderBy(KidsnotePosts.yearMonth to SortOrder.ASC)
            .map { KnMonthDto(it[KidsnotePosts.yearMonth], it[postCnt], imageCounts[it[KidsnotePosts.yearMonth]] ?: 0L) }
    }

    @GetMapping("/children/{id}/posts")
    fun posts(@PathVariable id: Long, @RequestParam yearMonth: String): List<PostDto> = transaction {
        require(YEAR_MONTH.matches(yearMonth)) { "yearMonth 형식은 yyyy-MM 입니다: $yearMonth" }
        val postRows = KidsnotePosts.selectAll()
            .where { (KidsnotePosts.childId eq id) and (KidsnotePosts.yearMonth eq yearMonth) }
            .orderBy(KidsnotePosts.dateWritten to SortOrder.ASC, KidsnotePosts.postId to SortOrder.ASC)
            .toList()
        toPostDtos(postRows)
    }

    @GetMapping("/posts/{postId}")
    fun post(@PathVariable postId: Long): PostDto = transaction {
        val row = KidsnotePosts.selectAll().where { KidsnotePosts.postId eq postId }.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "post $postId not found")
        toPostDtos(listOf(row)).first()
    }

    /** 글 행들 → DTO. 이미지 링크는 한 번에 조회해 메모리에서 글별로 묶는다. */
    private fun toPostDtos(postRows: List<org.jetbrains.exposed.sql.ResultRow>): List<PostDto> {
        if (postRows.isEmpty()) return emptyList()
        val postIds = postRows.map { it[KidsnotePosts.postId] }
        val imagesByPost = KidsnotePostImages.join(
            Assets, JoinType.INNER,
            onColumn = KidsnotePostImages.assetId, otherColumn = Assets.id,
        )
            .select(KidsnotePostImages.postId, KidsnotePostImages.assetId, KidsnotePostImages.filename, KidsnotePostImages.seq, Assets.width, Assets.height)
            .where { KidsnotePostImages.postId inList postIds }
            .orderBy(KidsnotePostImages.seq to SortOrder.ASC)
            .groupBy(
                keySelector = { it[KidsnotePostImages.postId] },
                valueTransform = {
                    PostImageDto(
                        assetId = it[KidsnotePostImages.assetId],
                        filename = it[KidsnotePostImages.filename],
                        width = it[Assets.width],
                        height = it[Assets.height],
                    )
                },
            )
        return postRows.map { row ->
            PostDto(
                postId = row[KidsnotePosts.postId],
                dateWritten = row[KidsnotePosts.dateWritten],
                content = row[KidsnotePosts.content],
                authorName = row[KidsnotePosts.authorName],
                createdAt = row[KidsnotePosts.createdAt],
                images = imagesByPost[row[KidsnotePosts.postId]] ?: emptyList(),
                video = row[KidsnotePosts.videoStatus]?.let { status ->
                    PostVideoDto(
                        status = status,
                        assetId = row[KidsnotePosts.videoAssetId],
                        originalFileName = row[KidsnotePosts.videoOriginalName],
                    )
                },
            )
        }
    }

    /** 아이별 사진 수 (posts ⋈ post_images) */
    private fun imageCountByChild(): Map<Long, Long> {
        val cnt = KidsnotePostImages.id.count()
        return KidsnotePosts.join(
            KidsnotePostImages, JoinType.INNER,
            onColumn = KidsnotePosts.postId, otherColumn = KidsnotePostImages.postId,
        )
            .select(KidsnotePosts.childId, cnt)
            .groupBy(KidsnotePosts.childId)
            .associate { it[KidsnotePosts.childId] to it[cnt] }
    }

    private fun imageCountByChildMonth(childId: Long): Map<String, Long> {
        val cnt = KidsnotePostImages.id.count()
        return KidsnotePosts.join(
            KidsnotePostImages, JoinType.INNER,
            onColumn = KidsnotePosts.postId, otherColumn = KidsnotePostImages.postId,
        )
            .select(KidsnotePosts.yearMonth, cnt)
            .where { KidsnotePosts.childId eq childId }
            .groupBy(KidsnotePosts.yearMonth)
            .associate { it[KidsnotePosts.yearMonth] to it[cnt] }
    }

    companion object {
        private val YEAR_MONTH = Regex("""\d{4}-\d{2}""")
    }
}
