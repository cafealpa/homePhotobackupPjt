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
import com.homephoto.server.service.PhotoDateRange
import java.time.LocalDateTime

class PhotoMcpTools(private val query: AssetQueryService, private val previews: PhotoPreviewService,
                    private val publicOAuth: Boolean = false) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun specifications(): List<SyncToolSpecification> = listOf(
        tool("search_photos", "날짜로 사진 찾기",
            "홈 포토의 촬영일 기준 사진 검색. 하루는 date, 기간은 start_date와 end_date를 함께 사용하며 혼용하지 마세요. 모두 YYYY-MM-DD이고 시작일과 종료일을 포함합니다. 월 검색은 해당 월의 첫날과 마지막 날을 지정하세요. 연도가 없고 대화에서도 알 수 없으면 사용자에게 연도를 물어보세요. 업로드일 검색이 아닙니다. 휴지통, 동영상, 키즈노트 전용 사진은 제외합니다. 반환 장수는 현재 페이지 장수이며 전체 장수가 아닙니다.",
            mapOf("date" to mapOf("type" to "string", "format" to "date"),
                "start_date" to mapOf("type" to "string", "format" to "date", "description" to "촬영 시작일 (포함), end_date와 함께 사용"),
                "end_date" to mapOf("type" to "string", "format" to "date", "description" to "촬영 종료일 (포함), start_date와 함께 사용"),
                "cursor" to mapOf("type" to "string", "maxLength" to 100),
                "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 24, "default" to 12)),
            emptyList(), ::search),
        tool("count_photos", "사진 전체 장수 집계",
            "촬영일 조건에 맞는 사진 전체 장수를 DB에서 집계합니다. 총 몇 장인지 물으면 이 도구를 사용하세요. 하루는 date, 기간은 start_date와 end_date를 함께 사용하며 혼용하지 마세요. YYYY-MM-DD 형식이며 양쪽 날짜를 포함합니다. 월은 첫날부터 마지막 날까지 지정하세요. 연도가 불분명하면 확인하세요. 휴지통, 영구 삭제, 동영상, 키즈노트 전용 사진은 제외합니다. 인물이나 음식 조건은 지원하지 않습니다.",
            mapOf("date" to mapOf("type" to "string", "format" to "date"),
                "start_date" to mapOf("type" to "string", "format" to "date"),
                "end_date" to mapOf("type" to "string", "format" to "date")),
            emptyList(), ::count),
        tool("get_photo", "사진 크게 보기",
            "검색 결과의 photo_id로 사진 상세와 새로운 미리보기 URL을 가져옵니다. 미리보기 URL 만료 시 다시 호출하세요.",
            mapOf("photo_id" to mapOf("type" to "integer", "minimum" to 1)), listOf("photo_id"), ::get),
    )

    private fun tool(name: String, title: String, description: String, properties: Map<String, Any>,
                     required: List<String>, handler: (Map<String, Any>, String) -> McpSchema.CallToolResult): SyncToolSpecification {
        val definition = McpSchema.Tool.builder().name(name).title(title).description(description)
            .inputSchema(McpSchema.JsonSchema("object", properties, required, false, null, null))
            .annotations(McpSchema.ToolAnnotations(title, true, false, true, false, null))
            .meta((if (name == "count_photos") emptyMap() else mapOf("ui" to mapOf("resourceUri" to GALLERY_URI), "openai/outputTemplate" to GALLERY_URI)) +
                if (publicOAuth) mapOf("securitySchemes" to listOf(mapOf("type" to "oauth2", "scopes" to listOf("photos:read")))) else emptyMap())
            .build()
        return SyncToolSpecification(definition) { context, request ->
            try {
                val grant = context.get("photoGrantId") as? String ?: ""
                check(!publicOAuth || grant.isNotEmpty()) { "Photo grant missing from MCP context" }
                val args = request.arguments().orEmpty()
                require(args.keys.all { it in properties }) { "지원하지 않는 검색 조건입니다." }
                handler(args, grant)
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

    private fun search(args: Map<String, Any>, grant: String): McpSchema.CallToolResult {
        val range = PhotoDateRange.parse(args)
        val (start, end, singleDay) = range
        val limit = if ("limit" in args) integer(args["limit"], "limit").also {
            require(it in 1..24) { "limit은 1~24입니다." }
        }.toInt() else 12
        val cursor = if ("cursor" in args) args["cursor"] as? String
            ?: throw IllegalArgumentException("cursor는 문자열이어야 합니다.") else null
        cursor?.let {
            require(it.length <= 100) { "올바른 cursor가 아닙니다." }
            val parts = it.split('~')
            require(parts.size == 2 && (parts[1].toLongOrNull() ?: 0) > 0) { "올바른 cursor가 아닙니다." }
            val cursorDate = LocalDateTime.parse(parts[0]).toLocalDate()
            require(!cursorDate.isBefore(start) && !cursorDate.isAfter(end)) { "검색 기간 안의 cursor를 사용해 주세요." }
        }
        // 한 장을 더 조회해 마지막 페이지에 불필요한 '더 보기'가 생기지 않게 한다.
        val page = query.list(AssetFilter(startDate = start, endDate = end, cursor = cursor, limit = limit + 1, mediaType = "PHOTO"))
        val items = page.items.take(limit)
        val next = if (page.items.size > limit) items.last().let { "${it.takenAt}~${it.id}" } else null
        val data = mapOf("date" to if (singleDay) start.toString() else null,
            "start_date" to start.toString(), "end_date" to end.toString(),
            "items" to items.map(::metadata), "nextCursor" to next)
        val label = if (start == end) start.toString() else "$start ~ $end"
        return result(data, items, if (items.isEmpty()) "$label 사진이 없어요." else "$label 사진 ${items.size}장을 표시합니다.", grant)
    }

    private fun count(args: Map<String, Any>, grant: String): McpSchema.CallToolResult {
        val range = PhotoDateRange.parse(args)
        val total = query.count(range.filter())
        return McpSchema.CallToolResult.builder()
            .structuredContent(range.metadata() + mapOf("count" to total, "media_type" to "PHOTO"))
            .content(listOf(McpSchema.TextContent("${range.label} 사진은 총 ${total}장입니다.")))
            .isError(false).build()
    }

    private fun get(args: Map<String, Any>, grant: String): McpSchema.CallToolResult {
        val id = integer(args["photo_id"], "photo_id")
        require(id > 0) { "photo_id는 양수여야 합니다." }
        val item = transaction {
            Assets.selectAll().where {
                (Assets.id eq id) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull() and
                    Assets.sourceTag.isNull() and (Assets.mediaType eq "PHOTO")
            }.firstOrNull()?.toAssetDto()
        } ?: return error("사진을 찾을 수 없어요. 삭제되었거나 조회 대상이 아닐 수 있어요.")
        return result(mapOf("items" to listOf(metadata(item)), "nextCursor" to null), listOf(item), "사진을 표시합니다.", grant)
    }

    private fun integer(value: Any?, field: String): Long {
        require(value is Number) { "$field 값은 정수여야 합니다." }
        return value.toString().toLongOrNull() ?: throw IllegalArgumentException("$field 값은 정수여야 합니다.")
    }

    // 원본 경로, 해시, GPS, 기기 정보와 인증 정보는 모델에 전달하지 않는다.
    private fun metadata(item: AssetDto) = mapOf("id" to item.id, "takenAt" to item.takenAt,
        "takenAtSource" to item.takenAtSource, "width" to item.width, "height" to item.height)

    private fun result(data: Map<String, Any?>, items: List<AssetDto>, text: String, grant: String) =
        McpSchema.CallToolResult.builder().structuredContent(data)
            .content(listOf(McpSchema.TextContent(text)))
            .meta(mapOf("previews" to items.associate { it.id.toString() to previews.urls(it.id, grant) }))
            .isError(false).build()

    private fun error(message: String) = McpSchema.CallToolResult.builder()
        .content(listOf(McpSchema.TextContent(message))).isError(true).build()

    companion object { const val GALLERY_URI = "ui://homephoto/gallery-v1.html" }
}
