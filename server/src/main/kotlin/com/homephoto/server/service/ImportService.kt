package com.homephoto.server.service

import com.homephoto.server.api.ImportStatusDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/** 서버 로컬 디스크의 기존 사진을 일괄 임포트한다 (원본은 건드리지 않고 복사). */
@Service
class ImportService(private val ingestService: AssetIngestService) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    @Volatile
    private var status = ImportStatusDto(
        running = false, total = 0, processed = 0,
        imported = 0, duplicates = 0, failed = 0,
        currentFile = null, lastError = null,
    )

    fun status(): ImportStatusDto = status

    companion object {
        const val IMPORT_DEVICE_ID = "server-import"
    }

    /** @return false면 이미 실행 중 */
    fun start(sourcePath: String): Boolean {
        val root = Path.of(sourcePath)
        require(Files.isDirectory(root)) { "not a directory: $sourcePath" }
        if (!running.compareAndSet(false, true)) return false

        thread(name = "import-worker", isDaemon = true) {
            try {
                val supported = AssetIngestService.PHOTO_EXTENSIONS + AssetIngestService.VIDEO_EXTENSIONS
                val files = Files.walk(root).use { stream ->
                    stream.asSequence()
                        .filter { it.isRegularFile() && it.extension.lowercase() in supported }
                        .toList()
                }
                status = status.copy(running = true, total = files.size, processed = 0, imported = 0, duplicates = 0, failed = 0, lastError = null)
                log.info("import started: ${files.size} files from $sourcePath")

                for (file in files) {
                    status = status.copy(currentFile = file.fileName.toString())
                    try {
                        val mtime = Files.getLastModifiedTime(file).toInstant()
                        val result = ingestService.ingest(
                            file, file.fileName.toString(),
                            expectedHash = null, fileMtime = mtime,
                            deviceId = IMPORT_DEVICE_ID, deviceName = "서버 임포트",
                        )
                        status = if (result.created) status.copy(imported = status.imported + 1)
                        else status.copy(duplicates = status.duplicates + 1)
                    } catch (e: Exception) {
                        log.warn("import failed for $file: ${e.message}")
                        status = status.copy(failed = status.failed + 1, lastError = "${file.fileName}: ${e.message}")
                    }
                    status = status.copy(processed = status.processed + 1)
                    if (status.processed % 100 == 0) {
                        log.info(
                            "임포트 진행: {}/{} (신규 {}, 중복 {}, 실패 {})",
                            status.processed, status.total, status.imported, status.duplicates, status.failed,
                        )
                    }
                }
                log.info("import finished: imported=${status.imported} duplicates=${status.duplicates} failed=${status.failed}")
            } finally {
                status = status.copy(running = false, currentFile = null)
                running.set(false)
            }
        }
        return true
    }
}
