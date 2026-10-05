package com.homephoto.server.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service
class ReleaseUpdateService(private val mapper: ObjectMapper, builds: ObjectProvider<BuildProperties>,
                           private val activity: ServerActivity) {
    final val currentVersion: String? = builds.ifAvailable?.version
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val busy = AtomicBoolean(false)
    @Volatile private var state = State("IDLE")
    @Volatile private var prepared: Prepared? = null
    data class Release(val tag: String, val prerelease: Boolean, val url: String, val notes: String,
                       val newer: Boolean, val assetName: String?, val size: Long, val verifiable: Boolean,
                       val publishedAt: String?)
    data class State(val phase: String, val tag: String? = null, val message: String? = null)
    data class Prepared(val jar: Path, val sha256: String, val tag: String)
    fun status() = state
    fun ready(): Prepared = prepared?.takeIf { state.phase == "READY" } ?: error("먼저 업데이트를 다운로드하고 검증해 주세요")
    private fun request(uri: URI) = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
        .header("Accept", "application/vnd.github+json").header("User-Agent", "HomePhoto-Updater")
        .header("X-GitHub-Api-Version", "2022-11-28").GET().build()
    private fun releases(): List<JsonNode> {
        val result = mutableListOf<JsonNode>()
        var page = 1
        do {
            val response = client.send(request(URI("https://api.github.com/repos/$REPOSITORY/releases?per_page=100&page=$page")), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "GitHub 릴리즈 조회 실패 (HTTP ${response.statusCode()}). 네트워크·API 호출 한도를 확인하세요." }
            result.addAll(mapper.readTree(response.body()).toList())
            page++
        } while (response.headers().firstValue("Link").orElse("").contains("rel=\"next\""))
        return result.filter { !it.path("draft").asBoolean() }
    }
    private fun asset(release: JsonNode): JsonNode? {
        val version = release.path("tag_name").asText().removePrefix("v")
        val assets = release.path("assets").toList().filter { it.path("state").asText() == "uploaded" }
        return assets.firstOrNull { it.path("name").asText() == "homephoto-server-$version.zip" }
            ?: assets.firstOrNull { it.path("name").asText() == "homephoto-server.jar" }
    }
    fun check(includePrerelease: Boolean): List<Release> = describe(releases(), includePrerelease)

    internal fun describe(releases: List<JsonNode>, includePrerelease: Boolean): List<Release> = releases
        .filter { !it.path("draft").asBoolean() && (includePrerelease || !it.path("prerelease").asBoolean()) }
        .map { release ->
            val tag = release.path("tag_name").asText()
            val newer = runCatching { currentVersion != null && ReleaseArtifacts.compare(tag, currentVersion) > 0 }.getOrDefault(false)
            val asset = asset(release)
            Release(tag, release.path("prerelease").asBoolean(), "https://github.com/$REPOSITORY/releases/tag/$tag",
                release.path("body").asText().take(20_000), newer, asset?.path("name")?.asText(), asset?.path("size")?.asLong() ?: 0,
                asset?.path("digest")?.asText()?.matches(Regex("sha256:[a-fA-F0-9]{64}")) == true,
                release.path("published_at").takeUnless { it.isMissingNode || it.isNull }?.asText())
        }.sortedByDescending { it.publishedAt }

    fun prepare(tag: String): State {
        check(!activity.draining && busy.compareAndSet(false, true)) { "다른 작업이 진행 중입니다" }
        if (!activity.enter()) { busy.set(false); error("서버 종료 준비 중입니다") }
        prepared = null
        state = State("DOWNLOADING", tag, "릴리즈 파일 다운로드·검증 중")
        Thread({
            try {
                check(currentVersion != null && ReleaseArtifacts.compare(tag, currentVersion) > 0) { "현재보다 새 버전만 적용할 수 있습니다" }
                val release = releases().singleOrNull { it.path("tag_name").asText() == tag } ?: error("릴리즈를 찾을 수 없습니다")
                val asset = asset(release) ?: error("지원하는 배포 파일이 없습니다")
                val digest = asset.path("digest").asText().removePrefix("sha256:")
                require(digest.matches(Regex("[a-fA-F0-9]{64}"))) { "GitHub SHA-256 정보가 없어 자동 설치할 수 없습니다" }
                val size = asset.path("size").asLong()
                require(size in 1..1_073_741_824) { "배포 파일 크기를 확인하세요 (최대 1GB)" }
                val url = URI(asset.path("browser_download_url").asText())
                require(url.scheme == "https" && url.host == "github.com" && url.path.startsWith("/$REPOSITORY/releases/download/")) { "허용되지 않은 다운로드 주소" }
                val root = Path.of("updates").toAbsolutePath()
                Files.createDirectories(root)
                val dir = Files.createTempDirectory(root, "release-")
                val archive = dir.resolve("download")
                val download = client.sendAsync(HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(15)).GET().build(), HttpResponse.BodyHandlers.ofFile(archive))
                val response = try { download.get(15, TimeUnit.MINUTES) } catch (e: Exception) { download.cancel(true); throw e }
                check(response.statusCode() == 200 && Files.size(archive) == size) { "릴리즈 파일 다운로드가 완료되지 않았습니다" }
                ReleaseArtifacts.verify(archive, digest)
                val jar = dir.resolve("homephoto-server.jar")
                if (asset.path("name").asText().endsWith(".zip")) ReleaseArtifacts.extract(archive, jar) else Files.copy(archive, jar)
                if (asset.path("name").asText().endsWith(".zip")) ReleaseArtifacts.extractRuntime(archive, jar)
                ReleaseArtifacts.validateJar(jar, tag)
                prepared = Prepared(jar, ReleaseArtifacts.sha256(jar), tag)
                state = State("READY", tag, "검증 완료. 업데이트 적용 시 진행 중인 작업을 마친 후 재시작합니다.")
            } catch (e: Exception) { state = State("FAILED", tag, e.message ?: "업데이트 준비 실패") }
            finally { busy.set(false); activity.leave() }
        }, "release-download").apply { isDaemon = true }.start()
        return state
    }
    companion object { const val REPOSITORY = "cafealpa/homePhotobackupPjt" }
}
