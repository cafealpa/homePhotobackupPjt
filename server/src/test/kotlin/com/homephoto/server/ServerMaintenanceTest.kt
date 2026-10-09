package com.homephoto.server

import com.homephoto.server.config.ServerDrainFilter
import com.homephoto.server.service.ReleaseArtifacts
import com.homephoto.server.service.ServerActivity
import com.homephoto.server.service.ServerMaintenanceService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ServerMaintenanceTest {
    @TempDir lateinit var temp: Path

    @Test fun `draining refuses new work and waits for existing activity`() {
        val activity = ServerActivity()
        assertTrue(activity.enter())
        assertTrue(activity.begin())
        assertFalse(activity.begin())
        assertFalse(activity.enter())
        assertFalse(activity.awaitIdle(Duration.ofMillis(10)))
        val idle = CompletableFuture.supplyAsync { activity.awaitIdle(Duration.ofSeconds(2)) }
        activity.leave()
        assertTrue(idle.get(3, TimeUnit.SECONDS))
        activity.resume()
        assertTrue(activity.enter()); activity.leave()
    }

    @Test fun `drain filter blocks new uploads but allows maintenance polling`() {
        val activity = ServerActivity()
        val filter = ServerDrainFilter(activity)
        activity.begin()
        val upload = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("POST", "/api/v1/assets"), upload) { _, _ -> fail("must not receive upload") }
        assertEquals(503, upload.status)
        assertEquals("30", upload.getHeader("Retry-After"))
        var polled = false
        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/admin/maintenance/status"), MockHttpServletResponse()) { _, _ -> polled = true }
        assertTrue(polled)
        polled=false
        filter.doFilter(MockHttpServletRequest("GET", "/api/v1/admin/processes"), MockHttpServletResponse()) { _, _ -> polled=true }
        assertTrue(polled)
    }

    @Test fun `failed request releases activity count`() {
        val activity = ServerActivity()
        val filter = ServerDrainFilter(activity)
        assertFailsWith<IllegalStateException> {
            filter.doFilter(MockHttpServletRequest("POST", "/api/v1/assets"), MockHttpServletResponse()) { _, _ ->
                assertEquals(1, activity.count()); error("request failed")
            }
        }
        assertEquals(0, activity.count())
    }

    @Test fun `release order prevents downgrade and handles numeric prerelease versions`() {
        assertTrue(ReleaseArtifacts.compare("v0.1.6-rc3", "0.1.6-rc2") > 0)
        assertTrue(ReleaseArtifacts.compare("v0.1.6-rc10", "0.1.6-rc2") > 0)
        assertTrue(ReleaseArtifacts.compare("v0.1.6", "0.1.6-rc3") > 0)
        assertTrue(ReleaseArtifacts.compare("v0.1.5", "0.1.6-rc3") < 0)
        assertEquals(0, ReleaseArtifacts.compare("v1.2.3", "1.2.3"))
        assertFailsWith<IllegalStateException> { ReleaseArtifacts.compare("latest", "1.2.3") }
    }

    @Test fun `checksum mismatch refuses downloaded file`() {
        val file = Files.writeString(temp.resolve("download"), "downloaded bytes")
        ReleaseArtifacts.verify(file, ReleaseArtifacts.sha256(file))
        assertFailsWith<IllegalStateException> { ReleaseArtifacts.verify(file, "0".repeat(64)) }
    }

    private fun zip(name: String, entry: String): Path = temp.resolve(name).also { path ->
        ZipOutputStream(Files.newOutputStream(path)).use { it.putNextEntry(ZipEntry(entry)); it.write("jar".toByteArray()); it.closeEntry() }
    }
    @Test fun `zip extraction writes only fixed jar path and refuses traversal`() {
        val target = temp.resolve("staged.jar")
        ReleaseArtifacts.extract(zip("ok.zip", "homephoto-server-1.2.3/homephoto-server.jar"), target)
        assertEquals("jar", Files.readString(target))
        assertFailsWith<IllegalArgumentException> { ReleaseArtifacts.extract(zip("bad.zip", "../homephoto-server.jar"), target) }
        assertEquals("jar", Files.readString(target))
    }

    @Test fun `jar version and executable identity must match selected release`() {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Start-Class", "com.homephoto.server.HomePhotoServerApplicationKt")
        }
        val jar = temp.resolve("server.jar")
        JarOutputStream(Files.newOutputStream(jar), manifest).use {
            it.putNextEntry(JarEntry("BOOT-INF/classes/META-INF/build-info.properties"))
            it.write("build.version=1.2.3\n".toByteArray()); it.closeEntry()
        }
        ReleaseArtifacts.validateJar(jar, "v1.2.3")
        assertFailsWith<IllegalStateException> { ReleaseArtifacts.validateJar(jar, "v1.2.4") }
    }

    @Test fun `split release requires runtime matching manifest hash`() {
        val runtimeBytes = "runtime contents".toByteArray()
        val digest = ReleaseArtifacts.sha256(Files.write(temp.resolve("runtime-source"), runtimeBytes))
        val name = "homephoto-face-runtime-$digest.jar"
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Start-Class", "com.homephoto.server.HomePhotoServerApplicationKt")
            mainAttributes.putValue("Class-Path", name)
        }
        val jar = temp.resolve("homephoto-server.jar")
        JarOutputStream(Files.newOutputStream(jar), manifest).use {
            it.putNextEntry(JarEntry("BOOT-INF/classes/META-INF/build-info.properties"))
            it.write("build.version=1.2.3\n".toByteArray()); it.closeEntry()
        }
        assertFails { ReleaseArtifacts.validateJar(jar, "v1.2.3") }
        val zip = temp.resolve("split.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use {
            it.putNextEntry(ZipEntry("release/homephoto-server.jar")); it.write(Files.readAllBytes(jar)); it.closeEntry()
            it.putNextEntry(ZipEntry("release/$name")); it.write(runtimeBytes); it.closeEntry()
        }
        ReleaseArtifacts.extractRuntime(zip, jar)
        ReleaseArtifacts.validateJar(jar, "v1.2.3")
        Files.writeString(temp.resolve(name), "corrupt")
        assertFailsWith<IllegalStateException> { ReleaseArtifacts.validateJar(jar, "v1.2.3") }
    }

    @Test fun `Windows arguments preserve spaces quotes and trailing backslashes`() {
        assertEquals("\"C:\\Program Files\\Java\\java.exe\"", ServerMaintenanceService.quoteWindowsArgument("C:\\Program Files\\Java\\java.exe"))
        assertEquals("\"a\\\"b\"", ServerMaintenanceService.quoteWindowsArgument("a\"b"))
        assertEquals("\"C:\\data\\\\\"", ServerMaintenanceService.quoteWindowsArgument("C:\\data\\"))
        assertEquals("\"\"", ServerMaintenanceService.quoteWindowsArgument(""))
    }
}
