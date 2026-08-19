package com.chochocho.homephotoclient.backup

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.chochocho.homephotoclient.data.ApiFactory
import com.chochocho.homephotoclient.data.CheckRequest
import com.chochocho.homephotoclient.data.MediaScanner
import com.chochocho.homephotoclient.data.SettingsRepository
import com.chochocho.homephotoclient.data.local.BackupDb
import com.chochocho.homephotoclient.data.local.FailureEntry
import com.chochocho.homephotoclient.data.toFriendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

fun formatSpeed(bps: Long): String = when {
    bps >= 1_048_576 -> "%.1f MB/s".format(bps / 1_048_576.0)
    bps >= 1024 -> "${bps / 1024} KB/s"
    else -> "$bps B/s"
}

fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

sealed interface BackupState {
    data object Idle : BackupState
    data class Working(
        val phase: String,
        val done: Int,
        val total: Int,
        val current: String?,
        val speedBps: Long? = null, // 업로드 단계에서만 채워짐 (bytes/sec)
        val startedAtMillis: Long = 0L, // 이번 백업 실행이 시작된 시각
    ) : BackupState
    data class Done(
        val uploaded: Int,
        val alreadyOnServer: Int,
        val failed: Int,
        val elapsedMillis: Long = 0L,
    ) : BackupState
    data class Error(val message: String) : BackupState
}

