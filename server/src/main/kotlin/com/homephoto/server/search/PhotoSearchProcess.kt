package com.homephoto.server.search

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.AppProperties
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.web.context.WebServerInitializedEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 설정 화면에서 고정된 로컬 Python 진입점만 실행한다. 요청으로 명령/경로를 받지 않는다. */
@Service
class PhotoSearchProcess(
    private val props: PhotoSearchProperties,
    private val app: AppProperties,
    private val mapper: ObjectMapper,
) {
    data class Status(val state: String, val message: String, val canStart: Boolean = false,
                      val canStop: Boolean = false, val indexedPhotos: Long? = null, val indexedFaces: Long? = null)
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
    private var process: Process? = null
    private var serverPort = 8080
    private var launchFailed = false
    internal var launch: (ProcessBuilder) -> Process = { it.start() }

    @EventListener fun serverStarted(event: WebServerInitializedEvent) { serverPort = event.webServer.port }

    private fun worker(): Path = (if (props.workerDir.isNotBlank()) Path.of(props.workerDir)
        else if (Files.isDirectory(Path.of("ml-worker"))) Path.of("ml-worker") else Path.of("../ml-worker"))
        .toAbsolutePath().normalize()
    private fun python() = worker().resolve(if (System.getProperty("os.name").startsWith("Windows"))
        ".venv-search/Scripts/python.exe" else ".venv-search/bin/python")
    private fun installed() = listOf(python(), worker().resolve("search_service.py"),
        worker().resolve("face_search.py"), worker().resolve("search-model/homephoto-model.json")).all(Files::isRegularFile)
    private fun pidFile() = worker().resolve("search-data/service.pid")
    private fun externalPidAlive(): Boolean = runCatching {
        val pid = Files.readString(pidFile()).trim().toLong()
        pid != process?.pid() && ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }.getOrDefault(false)

    @Synchronized fun status(): Status {
        if (!props.enabled) return Status("disabled", "검색 설정이 꺼져 있어요. 최초 설치 후 서버를 재시작해 주세요.")
        props.validate()
        val alive = process?.isAlive == true
        val health = runCatching {
            val request = HttpRequest.newBuilder(URI(props.baseUrl + "/health")).timeout(Duration.ofSeconds(1))
                .header("Authorization", "Bearer ${props.token}").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) mapper.readTree(response.body()).takeIf { it.path("status").asText() == "ready" } else null
        }.getOrNull()
        if (health != null) return Status("running",
            if (alive) "검색 서비스가 실행 중이에요. 새 사진과 얼굴을 자동으로 인덱싱해요."
            else "외부에서 시작한 검색 서비스가 실행 중이에요. 중지는 기존 실행 스크립트에서 해주세요.",
            canStop = alive, indexedPhotos = health.get("indexed_photos")?.asLong(), indexedFaces = health.get("indexed_faces")?.asLong())
        if (alive) return Status("starting", "모델을 준비하고 있어요. 오래 지속되면 검색 서비스 로그를 확인해 주세요.", canStop = true)
        if (externalPidAlive()) return Status("external", "기존 검색 프로세스가 실행 중이에요. 준비 상태는 검색 서비스 로그에서 확인해 주세요.")
        if (!installed()) return Status("not_installed", "검색 서비스 설치가 필요해요. 운영 PC에서 install-search.ps1을 먼저 실행해 주세요.")
        if (!portFree()) return Status("unavailable", "검색 포트가 사용 중이거나 인증이 맞지 않아요. 기존 검색 서비스와 토큰 설정을 확인해 주세요.")
        if (process != null || launchFailed) return Status("failed", "검색 프로세스가 종료됐어요. search-data/service.err.log를 확인한 뒤 다시 시작해 주세요.", canStart = true)
        return Status("stopped", "검색 서비스가 중지돼 있어요. 시작하면 기존 인덱스에 이어서 처리해요.", canStart = true)
    }

    private fun portFree(): Boolean = runCatching {
        ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", URI(props.baseUrl).port)) }
        true
    }.getOrDefault(false)

    @Synchronized fun start(): Status {
        val current = status()
        if (current.state in listOf("running", "starting", "external")) return current
        if (!current.canStart) throw ResponseStatusException(HttpStatus.CONFLICT, current.message)
        val state = worker().resolve("search-data")
        Files.createDirectories(state)
        val builder = ProcessBuilder(python().toString(), worker().resolve("search_service.py").toString())
            .directory(worker().toFile())
            .redirectOutput(ProcessBuilder.Redirect.appendTo(state.resolve("service.out.log").toFile()))
            .redirectError(ProcessBuilder.Redirect.appendTo(state.resolve("service.err.log").toFile()))
        builder.environment().putAll(mapOf(
            "HOMEPHOTO_API_KEY" to app.apiKey, "HOMEPHOTO_SEARCH_TOKEN" to props.token,
            "HOMEPHOTO_SERVER" to "http://127.0.0.1:$serverPort",
            "HOMEPHOTO_SEARCH_PORT" to URI(props.baseUrl).port.toString(),
            "HOMEPHOTO_SEARCH_MODEL" to worker().resolve("search-model").toString(),
            "HOMEPHOTO_SEARCH_DATA" to state.toString(), "PYTHONUNBUFFERED" to "1"))
        try {
            process = launch(builder)
            Files.writeString(pidFile(), process!!.pid().toString())
            launchFailed = false
            log.info("사진 검색 서비스 시작: PID {}", process!!.pid())
        } catch (e: Exception) {
            process?.destroyForcibly()
            process = null
            launchFailed = true
            log.warn("사진 검색 서비스 시작 실패: {}", e.javaClass.simpleName)
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "검색 서비스를 시작하지 못했어요. Python 설치와 폴더 권한을 확인해 주세요.")
        }
        return Status("starting", "검색 서비스를 시작했어요. 모델 준비가 끝나면 자동으로 인덱싱해요.", canStop = true)
    }

    @Synchronized fun stop(): Status {
        val owned = process
        if (owned == null) {
            val current = status()
            if (current.state in listOf("running", "external", "unavailable"))
                throw ResponseStatusException(HttpStatus.CONFLICT, "이 서버에서 시작한 프로세스만 중지할 수 있어요.")
            return current
        }
        owned.destroy()
        if (!owned.waitFor(5, TimeUnit.SECONDS)) {
            owned.destroyForcibly()
            if (!owned.waitFor(5, TimeUnit.SECONDS)) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "검색 서비스 종료를 기다리고 있어요.")
        }
        if (runCatching { Files.readString(pidFile()).trim() == owned.pid().toString() }.getOrDefault(false)) Files.deleteIfExists(pidFile())
        process = null
        launchFailed = false
        log.info("사진 검색 서비스 중지: PID {}", owned.pid())
        return status()
    }

    @PreDestroy @Synchronized fun shutdown() { if (process != null) stop() }
}
