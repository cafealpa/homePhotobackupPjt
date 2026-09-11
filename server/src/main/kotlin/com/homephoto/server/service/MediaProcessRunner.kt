package com.homephoto.server.service

import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 출력을 계속 비우면서 프로세스 종료를 별도로 기다려 타임아웃을 보장한다. */
@Component
class MediaProcessRunner {
    fun run(command: List<String>, timeout: Duration = Duration.ofSeconds(60)) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val tail = StringBuilder()
        val reader = thread(name = "media-process-output", isDaemon = true) {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(tail) {
                            tail.append(buffer, 0, count)
                            if (tail.length > 4096) tail.delete(0, tail.length - 4096)
                        }
                    }
                }
            }
        }
        try {
            check(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) { "media process timeout: ${command.first()}" }
            reader.join(1000)
            check(process.exitValue() == 0) {
                "media process failed (exit ${process.exitValue()}): ${synchronized(tail) { tail.takeLast(500).toString() }}"
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
        }
    }
}
