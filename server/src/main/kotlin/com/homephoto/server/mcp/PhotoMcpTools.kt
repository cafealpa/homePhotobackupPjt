package com.homephoto.server.mcp

import com.homephoto.server.api.AssetDto
import com.homephoto.server.api.toAssetDto
import com.homephoto.server.db.Assets
import com.homephoto.server.service.AssetFilter
import com.homephoto.server.service.AssetQueryService
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime

class PhotoMcpTools(private val query: AssetQueryService, private val previews: PhotoPreviewService) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun specifications(): List<SyncToolSpecification> = listOf(
        tool("search_photos", "날짜로 사진 찾기",
            "홈 포토의 촬영일 기준 사진 검색. date는 YYYY-MM-DD. 연도가 없고 대화에서도 알 수 없으면 사용자에게 연도를 물어보세요. 업로드일 검색이 아닙니다. 휴지통, 동영상, 키즈노트 전용 사진은 제외합니다.",
            mapOf("date" to mapOf("type" to "string", "format" to "date"),
                "cursor" to mapOf("type" to "string", "maxLength" to 100),
                "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 24, "default" to 12)),
            listOf("date"), ::search),
        tool("get_photo", "사진 크게 보기",
            "검색 결과의 photo_id로 사진 상세와 새로운 미리보기 URL을 가져옵니다. 미리보기 URL 만료 시 다시 호출하세요.",
            mapOf("photo_id" to mapOf("type" to "integer", "minimum" to 1)), listOf("photo_id"), ::get),
    )

    private fun tool(name: String, title: String, description: String, properties: Map<String, Any>,
                     required: List<String>, handler: (Map<String, Any>) -> McpSchema.CallToolResult): SyncToolSpecification {
        val definition = McpSchema.Tool.builder().name(name).title(title).description(description)
            .inputSchema(McpSchema.JsonSchema("object", properties, required, false, null, null))
            .annotations(McpSchema.ToolAnnotations(title, true, false, true, false, null))
            .meta(mapOf("ui" to mapOf("resourceUri" to GALLERY_URI), "openai/outputTemplate" to GALLERY_URI))
            .build()
        return SyncToolSpecification(definition) { _, request ->
            try {
                val args = request.arguments().orEmpty()
                require(args.keys.all { it in properties }) { "지원하지 않는 검색 조건입니다." }
                handler(args)
            } catch (e: IllegalArgumentException) {
                error(e.message ?: "검색 조건을 확인해 주세요.")
            } catch (e: java.time.DateTimeException) {
                error("실제 존재하는 날짜를 YYYY-MM-DD 형식으로 입력해 주세요.")
            } catch (e: Exception) {
                log.error("MCP photo tool failed: {}", name, e)
                error("사진 서버에서 조회하지 못했어요. 잠시 후 다시 시도해 주세요.")
            }
        }
    }

    private fun search(args: Map<String, Any>): McpSchema.CallToolResult {
        val date = args["date"] as? String ?: throw IllegalArgumentException("연도가 포함된 date가 필요합니다.")
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(date)) { "날짜는 YYYY-MM-DD 형식이어야 합니다." }
        LocalDate.parse(date)
        val limit = if ("limit" in args) integer(args["limit"], "limit").also {
            require(it in 1..24) { "limit은 1~24입니다." }
        }.toInt() else 12
        val cursor = if ("cursor" in args) args["cursor"] as? String
            ?: throw IllegalArgumentException("cursor는 문자열이어야 합니다.") else null
        cursor?.let {
            require(it.length <= 100 && it.startsWith("${date}T")) { "같은 날짜의 검색 결과 cursor를 사용해 주세요." }
            val parts = it.split('~')
            require(parts.size == 2 && (parts[1].toLongOrNull() ?: 0) > 0) { "올바른 cursor가 아닙니다." }
            LocalDateTime.parse(parts[0])
        }
        // 한 장을 더 조회해 마지막 페이지에 불필요한 '더 보기'가 생기지 않게 한다.
        val page = query.list(AssetFilter(day = date, cursor = cursor, limit = limit + 1, mediaType = "PHOTO"))
        val items = page.items.take(limit)
        val next = if (page.items.size > limit) items.last().let { "${it.takenAt}~${it.id}" } else null
        val data = mapOf("date" to date, "items" to items.map(::metadata), "nextCursor" to next)
        return result(data, items, if (items.isEmpty()) "$date 사진이 없어요." else "$date 사진 ${items.size}장을 표시합니다.")
    }

    private fun get(args: Map<String, Any>): McpSchema.CallToolResult {
        val id = integer(args["photo_id"], "photo_id")
        require(id > 0) { "photo_id는 양수여야 합니다." }
        val item = transaction {
            Assets.selectAll().where {
                (Assets.id eq id) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull() and
                    Assets.sourceTag.isNull() and (Assets.mediaType eq "PHOTO")
            }.firstOrNull()?.toAssetDto()
        } ?: return error("사진을 찾을 수 없어요. 삭제되었거나 조회 대상이 아닐 수 있어요.")
        return result(mapOf("items" to listOf(metadata(item)), "nextCursor" to null), listOf(item), "사진을 표시합니다.")
    }

    private fun integer(value: Any?, field: String): Long {
        require(value is Number) { "$field 값은 정수여야 합니다." }
        return value.toString().toLongOrNull() ?: throw IllegalArgumentException("$field 값은 정수여야 합니다.")
    }

    // 원본 경로, 해시, GPS, 기기 정보와 인증 정보는 모델에 전달하지 않는다.
    private fun metadata(item: AssetDto) = mapOf("id" to item.id, "takenAt" to item.takenAt,
        "takenAtSource" to item.takenAtSource, "width" to item.width, "height" to item.height)

    private fun result(data: Map<String, Any?>, items: List<AssetDto>, text: String) =
        McpSchema.CallToolResult.builder().structuredContent(data)
            .content(listOf(McpSchema.TextContent(text)))
            .meta(mapOf("previews" to items.associate { it.id.toString() to previews.urls(it.id) }))
            .isError(false).build()

    private fun error(message: String) = McpSchema.CallToolResult.builder()
        .content(listOf(McpSchema.TextContent(message))).isError(true).build()

    companion object { const val GALLERY_URI = "ui://homephoto/gallery-v1.html" }
}
