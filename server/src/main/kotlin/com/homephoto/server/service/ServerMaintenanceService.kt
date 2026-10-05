package com.homephoto.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource
import kotlin.system.exitProcess

/** 실제 종료/교체는 웹 요청 스레드 밖에서 수행한다. 작업 대기 시간 초과 시 종료를 취소한다. */
@Service
class ServerMaintenanceService(
    private val activity: ServerActivity,
    private val updates: ReleaseUpdateService,
    private val context: ConfigurableApplicationContext,
    private val dataSource: DataSource,
    private val mapper: ObjectMapper,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)
    val instanceId: String = UUID.randomUUID().toString()
    @Volatile private var phase = "RUNNING"
    @Volatile private var message: String? = null
    data class Status(val instanceId: String, val phase: String, val active: Int, val message: String?,
                      val update: ReleaseUpdateService.State, val canRestart: Boolean, val canUpdate: Boolean, val restartReason: String?)
    data class Launch(val java: String, val jar: Path?, val arguments: List<String>, val workDir: Path)

    fun status(): Status {
        val launch = runCatching { launch() }
        val update = runCatching { launch(requireJar = true) }
        return Status(instanceId, phase, activity.count(), message, updates.status(), launch.isSuccess, update.isSuccess, update.exceptionOrNull()?.message)
    }

    internal fun launch(requireJar: Boolean = false): Launch {
        check(System.getProperty("os.name").startsWith("Windows")) { "자동 재시작·설치는 Windows JAR 실행에서 지원합니다" }
        val args = ProcessHandle.current().info().arguments().orElse(emptyArray()).toList()
        val index = args.indexOf("-jar")
        check(args.isNotEmpty()) { "실행 명령을 확인할 수 없습니다" }
        val cwd = Path.of("").toAbsolutePath().normalize()
        val jar = if (index >= 0 && index + 1 < args.size) cwd.resolve(args[index + 1]).normalize() else null
        if (requireJar) {
            check(jar != null) { "IDE/Gradle 실행에서는 자동 설치할 수 없습니다. 배포 JAR로 실행해 주세요" }
            check(Files.isRegularFile(jar)) { "실행 중인 JAR 경로를 확인할 수 없습니다" }
            check(!jar.startsWith(cwd.resolve("build"))) { "실행 JAR를 run 폴더에 복사해 실행한 뒤 업데이트해 주세요" }
        }
        return Launch(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), jar, args, cwd)
    }

    @Synchronized fun request(action: String): Status {
        require(action in setOf("shutdown", "restart", "update"))
        val launch = if (action == "shutdown") null else launch(requireJar = action == "update")
        val update = if (action == "update") updates.ready() else null
        check(activity.begin()) { "이미 종료 또는 업데이트를 준비 중입니다" }
        phase = "DRAINING"
        message = "새 요청을 막고 처리 중인 작업이 끝나기를 기다립니다 (최대 120초)."
        Thread({
            var helper: Process? = null
            try {
                check(activity.awaitIdle(Duration.ofSeconds(120))) { "작업 대기 시간이 초과되어 종료를 취소했습니다. 진행 중인 작업을 확인한 뒤 다시 시도해 주세요." }
                if (launch != null) {
                    val directory = launch.workDir.resolve("updates").resolve("apply-${UUID.randomUUID()}")
                    Files.createDirectories(directory)
                    if (update != null) {
                        ReleaseArtifacts.verify(update.jar, update.sha256)
                        ReleaseArtifacts.validateJar(update.jar, update.tag)
                        snapshotDatabase(directory.resolve("photos.db"))
                        backupConfig(launch.workDir.resolve("config"), directory.resolve("config"))
                    }
                    helper = startHelper(directory, launch, update)
                }
                phase = "STOPPING"
                message = if (action == "shutdown") "서버를 종료합니다. 다시 사용하려면 서버 PC에서 시작해 주세요." else "프로세스 종료 후 재시작합니다."
                log.info("서버 {} 시작 — 진행 중인 로컬 작업 없음", action)
                context.close()
                exitProcess(0)
            } catch (e: Exception) {
                helper?.destroyForcibly()
                log.error("서버 유지보수 작업 취소", e)
                message = e.message ?: "종료 준비 실패"
                phase = "FAILED"
                activity.resume()
            }
        }, "server-maintenance").apply { isDaemon = false }.start()
        return status()
    }

    private fun snapshotDatabase(target: Path) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.execute("VACUUM INTO '${target.toString().replace("'", "''")}'") }
        }
    }
    private fun backupConfig(source: Path, target: Path) {
        if (!Files.isDirectory(source)) return
        Files.walk(source).use { paths -> paths.forEach { path ->
            val output = target.resolve(source.relativize(path))
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(output)
            else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.copy(path, output)
        } }
    }
    private fun startHelper(directory: Path, launch: Launch, update: ReleaseUpdateService.Prepared?): Process {
        val script = directory.resolve("maintenance.ps1")
        javaClass.getResourceAsStream("/maintenance.ps1")!!.use { Files.copy(it, script) }
        val plan = directory.resolve("plan.json")
        mapper.writeValue(plan.toFile(), mapOf(
            "pid" to ProcessHandle.current().pid(), "java" to launch.java, "arguments" to launch.arguments.joinToString(" ", transform = ::quoteWindowsArgument),
            "workDir" to launch.workDir.toString(), "target" to launch.jar?.toString(),
            "source" to update?.jar?.toString(), "sha256" to update?.sha256,
        ))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-PlanPath", plan.toString())
            .redirectErrorStream(true).redirectOutput(directory.resolve("helper.log").toFile()).start()
        val ready = directory.resolve("ready")
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        try {
            while (!Files.exists(ready)) {
                check(process.isAlive && System.nanoTime() < deadline) { "재시작 도우미가 준비되지 않았습니다. ${directory.resolve("helper.log")}" }
                Thread.sleep(100)
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
        }
        return process
    }

    companion object {
        /** Windows CommandLineToArgvW 규칙: 공백, 따옴표, 끝의 역슬래시를 보존한다. */
        internal fun quoteWindowsArgument(value: String): String = "\"" + value
            .replace(Regex("(\\\\*)\"")) { "\\".repeat(it.groupValues[1].length * 2 + 1) + "\"" }
            .replace(Regex("\\\\+$")) { it.value + it.value } + "\""
    }
}
