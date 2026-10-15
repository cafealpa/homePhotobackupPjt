package com.homephoto.server.search

import jakarta.annotation.PreDestroy
import com.homephoto.server.service.ServerActivity
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors

/** 허용된 고정 모델만 설치한다. 다운로드와 검증은 요청 스레드 밖에서 수행한다. */
@Service
class SearchModelInstaller(photo: PhotoSearchProperties, caption: CaptionTextSearchProperties,
    private val activity: ServerActivity = ServerActivity()) : AutoCloseable {
    data class Model(val id: String, val name: String, val directory: Path, val base: String, val hashes: Map<String, String>)
    data class Status(val id: String, val name: String, val directory: String, val files: List<String>,
        val missing: List<String>, val state: String, val message: String, val downloadedBytes: Long, val busy: Boolean)
    internal val models = listOf(
        Model("photo", "사진 모델 · 약 1.5GB", Path.of(photo.modelDir).toAbsolutePath().normalize(),
            "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4", SiglipEncoder.HASHES),
        Model("scene", "장면·문서 모델 · 약 129MiB", Path.of(caption.modelDir).toAbsolutePath().normalize(),
            "https://huggingface.co/Xenova/multilingual-e5-small/resolve/761b726dd34fb83930e26aab4e9ac3899aa1fa78", CaptionTextEncoder.HASHES))
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "search-model-download").apply { isDaemon = true } }
    private val progress = mutableMapOf<String, Status>()
    private var active: String? = null
    private var closed = false
    internal var downloadSource: (String) -> InputStream = ::openModelFile

    @Synchronized fun status(): List<Status> = models.map { model ->
        val missing = model.hashes.keys.filterNot { Files.isRegularFile(model.directory.resolve(it)) }
        (progress[model.id] ?: Status(model.id, model.name, model.directory.toString(), model.hashes.keys.toList(), missing,
            if (missing.isEmpty()) "unchecked" else "missing",
            if (missing.isEmpty()) "파일이 있어요. 버튼을 누르면 체크섬을 확인하고 필요한 파일만 받아요." else "필요한 모델 파일이 없어요.", 0, false))
            .copy(missing = missing, busy = active != null)
    }

    @Synchronized fun start(id: String): List<Status> {
        val model = models.find { it.id == id } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (closed || active != null) throw ResponseStatusException(HttpStatus.CONFLICT, "모델 준비 작업이 진행 중이에요. 잠시 기다려 주세요.")
        if (!activity.enter()) throw ResponseStatusException(HttpStatus.CONFLICT, "서버 종료 준비 중이에요.")
        active = id
        update(model, "checking", "기존 파일을 검증하고 있어요.", 0)
        executor.submit {
            try {
                install(model, { file, bytes -> update(model, "downloading", "$file 준비 중", bytes) }, downloadSource)
                update(model, "ready", "체크섬 검증 완료. 모델을 사용할 수 있어요.", 0)
            } catch (e: Exception) {
                val message = when (e) {
                    is ModelChecksumException -> "다운로드한 파일의 체크섬이 달라요. 기존 파일은 보존했어요. 다시 시도해 주세요."
                    is java.nio.file.FileSystemException -> "모델 폴더의 쓰기 권한과 디스크 여유 공간을 확인해 주세요."
                    else -> "모델을 받지 못했어요. 서버의 인터넷 연결과 huggingface.co 접속을 확인한 뒤 다시 시도해 주세요."
                }
                update(model, "failed", message, 0)
            } finally { synchronized(this) { active = null }; activity.leave() }
        }
        return status()
    }

    @Synchronized private fun update(model: Model, state: String, message: String, bytes: Long) {
        progress[model.id] = Status(model.id, model.name, model.directory.toString(), model.hashes.keys.toList(), emptyList(), state, message, bytes, true)
    }

    @PreDestroy @Synchronized override fun close() {
        closed = true
        if (executor.shutdownNow().isNotEmpty()) { active = null; activity.leave() }
    }
}

internal class ModelChecksumException : IllegalStateException()

private fun openModelFile(url: String): InputStream = URI.create(url).toURL().openConnection()
    .apply { connectTimeout = 20000; readTimeout = 60000 }.getInputStream()

/** 임시 파일을 먼저 검증한다. 실패해도 기존 모델은 교체하지 않는다. */
internal fun install(model: SearchModelInstaller.Model, progress: (String, Long) -> Unit = { _, _ -> },
    source: (String) -> InputStream = ::openModelFile) {
    Files.createDirectories(model.directory)
    for ((name, expected) in model.hashes) {
        check(!Thread.currentThread().isInterrupted)
        progress("$name 검증", 0)
        val target = model.directory.resolve(name)
        if (Files.isRegularFile(target) && modelHash(target) == expected) continue
        val temporary = Files.createTempFile(model.directory, ".$name-", ".download")
        try {
            val remote = if (name.endsWith(".onnx")) "onnx/$name" else name
            source("${model.base}/$remote").use { input ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(65536)
                    var bytes = 0L
                    var reported = 0L
                    while (true) {
                        check(!Thread.currentThread().isInterrupted)
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n); bytes += n
                        if (bytes - reported >= 1024 * 1024) { progress(name, bytes); reported = bytes }
                    }
                    progress("$name 검증", bytes)
                }
            }
            if (modelHash(temporary) != expected) throw ModelChecksumException()
            // 같은 파일시스템 안의 원자적 교체. 지원하지 않으면 기존 파일을 유지하고 실패한다.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}

internal fun modelHash(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            check(!Thread.currentThread().isInterrupted)
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@RestController
@RequestMapping("/api/v1/admin/search-models")
class SearchModelController(private val installer: SearchModelInstaller) {
    @GetMapping fun status() = installer.status()
    @PostMapping("/{id}/download", headers = ["X-HomePhoto-Action=search-model"])
    fun download(@PathVariable id: String) = installer.start(id)
}
