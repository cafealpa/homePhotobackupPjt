package com.homephoto.server.service

import com.homephoto.server.config.AppProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

/**
 * 웹 설정 페이지의 저장 처리.
 * - 저장 위치: ./config/application.yml — Spring Boot가 시작 시 자동으로 읽어
 *   classpath의 application.yml을 덮어쓰는 표준 외부 설정 경로. 즉 재시작해도 유지된다.
 * - storage-root 외의 값은 AppProperties의 var 필드를 바꿔 즉시 적용한다.
 *   storage-root는 DB 연결(datasource URL)이 시작 시 고정되므로 재시작 후 적용.
 */
@Service
class SettingsService(
    private val props: AppProperties,
    private val thumbnailService: ThumbnailService,
    @Value("\${homephoto.settings-config-file:config/application.yml}") configFile: String = CONFIG_FILE.toString(),
) {
    private val configPath = Path.of(configFile)
    @Volatile private var pendingOriginalStorageRoot = props.originalStorage.root?.toString()?.replace('\\', '/') ?: ""

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /** 웹 폼과 주고받는 평면 구조. */
    data class Settings(
        val storageRoot: String,
        /** DB를 둘 폴더. 빈 문자열 = 저장소 안의 db 폴더 */
        val dbPath: String = "",
        /** 썸네일을 둘 폴더. 빈 문자열 = 저장소 안의 thumbs 폴더. 바꾸면 즉시 적용 + 기존 파일 백그라운드 이동 */
        val thumbsPath: String = "",
        val apiKey: String,
        val ffmpegPath: String,
        val trashRetentionDays: Long,
        val captionEnabled: Boolean,
        val captionBaseUrl: String,
        val captionModel: String,
        val captionTimeoutSeconds: Long,
        /** null = 이전 클라이언트/생략 시 저장된 값 유지, 빈 문자열 = 기존 storageRoot 사용. */
        val originalStorageRoot: String? = null,
        /** null = 이전 클라이언트가 생략하면 게시 설정 보존. 비밀 값 대신 파일 경로만 주고받는다. */
        val googlePhotos: AppProperties.GooglePhotosProperties? = null,
        val face: AppProperties.FaceProperties? = null,
    )

    data class SaveResult(val restartRequired: List<String>, val configFile: String)

    fun current() = Settings(
        storageRoot = props.storageRoot.toString().replace('\\', '/'),
        dbPath = props.dbPath.replace('\\', '/'),
        thumbsPath = props.thumbsPath.replace('\\', '/'),
        apiKey = props.apiKey,
        ffmpegPath = props.ffmpegPath,
        trashRetentionDays = props.trashRetentionDays,
        captionEnabled = props.caption.enabled,
        captionBaseUrl = props.caption.baseUrl,
        captionModel = props.caption.model,
        captionTimeoutSeconds = props.caption.timeoutSeconds,
        originalStorageRoot = pendingOriginalStorageRoot,
        googlePhotos = props.googlePhotos,
        face = props.face,
    )

    @Synchronized
    fun save(request: Settings): SaveResult {
        validate(request)

        val restartRequired = buildList {
            val requested = Path.of(request.storageRoot).toAbsolutePath().normalize()
            if (requested != props.storageRoot.toAbsolutePath().normalize()) add("storageRoot")
            if (request.dbPath.trim() != props.dbPath) add("dbPath")
            if (request.originalStorageRoot != null) {
                val requestedOriginal = request.originalStorageRoot.trim().takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() }
                if (requestedOriginal != props.originalStorage.root?.toAbsolutePath()?.normalize()) add("originalStorageRoot")
            }
        }

        writeConfigFile(request)
        request.face?.let { props.face = it.copy(modelDir = it.modelDir.trim()) }
        request.originalStorageRoot?.let { pendingOriginalStorageRoot = it.trim().replace('\\', '/') }
        request.googlePhotos?.let { props.googlePhotos = it.copy(clientFile = it.clientFile.trim(), tokenFile = it.tokenFile.trim()) }

        // 즉시 적용 (storage-root 제외). API 키를 바꾸면 기존 쿠키·헤더가 무효가 되어 재로그인 필요.
        // dbPath는 즉시 적용하지 않는다 — 연결 URL은 시작 시 고정이라 재시작 전까지 예전 DB를 쓴다.
        // (설정 파일에는 기록되므로 재시작하면 반영된다)
        props.apiKey = request.apiKey
        // 썸네일 폴더는 즉시 적용 — 경로는 매번 계산되므로 바꾸는 순간부터 새 폴더를 쓰고,
        // 옛 폴더에 남은 파일은 ThumbnailService가 백그라운드로 옮긴다 (재시작 불필요)
        val oldThumbsDir = props.thumbsDir
        props.thumbsPath = request.thumbsPath.trim()
        if (props.thumbsDir.toAbsolutePath().normalize() != oldThumbsDir.toAbsolutePath().normalize()) {
            thumbnailService.relocate(oldThumbsDir)
        }
        props.ffmpegPath = request.ffmpegPath
        props.trashRetentionDays = request.trashRetentionDays
        props.caption = AppProperties.CaptionProperties(
            enabled = request.captionEnabled,
            baseUrl = request.captionBaseUrl,
            model = request.captionModel,
            timeoutSeconds = request.captionTimeoutSeconds,
        )

        log.info(
            "설정 저장: {} (재시작 필요: {})",
            configPath.toAbsolutePath(),
            restartRequired.ifEmpty { listOf("없음") }.joinToString(),
        )
        return SaveResult(restartRequired, configPath.toAbsolutePath().toString())
    }

    private fun validate(s: Settings) {
        s.face?.let { require(it.modelDir.isBlank() || Path.of(it.modelDir.trim()).isAbsolute) { "얼굴 모델 폴더는 전체 경로로 입력하세요" } }
        require(s.storageRoot.isNotBlank()) { "저장소 경로를 입력하세요" }
        if (!s.originalStorageRoot.isNullOrBlank()) {
            require(Path.of(s.originalStorageRoot.trim()).isAbsolute) { "원본 저장소는 전체 경로로 입력하세요 (예: D:/PhotoArchive 또는 UNC 공유 경로)" }
        }
        if (s.dbPath.isNotBlank()) {
            val dir = runCatching { Path.of(s.dbPath.trim()) }.getOrNull()
            require(dir != null) { "DB 파일 위치가 올바른 경로가 아닙니다" }
            require(dir.isAbsolute) { "DB 파일 위치는 전체 경로로 입력하세요 (예: D:/homePhotoDb)" }
        }
        if (s.thumbsPath.isNotBlank()) {
            val dir = runCatching { Path.of(s.thumbsPath.trim()) }.getOrNull()
            require(dir != null) { "썸네일 폴더가 올바른 경로가 아닙니다" }
            require(dir.isAbsolute) { "썸네일 폴더는 전체 경로로 입력하세요 (예: D:/homePhotoThumbs)" }
            // 지금 폴더와 포개지면 이동 중에 자기 자신 안으로 옮기는 꼴이 된다
            val current = props.thumbsDir.toAbsolutePath().normalize()
            val next = dir.toAbsolutePath().normalize()
            require(next == current || !(next.startsWith(current) || current.startsWith(next))) {
                "썸네일 폴더는 지금 폴더($current)의 안이나 상위가 될 수 없습니다"
            }
            val storage = props.storageRoot.toAbsolutePath().normalize()
            require(next != storage && next != props.originalsDir.toAbsolutePath().normalize()) {
                "썸네일 폴더는 저장소 루트나 originals 폴더와 달라야 합니다"
            }
        }
        val localRoot = Path.of(s.storageRoot).toAbsolutePath().normalize()
        val originalRoot = (s.originalStorageRoot ?: pendingOriginalStorageRoot).trim().takeIf { it.isNotEmpty() }
            ?.let { Path.of(it).toAbsolutePath().normalize() } ?: localRoot
        val originalDir = originalRoot.resolve("originals")
        val localDirs = listOf(
            if (s.dbPath.isBlank()) localRoot.resolve("db") else Path.of(s.dbPath.trim()).toAbsolutePath().normalize(),
            if (s.thumbsPath.isBlank()) localRoot.resolve("thumbs") else Path.of(s.thumbsPath.trim()).toAbsolutePath().normalize(),
            localRoot.resolve("tmp"),
            localRoot.resolve("incoming"),
        )
        require(localDirs.none { it.startsWith(originalDir) || originalDir.startsWith(it) }) {
            "원본 폴더는 DB·썸네일·임시·수신 대기 폴더와 겹칠 수 없습니다"
        }
        require(s.apiKey.length >= 4) { "API 키는 4자 이상이어야 합니다" }
        s.googlePhotos?.let { google ->
            require(google.maxAttempts in 1..10) { "Google Photos 재시도는 1~10회 사이여야 합니다." }
            for (file in listOf(google.clientFile, google.tokenFile).filter(String::isNotBlank)) {
                require(Path.of(file.trim()).isAbsolute) { "Google Photos 인증 파일은 전체 경로로 입력하세요." }
            }
            if (google.enabled) require(google.clientFile.isNotBlank() && google.tokenFile.isNotBlank()) { "게시를 사용하려면 Desktop OAuth JSON과 토큰 파일 경로가 필요합니다." }
        }
        require(s.ffmpegPath.isNotBlank()) { "ffmpeg 경로를 입력하세요" }
        require(s.trashRetentionDays in 1..3650) { "휴지통 보관일은 1~3650 사이여야 합니다" }
        require(s.captionBaseUrl.startsWith("http://") || s.captionBaseUrl.startsWith("https://")) {
            "장면 분석 서버 주소는 http:// 또는 https://로 시작해야 합니다"
        }
        require(s.captionModel.isNotBlank()) { "장면 분석 모델명을 입력하세요" }
        require(s.captionTimeoutSeconds in 10..3600) { "장면 분석 타임아웃은 10~3600초 사이여야 합니다" }
    }

    private fun writeConfigFile(s: Settings) {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val values = if (Files.exists(configPath)) yaml.load<MutableMap<String, Any?>>(Files.readString(configPath)) ?: linkedMapOf()
                     else linkedMapOf()
        @Suppress("UNCHECKED_CAST")
        val homephoto = values.getOrPut("homephoto") { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
        homephoto["storage-root"] = s.storageRoot
        val face = s.face ?: props.face
        homephoto["face"] = linkedMapOf("enabled" to face.enabled, "model-dir" to face.modelDir.trim())
        homephoto["db-path"] = s.dbPath.trim()
        homephoto["thumbs-path"] = s.thumbsPath.trim()
        homephoto["api-key"] = s.apiKey
        homephoto["ffmpeg-path"] = s.ffmpegPath
        homephoto["trash-retention-days"] = s.trashRetentionDays
        @Suppress("UNCHECKED_CAST")
        val original = homephoto.getOrPut("original-storage") { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
        if (s.originalStorageRoot != null) {
            if (s.originalStorageRoot.isBlank()) original.remove("root") else original["root"] = s.originalStorageRoot.trim()
        } else if (!original.containsKey("root") && pendingOriginalStorageRoot.isNotEmpty()) {
            original["root"] = pendingOriginalStorageRoot
        }
        if (original.isEmpty()) homephoto.remove("original-storage")
        @Suppress("UNCHECKED_CAST")
        val caption = homephoto.getOrPut("caption") { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
        caption["enabled"] = s.captionEnabled
        caption["base-url"] = s.captionBaseUrl
        caption["model"] = s.captionModel
        caption["timeout-seconds"] = s.captionTimeoutSeconds
        if (s.googlePhotos != null || !homephoto.containsKey("google-photos")) {
            val settings = s.googlePhotos ?: props.googlePhotos
            @Suppress("UNCHECKED_CAST")
            val google = homephoto.getOrPut("google-photos") { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
            google["enabled"] = settings.enabled
            google["client-file"] = settings.clientFile.trim()
            google["token-file"] = settings.tokenFile.trim()
            google["auto-publish-new"] = settings.autoPublishNew
            google["include-videos"] = settings.includeVideos
            google["max-attempts"] = settings.maxAttempts
        }
        AtomicFiles.write(configPath.toAbsolutePath()) { temp ->
            Files.writeString(temp, "# 웹 설정에서 변경한 값. 기타 설정은 보존합니다.\n" + yaml.dump(values))
        }
    }

    companion object {
        /** 실행 디렉토리 기준 — IntelliJ·jar 실행 모두 server/ 에서 돌므로 server/config/application.yml */
        val CONFIG_FILE: Path = Path.of("config", "application.yml")
    }
}
