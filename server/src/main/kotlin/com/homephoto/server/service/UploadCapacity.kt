package com.homephoto.server.service

import com.homephoto.server.config.AppProperties
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.LinkOption

@Component
class UploadDiskProbe(private val props: AppProperties) {
    data class Usage(val total: Long, val usable: Long, val incoming: Long)
    fun read(): Usage {
        Files.createDirectories(props.incomingDir)
        val store = Files.getFileStore(props.incomingDir)
        // DB에 없는 고아 파일·삭제 실패 사본도 실제 용량에 포함한다. 자동으로 삭제하지 않는다.
        val incoming = Files.walk(props.incomingDir).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong {
                try { Files.size(it) } catch (_: java.nio.file.NoSuchFileException) { 0L } // 원본 이관 직후 삭제 경합
            }.sum()
        }
        return Usage(store.totalSpace, store.usableSpace, incoming)
    }
}

class UploadSpaceUnavailable(val reason: String) : RuntimeException(reason)

/** 멀티파트 파싱 전에 예약한다. 검사와 예약은 하나의 락으로 병렬 요청 경합을 막는다. */
@Service
class UploadCapacity(private val props: AppProperties, private val disk: UploadDiskProbe) {
    private var reserved = 0L
    private var paused = false
    private var blockedBytes = 0L
    private var reason: String? = null
    data class Status(val accepting: Boolean, val reason: String?, val usableBytes: Long, val totalBytes: Long,
                      val incomingBytes: Long, val reservedBytes: Long, val minimumFreeBytes: Long,
                      val maxIncomingBytes: Long, val retryAfterSeconds: Int = 300)

    @Synchronized fun status(requestBytes: Long = 0): Status {
        require(requestBytes in 0..MAX_REQUEST_BYTES)
        val policy = props.uploadBuffer
        val usage = try { disk.read() } catch (_: Exception) {
            paused = true; reason = "서버 저장 공간을 확인할 수 없습니다. 저장 장치를 확인해 주세요."
            return Status(false, reason, 0, 0, 0, reserved, policy.minFreeGiB * GiB, policy.maxIncomingGiB * GiB)
        }
        val floor = maxOf(policy.minFreeGiB * GiB, usage.total / 100 * policy.minFreePercent)
        val ceiling = policy.maxIncomingGiB * GiB
        val margin = policy.resumeMarginGiB * GiB
        // 멀티파트 임시 파일 + 해시 계산용 사본 + 동기 저장 시 원본 사본까지 보수적으로 예약.
        val free = usage.usable - reserved * DISK_COPIES
        val held = usage.incoming + reserved
        if (paused && free >= floor + margin + blockedBytes * DISK_COPIES && held + blockedBytes <= ceiling - margin) {
            paused = false; reason = null; blockedBytes = 0
        }
        if (free < floor || held >= ceiling) {
            paused = true
            reason = if (free < floor) "서버 디스크 여유 공간 부족으로 백업을 대기합니다." else "서버 원본 저장 대기 용량이 상한에 도달했습니다."
        }
        val requestReason = when {
            requestBytes > ceiling -> "이 파일이 서버 수신 용량 상한보다 큽니다. 서버 용량 설정을 확인해 주세요."
            free - requestBytes * DISK_COPIES < floor -> "이 파일을 받을 서버 디스크 공간이 부족합니다."
            held + requestBytes > ceiling -> "이 파일을 받을 서버 원본 저장 대기 공간이 부족합니다."
            else -> null
        }
        if (requestReason != null && requestBytes <= ceiling) {
            paused = true; blockedBytes = maxOf(blockedBytes, requestBytes); reason = requestReason
        }
        return Status(!paused && requestReason == null, reason ?: requestReason, usage.usable, usage.total, usage.incoming,
            reserved, floor, ceiling)
    }

    @Synchronized fun reserve(bytes: Long): AutoCloseable {
        require(bytes in 1..MAX_REQUEST_BYTES)
        val state = status(bytes)
        if (!state.accepting) throw UploadSpaceUnavailable(state.reason!!)
        reserved += bytes
        var released = false
        return AutoCloseable { synchronized(this) { if (!released) { reserved -= bytes; released = true } } }
    }

    @Synchronized fun writeFailed() {
        paused = true
        reason = "서버 디스크 기록 공간이 부족합니다. 공간 확보 후 자동으로 재개합니다."
    }

    companion object {
        const val GiB = 1024L * 1024 * 1024
        const val DISK_COPIES = 3L
        const val MAX_REQUEST_BYTES = 5 * GiB + 1024 * 1024
        fun isDiskFull(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(10).any {
            val text = it.message.orEmpty().lowercase()
            text.contains("no space left") || text.contains("disk full") || text.contains("disk is full") ||
                text.contains("sqlite_full") || text.contains("not enough space") || text.contains("디스크 공간이 부족")
        }
    }
}
