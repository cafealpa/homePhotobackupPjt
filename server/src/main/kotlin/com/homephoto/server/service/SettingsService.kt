package com.homephoto.server.service

import com.homephoto.server.config.AppProperties
import org.springframework.stereotype.Service
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
) {

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
    )

    fun save(request: Settings): SaveResult {
        validate(request)

        val restartRequired = buildList {
            val requested = Path.of(request.storageRoot).toAbsolutePath().normalize()
            if (requested != props.storageRoot.toAbsolutePath().normalize()) add("storageRoot")
            if (request.dbPath.trim() != props.dbPath) add("dbPath")
        }

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

        writeConfigFile(request)
        log.info(
            "설정 저장: {} (재시작 필요: {})",
            CONFIG_FILE.toAbsolutePath(),
            restartRequired.ifEmpty { listOf("없음") }.joinToString(),
        )
        return SaveResult(restartRequired, CONFIG_FILE.toAbsolutePath().toString())
    }

    private fun validate(s: Settings) {
        require(s.storageRoot.isNotBlank()) { "저장소 경로를 입력하세요" }
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
        require(s.apiKey.length >= 4) { "API 키는 4자 이상이어야 합니다" }
        require(s.ffmpegPath.isNotBlank()) { "ffmpeg 경로를 입력하세요" }
        require(s.trashRetentionDays in 1..3650) { "휴지통 보관일은 1~3650 사이여야 합니다" }
        require(s.captionBaseUrl.startsWith("http://") || s.captionBaseUrl.startsWith("https://")) {
            "장면 분석 서버 주소는 http:// 또는 https://로 시작해야 합니다"
        }
        require(s.captionModel.isNotBlank()) { "장면 분석 모델명을 입력하세요" }
        require(s.captionTimeoutSeconds in 10..3600) { "장면 분석 타임아웃은 10~3600초 사이여야 합니다" }
    }

    /**
     * 서버 재시작. 현재 프로세스의 종료를 기다렸다가 다시 띄우는 PowerShell 감시
     * 프로세스를 띄워 두고 스스로 종료한다 (포트 해제 후 재기동이라 충돌 없음).
     *
     * jar 실행이면 build/libs가 아니라 **run/homephoto-server.jar 사본**으로 재기동한다.
     * 서버가 물고 있는 jar를 gradle 재빌드가 덮어쓰면 실행 중인 JVM의 클래스 로딩이
     * 깨지기 때문(아직 안 불린 엔드포인트만 멈추는 반죽음 상태 — 실제 발생 사례).
     * 재기동 직전 최신 build/libs jar를 사본으로 복사하므로 재시작 = 새 빌드 배포를 겸한다.
     * IDE(-cp) 실행이면 같은 클래스패스로 재기동. 재기동 프로세스는 콘솔 없는 백그라운드로
     * 돌며 로그는 기존처럼 logs/homephoto.log에 남는다.
     */
    fun scheduleRestart() {
        val pid = ProcessHandle.current().pid()
        val binDir = Path.of(System.getProperty("java.home"), "bin")
        val javaExe = listOf("javaw.exe", "java.exe", "java").map(binDir::resolve).first { Files.exists(it) }
        val launchCmd = System.getProperty("sun.java.command")
            ?: throw IllegalStateException("실행 명령을 알 수 없어 재시작할 수 없습니다")
        val mainToken = launchCmd.substringBefore(' ') // "x.jar" 또는 메인 클래스명
        val workDir = System.getProperty("user.dir")

        fun pq(v: String) = "'${v.replace("'", "''")}'" // PowerShell 단일 인용 이스케이프
        val dollar = '$'
        val relaunch = if (mainToken.endsWith(".jar")) {
            // 최신 빌드 산출물 → run/ 사본 복사(구 프로세스 종료 후라 잠금 없음) → 사본 실행
            "New-Item -ItemType Directory -Force 'run' | Out-Null; " +
                "${dollar}src = Get-ChildItem 'build/libs' -Filter '*.jar' -ErrorAction SilentlyContinue | " +
                "Where-Object { ${dollar}_.Name -notlike '*-plain*' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1; " +
                "if (${dollar}src) { Copy-Item ${dollar}src.FullName 'run/homephoto-server.jar' -Force } " +
                "elseif (-not (Test-Path 'run/homephoto-server.jar')) { Copy-Item ${pq(mainToken)} 'run/homephoto-server.jar' -Force }; " +
                "Start-Process -FilePath ${pq(javaExe.toString())} -ArgumentList @('-jar','run/homephoto-server.jar') -WorkingDirectory ${pq(workDir)}"
        } else {
            val argList = listOf("-cp", System.getProperty("java.class.path"), mainToken).joinToString(",") { pq(it) }
            "Start-Process -FilePath ${pq(javaExe.toString())} -ArgumentList @($argList) -WorkingDirectory ${pq(workDir)}"
        }
        val psCommand = "Wait-Process -Id $pid -ErrorAction SilentlyContinue; Start-Sleep -Seconds 1; $relaunch"

        log.info("서버 재시작 예약 — PID {} 종료 후 재기동: {} {}", pid, javaExe.fileName, mainToken)
        Thread {
            try {
                Thread.sleep(700) // 응답이 클라이언트에 전달될 시간
                ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psCommand).start()
            } catch (e: Exception) {
                log.error("재시작 감시 프로세스 실행 실패 — 서버를 종료하지 않습니다", e)
                return@Thread
            }
            kotlin.system.exitProcess(0)
        }.apply { isDaemon = true; name = "restart-scheduler" }.start()
    }

    private fun writeConfigFile(s: Settings) {
        // YAML 단일 인용: ' → '' 만 이스케이프하면 백슬래시 등이 그대로 보존된다
        fun q(v: String) = "'${v.replace("'", "''")}'"
        val yaml = """
            |# 웹 설정 페이지에서 저장된 값 — classpath의 application.yml을 덮어쓴다 (Spring 외부 설정).
            |# 직접 편집해도 되며, 서버 재시작 시 반영된다. API 키를 잊었다면 여기서 확인.
            |homephoto:
            |  storage-root: ${q(s.storageRoot)}
            |  db-path: ${q(s.dbPath.trim())}
            |  thumbs-path: ${q(s.thumbsPath.trim())}
            |  api-key: ${q(s.apiKey)}
            |  ffmpeg-path: ${q(s.ffmpegPath)}
            |  trash-retention-days: ${s.trashRetentionDays}
            |  caption:
            |    enabled: ${s.captionEnabled}
            |    base-url: ${q(s.captionBaseUrl)}
            |    model: ${q(s.captionModel)}
            |    timeout-seconds: ${s.captionTimeoutSeconds}
            |""".trimMargin()
        Files.createDirectories(CONFIG_FILE.toAbsolutePath().parent)
        Files.writeString(CONFIG_FILE, yaml)
    }

    companion object {
        /** 실행 디렉토리 기준 — IntelliJ·jar 실행 모두 server/ 에서 돌므로 server/config/application.yml */
        val CONFIG_FILE: Path = Path.of("config", "application.yml")
    }
}