class BackupEngine private constructor(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    private val db = BackupDb(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val mutex = Mutex() // 수동 백업과 자동(WorkManager) 백업의 동시 실행 방지

    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state = _state.asStateFlow()

    private val _counts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts = _counts.asStateFlow()

    // 실패 이력 (최신순). 실패가 기록될 때마다 갱신되어 백업 탭에 바로 반영된다.
    private val _failures = MutableStateFlow<List<FailureEntry>>(emptyList())
    val failures = _failures.asStateFlow()

    fun refreshCounts() {
        scope.launch {
            _counts.value = db.counts()
            _failures.value = db.failureLog()
        }
    }

    fun clearFailureLog() {
        scope.launch {
            db.clearFailureLog()
            _failures.value = emptyList()
        }
    }

    /** 실패 이력을 남기고(사유 포함) 목록을 갱신한다 */
    private fun recordFailure(displayName: String, stage: String, message: String) {
        db.logFailure(displayName, stage, message)
        _failures.value = db.failureLog()
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                runBackupOnce()
            } catch (_: CancellationException) {
                // 사용자가 중지한 경우 — 상태는 runBackupOnce에서 Idle로 정리됨
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** 스킵된(서버에서 삭제된) 항목 목록 */
    suspend fun skippedItems(): List<com.chochocho.homephotoclient.data.local.LocalAsset> =
        kotlinx.coroutines.withContext(Dispatchers.IO) { db.itemsWithStatus("SKIPPED") }

    /** 선택한 스킵 항목을 다시 올리기로 표시하고 백업을 시작한다 */
    fun requeueAndBackup(uris: List<String>) {
        scope.launch {
            db.requeueByUris(uris)
            _counts.value = db.counts()
            start()
        }
    }

    /** 백업 1회 실행. 이미 실행 중이면 끝날 때까지 기다렸다가 이어서 실행된다. */
    suspend fun runBackupOnce(): BackupState = mutex.withLock { runBackup() }

    private suspend fun runBackup(): BackupState {
        val runStart = System.currentTimeMillis()
        try {
            val cfg = settings.settings.first()
            val deviceId = settings.ensureDeviceId()
            val api = ApiFactory.create(cfg.serverUrl, cfg.apiKey, deviceId, cfg.deviceName)

            // 1. MediaStore 스캔
            _state.value = BackupState.Working("스캔", 0, 0, null, startedAtMillis = runStart)
            db.upsertScanned(MediaScanner.scanDcim(context))
            _counts.value = db.counts()

            // 2. 해시 계산 (신규 파일 + 이전에 해시 실패한 파일)
            db.revertHashFailures()
            val toHash = db.itemsWithStatus("NEW")
            toHash.forEachIndexed { i, item ->
                coroutineContext.ensureActive()
                _state.value = BackupState.Working("해시 계산", i + 1, toHash.size, item.displayName, startedAtMillis = runStart)
                try {
                    db.updateHash(item.uri, sha256(item.uri))
                } catch (e: CancellationException) {
                    throw e // 취소는 실패가 아니다 — 상태를 건드리지 않고 그대로 전파
                } catch (e: Exception) {
                    val reason = "파일을 읽을 수 없음: ${e.message ?: e.javaClass.simpleName}"
                    db.updateStatus(item.uri, "FAILED", reason)
                    recordFailure(item.displayName, "해시", reason)
                }
            }
            _counts.value = db.counts()

            // 3. 서버 대조 — UPLOADED 포함 전체 해시를 검증한다.
            //    서버에 이미 있는 건 업로드 없이 완료 처리하고,
            //    서버에서 사라진 건(서버 데이터 유실/이전) 재업로드 대기로 되돌린다.
            _state.value = BackupState.Working("서버 대조", 0, 0, null, startedAtMillis = runStart)
            val pendingHashes = db.itemsWithStatus("HASHED", "FAILED").mapNotNull { it.hash }.toSet()
            val candidates = db.itemsWithStatus("HASHED", "FAILED", "UPLOADED", "SKIPPED")
                .mapNotNull { it.hash }.distinct()
            val onServer = mutableSetOf<String>()
            val missingOnServer = mutableSetOf<String>()
            val deletedOnServer = mutableSetOf<String>()
            candidates.chunked(500).forEach { chunk ->
                coroutineContext.ensureActive()
                val response = api.check(CheckRequest(chunk))
                val missing = response.missing.toSet()
                val deleted = (response.deleted ?: emptyList()).toSet()
                missingOnServer += missing
                deletedOnServer += deleted
                onServer += chunk.filterNot { it in missing || it in deleted }
            }
            db.markUploadedByHashes(onServer)
            // 서버에서 삭제된 사진은 스킵 처리 (백업 탭의 스킵 관리에서 되살릴 수 있음)
            db.markSkippedByHashes(deletedOnServer)
            db.revertToHashedByHashes(missingOnServer)
            // 완료 요약의 "서버에 이미 있음"은 이번에 새로 확인된(업로드 대기였던) 것만 센다
            val skippedThisRun = onServer.count { it in pendingHashes }
            _counts.value = db.counts()

            // 4. 업로드 (RESTORE = 서버에서 삭제됐지만 사용자가 다시 올리기로 한 항목)
            val toUpload = db.itemsWithStatus("HASHED", "FAILED", "RESTORE").filter { it.hash != null }
            var uploaded = 0
            var failed = 0
            val uploadStart = System.currentTimeMillis()
            val bytesSent = java.util.concurrent.atomic.AtomicLong(0)
            val lastSpeedPush = java.util.concurrent.atomic.AtomicLong(0)
            fun speedNow(): Long {
                val elapsed = (System.currentTimeMillis() - uploadStart).coerceAtLeast(1)
                return bytesSent.get() * 1000 / elapsed
            }
            toUpload.forEachIndexed { i, item ->
                coroutineContext.ensureActive()
                _state.value = BackupState.Working("업로드", i + 1, toUpload.size, item.displayName, speedNow(), startedAtMillis = runStart)
                try {
                    // 전송 중에도 0.5초마다 속도를 갱신한다 (대용량 동영상 대비)
                    val response = uploadWithRetry(api, item) { chunkBytes ->
                        bytesSent.addAndGet(chunkBytes)
                        val now = System.currentTimeMillis()
                        val last = lastSpeedPush.get()
                        if (now - last > 500 && lastSpeedPush.compareAndSet(last, now)) {
                            _state.value = BackupState.Working("업로드", i + 1, toUpload.size, item.displayName, speedNow(), startedAtMillis = runStart)
                        }
                    }
                    when (response.code()) {
                        201, 409 -> { db.updateStatus(item.uri, "UPLOADED"); uploaded++ }
                        400 -> {
                            // 해시 불일치 — 저장된 해시가 낡았을 가능성(예: GPS 보존 변경 전 계산).
                            // NEW로 되돌려 다음 실행에서 해시를 재계산하게 한다.
                            val reason = "해시 재계산 예정 (서버와 불일치)"
                            db.updateStatus(item.uri, "NEW", reason)
                            recordFailure(item.displayName, "업로드", "$reason — ${serverErrorMessage(response)}")
                            failed++
                        }
                        else -> {
                            val reason = serverErrorMessage(response)
                            db.updateStatus(item.uri, "FAILED", reason)
                            recordFailure(item.displayName, "업로드", reason)
                            failed++
                        }
                    }
                } catch (e: CancellationException) {
                    throw e // 취소는 실패가 아니다
                } catch (e: Exception) {
                    // 친절한 설명 + 원래 예외 메시지 (원인 추적용)
                    val detail = e.message?.take(200) ?: e.javaClass.simpleName
                    val reason = "${e.toFriendlyMessage()} ($detail)"
                    db.updateStatus(item.uri, "FAILED", reason)
                    recordFailure(item.displayName, "업로드", reason)
                    failed++
                }
                if ((i + 1) % 10 == 0) _counts.value = db.counts()
            }

            _counts.value = db.counts()
            val done = BackupState.Done(uploaded, skippedThisRun, failed, System.currentTimeMillis() - runStart)
            _state.value = done
            return done
        } catch (e: CancellationException) {
            _state.value = BackupState.Idle
            throw e
        } catch (e: Exception) {
            val friendly = e.toFriendlyMessage()
            val detail = e.message?.take(200) ?: e.javaClass.simpleName
            recordFailure("(백업 전체)", "실행", "$friendly ($detail)")
            val error = BackupState.Error(friendly)
            _state.value = error
            return error
        }
    }

    /**
     * 실패 응답을 사람이 읽을 수 있는 한 줄로. 서버는 `{"error": "..."}`로 사유를 내려주므로 그걸 우선 쓴다.
     * 예: "HTTP 415: 지원하지 않는 형식: image/heif"
     */
    private fun serverErrorMessage(response: retrofit2.Response<*>): String {
        val code = response.code()
        val body = runCatching { response.errorBody()?.string() }.getOrNull()?.trim().orEmpty()
        val serverMsg = runCatching { org.json.JSONObject(body).optString("error") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: body.takeIf { it.isNotBlank() && it.length <= 200 }
        val hint = when (code) {
            401 -> "API 키가 올바르지 않음"
            413 -> "파일이 너무 큼 (서버 업로드 한도 초과)"
            415 -> "서버가 지원하지 않는 파일 형식"
            in 500..599 -> "서버 내부 오류"
            else -> null
        }
        return buildString {
            append("HTTP $code")
            hint?.let { append(": ").append(it) }
            serverMsg?.let { append(if (hint != null) " — " else ": ").append(it) }
        }
    }

    /** 순간적인 Wi-Fi 끊김(connect timeout 등)은 짧게 기다렸다 최대 3회 재시도한다. */
    private suspend fun uploadWithRetry(
        api: com.chochocho.homephotoclient.data.HomePhotoApi,
        item: com.chochocho.homephotoclient.data.local.LocalAsset,
        onBytes: (Long) -> Unit = {},
    ): retrofit2.Response<com.chochocho.homephotoclient.data.AssetDto> {
        var lastError: IOException? = null
        repeat(MAX_UPLOAD_ATTEMPTS) { attempt ->
            coroutineContext.ensureActive()
            try {
                val uri = Uri.parse(item.uri)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val filePart = MultipartBody.Part.createFormData(
                    "file", item.displayName,
                    ContentUriRequestBody(
                        context.contentResolver, withOriginalLocation(uri), item.size, mime, onBytes,
                    ),
                )
                return api.upload(
                    file = filePart,
                    hash = item.hash!!.toRequestBody(TEXT),
                    fileMtime = item.mtime?.toString()?.toRequestBody(TEXT),
                )
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_UPLOAD_ATTEMPTS - 1) kotlinx.coroutines.delay(2000L * (attempt + 1))
            }
        }
        throw lastError!!
    }

    /**
     * ACCESS_MEDIA_LOCATION 권한이 있으면 위치 EXIF가 지워지지 않은 원본을 요구한다.
     * 권한 없이 MediaStore 스트림을 열면 Android가 GPS를 0으로 지운 사본을 준다.
     */
    private fun withOriginalLocation(uri: Uri): Uri =
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) MediaStore.setRequireOriginal(uri) else uri

    private fun sha256(uriString: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(withOriginalLocation(Uri.parse(uriString)))?.use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: throw IOException("cannot open $uriString")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_UPLOAD_ATTEMPTS = 3
        private val TEXT = "text/plain".toMediaType()

        @Volatile
        private var instance: BackupEngine? = null

        fun get(context: Context): BackupEngine =
            instance ?: synchronized(this) {
                instance ?: BackupEngine(
                    context.applicationContext,
                    SettingsRepository(context.applicationContext),
                ).also { instance = it }
            }
    }
}

/** ContentResolver 스트림을 그대로 흘려보내는 RequestBody (파일 전체를 메모리에 올리지 않음) */
private class ContentUriRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val size: Long,
    mime: String,
    private val onBytes: (Long) -> Unit = {},
) : RequestBody() {
    private val mediaType = mime.toMediaTypeOrNull()

    override fun contentType() = mediaType
    override fun contentLength() = size

    override fun writeTo(sink: BufferedSink) {
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                onBytes(read.toLong())
            }
        } ?: throw IOException("cannot open $uri")
    }
}
