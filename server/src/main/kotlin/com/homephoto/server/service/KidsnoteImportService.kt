package com.homephoto.server.service

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.api.KidsnoteImportStatusDto
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.KidsnoteChildren
import com.homephoto.server.db.KidsnotePostImages
import com.homephoto.server.db.KidsnotePosts
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * 키즈노트 백업 폴더(E:\kidsnote) 임포터.
 *
 * 폴더 구조: {루트}\{아이}\{yyyy}\{yyyy-MM}\{yyyy-MM-dd}\content.json + {글ID}_{이미지ID}.jpg
 * content.json은 그날 알림장 글의 배열이며, 글 id(전역 고유)가 재임포트 멱등성 키다.
 * 사진은 AssetIngestService로 정식 인제스트(source=KIDSNOTE, ML 작업 생략)하고,
 * 영상은 CDN URL에서 다운로드를 시도해 성공 시 VIDEO 자산으로 저장, 실패 시 LOST로 기록한다.
 */
@Service
class KidsnoteImportService(
    private val ingestService: AssetIngestService,
    private val props: AppProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    @Volatile
    private var status = KidsnoteImportStatusDto(
        running = false, totalDays = 0, processedDays = 0,
        posts = 0, newPosts = 0, images = 0, newImages = 0,
        videosDownloaded = 0, videosLost = 0, failed = 0,
        currentFolder = null, lastError = null,
    )

    fun status(): KidsnoteImportStatusDto = status

    data class KidsnotePostJson(
        val id: Long,
        @param:JsonProperty("date_written") val dateWritten: String,
        val content: String = "",
        val images: List<String> = emptyList(),
        val video: KidsnoteVideoJson? = null,
        @param:JsonProperty("author_name") val authorName: String = "",
        @param:JsonProperty("child_name") val childName: String = "",
        @param:JsonProperty("created_at") val createdAt: String = "",
    )

    data class KidsnoteVideoJson(
        @param:JsonProperty("original_file_name") val originalFileName: String? = null,
        @param:JsonProperty("high_url") val highUrl: String? = null,
        @param:JsonProperty("low_url") val lowUrl: String? = null,
    )

    /** @return false면 이미 실행 중 */
    fun start(sourcePath: String): Boolean {
        val root = Path.of(sourcePath)
        require(Files.isDirectory(root)) { "not a directory: $sourcePath" }
        if (!running.compareAndSet(false, true)) return false

        thread(name = "kidsnote-import", isDaemon = true) {
            try {
                runImport(root)
            } catch (e: Exception) {
                log.error("키즈노트 임포트 중단: ${e.message}", e)
                status = status.copy(lastError = e.message)
            } finally {
                status = status.copy(running = false, currentFolder = null)
                running.set(false)
            }
        }
        return true
    }

    private fun runImport(root: Path) {
        // 아이 폴더별로 content.json 전체를 미리 수집 (진행률 분모 + 날짜 오름차순 처리)
        val childDirs = Files.list(root).use { s -> s.filter { it.isDirectory() }.sorted().toList() }
        val dayFiles: List<Pair<Path, Path>> = childDirs.flatMap { childDir ->
            Files.walk(childDir).use { s ->
                s.filter { it.name == "content.json" }.sorted().toList()
            }.map { childDir to it }
        }

        status = KidsnoteImportStatusDto(
            running = true, totalDays = dayFiles.size, processedDays = 0,
            posts = 0, newPosts = 0, images = 0, newImages = 0,
            videosDownloaded = 0, videosLost = 0, failed = 0,
            currentFolder = null, lastError = null,
        )
        log.info("키즈노트 임포트 시작: {}개 일자 폴더 ({})", dayFiles.size, root)

        val childIds = mutableMapOf<String, Long>() // folderName → kidsnote_children.id

        for ((childDir, contentJson) in dayFiles) {
            val dayDir = contentJson.parent
            status = status.copy(currentFolder = "${childDir.name}/${dayDir.name}")
            try {
                val posts = objectMapper.readValue(contentJson.toFile(), Array<KidsnotePostJson>::class.java)
                for (post in posts) {
                    val childId = childIds.getOrPut(childDir.name) { upsertChild(childDir.name, post.childName) }
                    processPost(post, dayDir, childId)
                }
            } catch (e: Exception) {
                log.warn("일자 폴더 처리 실패 {}: {}", dayDir, e.message)
                status = status.copy(failed = status.failed + 1, lastError = "${dayDir.name}: ${e.message}")
            }
            status = status.copy(processedDays = status.processedDays + 1)
            if (status.processedDays % 50 == 0) {
                log.info(
                    "키즈노트 진행: {}/{}일 (글 {}건 중 신규 {}, 사진 {}장 중 신규 {}, 영상 성공 {}/유실 {}, 실패 {})",
                    status.processedDays, status.totalDays, status.posts, status.newPosts,
                    status.images, status.newImages, status.videosDownloaded, status.videosLost, status.failed,
                )
            }
        }
        log.info(
            "키즈노트 임포트 완료: 글 {}건(신규 {}), 사진 {}장(신규 {}), 영상 성공 {}/유실 {}, 실패 {}",
            status.posts, status.newPosts, status.images, status.newImages,
            status.videosDownloaded, status.videosLost, status.failed,
        )
    }

    private fun upsertChild(folderName: String, childName: String): Long = transaction {
        KidsnoteChildren.selectAll().where { KidsnoteChildren.folderName eq folderName }.firstOrNull()
            ?.get(KidsnoteChildren.id)
            ?: KidsnoteChildren.insert {
                it[KidsnoteChildren.folderName] = folderName
                it[KidsnoteChildren.childName] = childName
                it[createdAt] = LocalDateTime.now().format(AssetIngestService.ISO)
            }[KidsnoteChildren.id]
    }

    private fun processPost(post: KidsnotePostJson, dayDir: Path, childId: Long) {
        status = status.copy(posts = status.posts + 1, images = status.images + post.images.size)

        val existing = transaction {
            KidsnotePosts.selectAll().where { KidsnotePosts.postId eq post.id }.firstOrNull()
        }
        if (existing != null) {
            // 이미 임포트된 글 — 유실(LOST) 영상만 재시도
            if (existing[KidsnotePosts.videoStatus] == "LOST" && post.video != null) {
                val videoAssetId = downloadAndIngestVideo(post)
                if (videoAssetId != null) {
                    transaction {
                        KidsnotePosts.update({ KidsnotePosts.postId eq post.id }) {
                            it[videoStatus] = "DOWNLOADED"
                            it[KidsnotePosts.videoAssetId] = videoAssetId
                        }
                    }
                    status = status.copy(videosDownloaded = status.videosDownloaded + 1)
                }
            }
            return
        }

        val resolved = resolveTakenAt(post)

        // 사진 인제스트 (복사 — E:의 원본은 보존). 파일이 없으면 그 사진만 건너뛴다.
        data class Link(val filename: String, val assetId: Long, val seq: Int)
        val links = mutableListOf<Link>()
        for ((seq, filename) in post.images.withIndex()) {
            val file = dayDir.resolve(filename)
            if (!Files.isRegularFile(file)) {
                log.warn("사진 없음: {}", file)
                status = status.copy(failed = status.failed + 1, lastError = "사진 없음: $filename")
                continue
            }
            try {
                val result = ingestService.ingest(
                    source = file, originalFilename = filename,
                    expectedHash = null, fileMtime = null,
                    deviceId = KIDSNOTE_DEVICE_ID, deviceName = "키즈노트",
                    takenAtOverride = resolved, sourceTag = SOURCE_TAG, skipMlJobs = true,
                )
                links += Link(filename, result.asset.id, seq)
                if (result.created) status = status.copy(newImages = status.newImages + 1)
            } catch (e: Exception) {
                log.warn("사진 인제스트 실패 {}: {}", file, e.message)
                status = status.copy(failed = status.failed + 1, lastError = "$filename: ${e.message}")
            }
        }

        // 영상: 다운로드 시도 → 성공 시 VIDEO 자산, 실패 시 LOST (글 자체는 계속 저장)
        var videoStatus: String? = null
        var videoAssetId: Long? = null
        if (post.video != null) {
            videoAssetId = downloadAndIngestVideo(post)
            videoStatus = if (videoAssetId != null) "DOWNLOADED" else "LOST"
            status = if (videoAssetId != null) status.copy(videosDownloaded = status.videosDownloaded + 1)
            else status.copy(videosLost = status.videosLost + 1)
        }

        // 글 행을 마지막에 기록 — 중간 크래시 시 다음 실행이 글을 처음부터 다시 처리한다
        // (이미 인제스트된 사진은 해시 중복제거로 재사용되므로 안전)
        val nowIso = LocalDateTime.now().format(AssetIngestService.ISO)
        transaction {
            KidsnotePosts.insert {
                it[postId] = post.id
                it[KidsnotePosts.childId] = childId
                it[dateWritten] = post.dateWritten
                it[yearMonth] = post.dateWritten.take(7)
                it[content] = post.content
                it[authorName] = post.authorName
                it[createdAt] = post.createdAt
                it[KidsnotePosts.videoStatus] = videoStatus
                it[KidsnotePosts.videoAssetId] = videoAssetId
                it[videoOriginalName] = post.video?.originalFileName
                it[videoHighUrl] = post.video?.highUrl
                it[videoLowUrl] = post.video?.lowUrl
                it[importedAt] = nowIso
            }
            for (link in links) {
                KidsnotePostImages.insertIgnore {
                    it[postId] = post.id
                    it[assetId] = link.assetId
                    it[filename] = link.filename
                    it[seq] = link.seq
                }
            }
        }
        status = status.copy(newPosts = status.newPosts + 1)
    }

    /**
     * assets.takenAt 결정: created_at(UTC)을 KST로 변환한 시각이 date_written과 같은 날이면 그 시각,
     * 아니면 date_written 정오. (EXIF는 게시일보다 1~2일 이른 경우가 28%라 쓰지 않는다)
     */
    private fun resolveTakenAt(post: KidsnotePostJson): TakenAtResolver.Resolved {
        val date = LocalDate.parse(post.dateWritten)
        val kstDateTime = runCatching {
            Instant.parse(post.createdAt).atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime()
        }.getOrNull()
        val takenAt = if (kstDateTime != null && kstDateTime.toLocalDate() == date) kstDateTime else date.atTime(12, 0)
        return TakenAtResolver.Resolved(takenAt, TAKEN_AT_SOURCE)
    }

    /** CDN에서 영상 다운로드 시도 (high → low 순). 성공 시 인제스트된 자산 id, 실패 시 null. */
    private fun downloadAndIngestVideo(post: KidsnotePostJson): Long? {
        val video = post.video ?: return null
        val urls = listOfNotNull(video.highUrl, video.lowUrl)
        if (urls.isEmpty()) return null

        val ext = video.originalFileName?.substringAfterLast('.', "")?.lowercase()
            ?.takeIf { it in AssetIngestService.VIDEO_EXTENSIONS } ?: "mp4"
        val filename = video.originalFileName?.takeIf { it.isNotBlank() } ?: "kidsnote_${post.id}.$ext"

        for (url in urls) {
            Files.createDirectories(props.uploadTmpDir)
            val temp = Files.createTempFile(props.uploadTmpDir, "kidsnote-video-", ".$ext")
            try {
                val request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(300))
                    .GET()
                    .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temp))
                if (response.statusCode() == 200 && Files.size(temp) > 0) {
                    val result = ingestService.ingest(
                        source = temp, originalFilename = filename,
                        expectedHash = null, fileMtime = null,
                        deviceId = KIDSNOTE_DEVICE_ID, deviceName = "키즈노트",
                        moveSource = true, // 임시 파일이라 rename으로 배치
                        takenAtOverride = resolveTakenAt(post), sourceTag = SOURCE_TAG, skipMlJobs = true,
                    )
                    log.info("영상 다운로드 성공: 글 #{} → asset #{}", post.id, result.asset.id)
                    return result.asset.id
                }
                log.debug("영상 다운로드 실패(HTTP {}): 글 #{} {}", response.statusCode(), post.id, url)
            } catch (e: Exception) {
                log.debug("영상 다운로드 실패: 글 #{} {} — {}", post.id, url, e.message)
            } finally {
                temp.deleteIfExists()
            }
        }
        return null
    }

    companion object {
        const val KIDSNOTE_DEVICE_ID = "kidsnote"
        const val SOURCE_TAG = "KIDSNOTE"
        const val TAKEN_AT_SOURCE = "KIDSNOTE"

        private val httpClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
}
