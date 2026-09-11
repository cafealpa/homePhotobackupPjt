package com.homephoto.server

import com.homephoto.server.service.AtomicFiles
import com.homephoto.server.service.MediaProcessRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

@Timeout(15)
class FileSafetyTest {
    @TempDir lateinit var temp: Path

    @Test fun `failed write keeps existing target and removes temporary file`() {
        val target = temp.resolve("image.jpg")
        Files.writeString(target, "original")
        assertFailsWith<IllegalStateException> {
            AtomicFiles.write(target) {
                Files.writeString(it, "partial")
                error("interrupted generation")
            }
        }
        assertEquals("original", Files.readString(target))
        Files.list(temp).use { assertEquals(listOf(target), it.toList()) }
    }

    @Test fun `empty output is not published and successful retry is published`() {
        val target = temp.resolve("image.jpg")
        assertFailsWith<IllegalStateException> { AtomicFiles.write(target) {} }
        assertFalse(Files.exists(target))
        AtomicFiles.write(target) { Files.writeString(it, "complete") }
        assertEquals("complete", Files.readString(target))
    }

    private fun command(mode: String) = listOf(
        Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString(),
        "-cp", Path.of(ProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).toString(),
        ProcessFixture::class.java.name, mode,
    )

    @Test fun `hanging process times out even while its output stream stays open`() {
        val started = System.nanoTime()
        val exception = assertFailsWith<IllegalStateException> {
            MediaProcessRunner().run(command("hang"), Duration.ofMillis(500))
        }
        assertTrue(exception.message.orEmpty().contains("timeout"))
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(5))
    }

    @Test fun `large process output is drained and final failure is reported`() {
        val exception = assertFailsWith<IllegalStateException> {
            MediaProcessRunner().run(command("fail"), Duration.ofSeconds(5))
        }
        assertTrue(exception.message.orEmpty().contains("exit 7"))
        assertTrue(exception.message.orEmpty().contains("final failure marker"))
    }

    @Test fun `successful process completes normally`() {
        MediaProcessRunner().run(command("ok"), Duration.ofSeconds(5))
    }
}
