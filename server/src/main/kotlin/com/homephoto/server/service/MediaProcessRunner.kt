package com.homephoto.server.service

import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 출력을 계속 비우면서 프로세스 종료를 별도로 기다려 타임아웃을 보장한다. */
@Component
class MediaProcessRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(command: List<String>, timeout: Duration = Duration.ofSeconds(60)) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val started = System.nanoTime()
        log.info("미디어 변환 시작: pid={} thread={} 제한={}초", process.pid(), Thread.currentThread().name, timeout.seconds)
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
            val alive = process.isAlive
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            log.info("미디어 변환 종료: pid={} 소요={}ms 강제종료={} exit={}", process.pid(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), alive,
                if (process.isAlive) "종료 대기" else process.exitValue().toString())
        }
    }
}
