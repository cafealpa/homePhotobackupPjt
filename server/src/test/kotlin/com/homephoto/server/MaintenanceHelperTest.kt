package com.homephoto.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.service.ReleaseArtifacts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 현재 서버 대신 별도 JVM과 임시 텍스트 JAR만 사용한다. */
@EnabledOnOs(OS.WINDOWS)
class MaintenanceHelperTest {
    @TempDir lateinit var temp: Path
    private val mapper = ObjectMapper()
    private val java get() = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()

    private fun start(pid: Long, digest: String, executable: String = java, runtime: Path? = null): Process {
        val script = temp.resolve("maintenance.ps1")
        javaClass.getResourceAsStream("/maintenance.ps1")!!.use { Files.copy(it, script) }
        val plan = temp.resolve("plan.json")
        mapper.writeValue(plan.toFile(), mapOf("pid" to pid, "java" to executable, "arguments" to "-version",
            "workDir" to temp.toString(), "target" to temp.resolve("current.jar").toString(),
            "source" to temp.resolve("new.jar").toString(), "sha256" to digest, "runtime" to runtime?.toString()))
        return ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-ExecutionPolicy", "Bypass", "-File", script.toString(), "-PlanPath", plan.toString())
            .redirectErrorStream(true).redirectOutput(temp.resolve("helper.log").toFile()).start()
    }
    private fun await(process: Process): Int {
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS), "helper timeout"); return process.exitValue() }
        finally { if (process.isAlive) process.destroyForcibly() }
    }
    private fun files(): String {
        Files.writeString(temp.resolve("current.jar"), "old jar")
        return ReleaseArtifacts.sha256(Files.writeString(temp.resolve("new.jar"), "new jar"))
    }
    @Test fun `helper waits for old process then backs up and replaces only jar`() {
        val digest = files()
        val old = ProcessBuilder(java, "-cp", Path.of(ProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).toString(),
            ProcessFixture::class.java.name, "hang").redirectOutput(temp.resolve("old.log").toFile()).start()
        val helper = start(old.pid(), digest)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(temp.resolve("ready")) && helper.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(Files.exists(temp.resolve("ready")), Files.readString(temp.resolve("helper.log")) +
                if (Files.exists(temp.resolve("result.json"))) Files.readString(temp.resolve("result.json")) else "no result")
            assertEquals("old jar", Files.readString(temp.resolve("current.jar")))
            old.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            val exit = await(helper)
            assertEquals(0, exit, Files.readString(temp.resolve("helper.log")) + Files.readString(temp.resolve("result.json")))
            assertEquals("new jar", Files.readString(temp.resolve("current.jar")))
            assertEquals("old jar", Files.readString(temp.resolve("previous.jar")))
            val result = mapper.readTree(temp.resolve("result.json").toFile())
            assertEquals("STARTED", result["phase"].asText())
            ProcessHandle.of(result["pid"].asLong()).ifPresent { it.onExit().get(10, TimeUnit.SECONDS) }
        } finally { if (old.isAlive) old.destroyForcibly(); if (helper.isAlive) helper.destroyForcibly() }
    }
    @Test fun `checksum failure never signals ready or changes current jar`() {
        files()
        assertEquals(1, await(start(2147483000, "0".repeat(64))))
        assertFalse(Files.exists(temp.resolve("ready")))
        assertEquals("old jar", Files.readString(temp.resolve("current.jar")))
    }
    @Test fun `runtime is installed before switch and old runtime remains for rollback`() {
        val digest = files()
        val folder = Files.createDirectory(temp.resolve("download"))
        val source = Files.writeString(folder.resolve("runtime"), "native runtime")
        val runtime = Files.move(source, folder.resolve("homephoto-face-runtime-${ReleaseArtifacts.sha256(source)}.jar"))
        val old = Files.writeString(temp.resolve("old-runtime.jar"), "old runtime")
        assertEquals(0, await(start(2147483000, digest, runtime = runtime)))
        assertEquals("native runtime", Files.readString(temp.resolve(runtime.fileName)))
        assertEquals("old runtime", Files.readString(old))
        assertEquals("new jar", Files.readString(temp.resolve("current.jar")))
        val childPid = mapper.readTree(temp.resolve("result.json").toFile())["pid"].asLong()
        ProcessHandle.of(childPid).ifPresent { it.onExit().get(10, TimeUnit.SECONDS) }
    }
    @Test fun `invalid runtime does not signal ready or replace server`() {
        val digest = files()
        val runtime = Files.writeString(temp.resolve("homephoto-face-runtime-${"0".repeat(64)}.jar"), "corrupt")
        assertEquals(1, await(start(2147483000, digest, runtime = runtime)))
        assertFalse(Files.exists(temp.resolve("ready")))
        assertEquals("old jar", Files.readString(temp.resolve("current.jar")))
    }
    @Test fun `launch failure restores previous jar before new Java starts`() {
        val digest = files()
        val invalid = Files.writeString(temp.resolve("invalid.exe"), "not executable")
        assertEquals(1, await(start(2147483000, digest, invalid.toString())))
        assertTrue(Files.exists(temp.resolve("ready")))
        assertTrue(Files.exists(temp.resolve("previous.jar")))
        assertEquals("old jar", Files.readString(temp.resolve("current.jar")))
        assertEquals("FAILED", mapper.readTree(temp.resolve("result.json").toFile())["phase"].asText())
    }
}
