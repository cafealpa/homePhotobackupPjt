package com.homephoto.server.service

import com.homephoto.server.api.AssetDto
import com.homephoto.server.api.toAssetDto
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.IncomingUploads as Q
import jakarta.annotation.PreDestroy
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 단일 서버의 영구 수신 큐. 파일을 먼저 확정하고 DB 커밋 후에만 접수 성공을 응답한다. */
@Service
class IncomingUploadService(
    private val props: AppProperties,
    private val ingest: AssetIngestService,
    private val locks: AssetLocks,
    private val exif: ExifService,
    private val dates: TakenAtResolver,
    private val activity: ServerActivity = ServerActivity(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "original-storage-worker").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)

    data class Receipt(val hash: String, val status: String = "QUEUED")
    data class Accepted(val receipt: Receipt, val existing: AssetDto? = null)
    data class Item(val hash: String, val filename: String, val bytes: Long, val status: String,
                    val receivedAt: Long, val nextAttemptAt: Long, val attempts: Int, val lastError: String?)
    data class Summary(val count: Long, val bytes: Long, val oldestReceivedAt: Long?, val items: List<Item>)

    fun accept(source: Path, filename: String, hash: String, expectedHash: String?,
               fileMtime: Instant?, deviceId: String?, deviceName: String?): Accepted {
        require(expectedHash == null || hash.equals(expectedHash, ignoreCase = true)) { "hash mismatch" }
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext !in AssetIngestService.PHOTO_EXTENSIONS && ext !in AssetIngestService.VIDEO_EXTENSIONS)
            throw UnsupportedMediaException(ext)
        require(Files.size(source) > 0) { "empty upload" }
        return locks.withHash(hash) {
            val asset = transaction { Assets.selectAll().where { Assets.hash eq hash }.firstOrNull() }
            if (asset != null && asset[Assets.deletedAt] == null && asset[Assets.sourceTag] == null)
                return@withHash Accepted(Receipt(hash, "STORED"), asset.toAssetDto())
            val old = transaction { Q.selectAll().where { Q.hash eq hash }.firstOrNull() }
            if (old != null && old[Q.status] in HELD && Files.isRegularFile(localFile(old)) &&
                old[Q.restoreDeletedAt] == asset?.get(Assets.deletedAt) && old[Q.restorePurgedAt] == asset?.get(Assets.purgedAt))
                return@withHash Accepted(Receipt(hash))

            val resolved = dates.resolve(filename, exif.extract(source).takenAt, fileMtime)
            Files.createDirectories(props.incomingDir)
            val name = "${UUID.randomUUID()}.$ext"
            val target = props.incomingDir.resolve(name)
            // tmp와 incoming은 같은 로컬 루트. 강제 기록 후 원자적으로 이름을 확정한다.
            FileChannel.open(source, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            // DB 커밋 결과가 불확실할 때도 사본을 삭제하지 않는다. 고아 파일은 운영 문서에 따라 확인한다.
            transaction {
                Q.deleteWhere { Q.hash eq hash }
                Q.insert {
                    it[Q.hash] = hash; it[localName] = name; it[Q.filename] = filename
                    it[bytes] = Files.size(target); it[takenAt] = resolved.takenAt.toString()
                    it[takenAtSource] = resolved.source; it[Q.deviceId] = deviceId; it[Q.deviceName] = deviceName
                    it[restoreDeletedAt] = asset?.get(Assets.deletedAt)
                    it[restorePurgedAt] = asset?.get(Assets.purgedAt)
                    it[receivedAt] = System.currentTimeMillis()
                }
            }
            log.info("업로드 수신 완료: {} — 원본 저장 대기", filename)
            Accepted(Receipt(hash))
        }
    }

    /** 호출자는 assets 조회와 같은 트랜잭션에서 사용한다. 명시적 복원 접수만 묘비보다 우선한다. */
    fun queuedHashes(hashes: List<String>): Set<String> = hashes.chunked(500).flatMap { chunk ->
        val deletedByHash = Assets.select(Assets.hash, Assets.deletedAt, Assets.purgedAt).where { Assets.hash inList chunk }
            .associate { it[Assets.hash] to (it[Assets.deletedAt] to it[Assets.purgedAt]) }
        Q.selectAll().where { (Q.hash inList chunk) and (Q.status inList HELD) }.mapNotNull { row ->
            val deleted = deletedByHash[row[Q.hash]]
            row[Q.hash].takeIf { deleted?.first == null || deleted == (row[Q.restoreDeletedAt] to row[Q.restorePurgedAt]) }
        }
    }.toSet()

    fun recover() {
        Files.createDirectories(props.incomingDir)
        transaction {
            Q.update({ Q.status eq "RUNNING" }) { it[status] = "PENDING"; it[nextAttemptAt] = 0 }
        }
        // 완료 기록 이후 로컬 파일 삭제 전에 종료된 경우만 정리한다.
        transaction { Q.selectAll().where { Q.status inList listOf("DONE", "CANCELLED") }.toList() }
            .forEach { row -> runCatching { Files.deleteIfExists(localFile(row)) } }
    }

    @Scheduled(fixedDelay = 5000)
    fun tick() {
        if (!running.compareAndSet(false, true)) return
        try {
            executor.submit {
                try { while (!Thread.currentThread().isInterrupted && processNext()) { /* drain due work */ } }
                catch (e: Exception) { log.warn("원본 저장 큐 처리 중단 — 다음 주기에 재시도", e) }
                finally { running.set(false) }
            }
        } catch (e: Exception) { running.set(false); throw e }
    }

    fun processNext(): Boolean {
        if (!activity.enter("INCOMING")) return false
        try { return processClaimed() } finally { activity.leave("INCOMING") }
    }

    private fun processClaimed(): Boolean {
        val row = transaction {
            val hash = exec("""
                UPDATE incoming_uploads SET status='RUNNING', attempts=attempts+1
                WHERE hash=(SELECT hash FROM incoming_uploads WHERE status='PENDING'
                  AND next_attempt_at <= ${System.currentTimeMillis()} ORDER BY received_at LIMIT 1)
                RETURNING hash
            """.trimIndent(), explicitStatementType = StatementType.SELECT) { rs -> if (rs.next()) rs.getString(1) else null }
                ?: return@transaction null
            Q.selectAll().where { Q.hash eq hash }.first()
        } ?: return false
        val hash = row[Q.hash]
        ProcessMonitor.stage("원본 저장", "${row[Q.filename]} · 시도 ${row[Q.attempts]}")
        activity.event("INCOMING","START","${row[Q.filename]} · 저장 시도 ${row[Q.attempts]}")
        return locks.withHash(hash) {
            val current = transaction { Q.selectAll().where { Q.hash eq hash }.firstOrNull() }
            if (current == null || current[Q.localName] != row[Q.localName] || current[Q.status] != "RUNNING")
                return@withHash true
            val source = localFile(row)
            try {
                ProcessMonitor.checkpoint()
                val asset = transaction { Assets.selectAll().where { Assets.hash eq hash }.firstOrNull() }
                val deleted = asset?.get(Assets.deletedAt)
                if (deleted != null && (deleted != row[Q.restoreDeletedAt] || asset[Assets.purgedAt] != row[Q.restorePurgedAt])) {
                    finish(hash, "CANCELLED") // 접수 뒤 사용자가 삭제한 자산을 되살리지 않는다.
                } else if (asset != null && deleted == null && asset[Assets.sourceTag] == null) {
                    finish(hash, "DONE") // 저장 성공 직후 큐 완료 기록 전에 종료됐던 경우
                } else {
                    if (!Files.isRegularFile(source)) {
                        fail(row, "LOST", "서버 수신 파일이 없습니다. 기기에서 다시 백업해 주세요.")
                        return@withHash true
                    }
                    // 재시도 때 해시를 다시 검증. 원본 저장과 DB 커밋까지 로컬 사본을 유지한다.
                    ingest.ingest(source, row[Q.filename], hash, null,
                        deviceId = row[Q.deviceId], deviceName = row[Q.deviceName],
                        moveSource = false,
                        takenAtOverride = TakenAtResolver.Resolved(LocalDateTime.parse(row[Q.takenAt]), row[Q.takenAtSource]))
                    finish(hash, "DONE")
                    log.info("원본 저장 완료: {}", row[Q.filename])
                }
            } catch (e: Exception) {
                if (e is ProcessStoppedException || ProcessMonitor.cancelled()) {
                    transaction { Q.update({ Q.hash eq hash }) {
                        it[status]="PENDING"; it[nextAttemptAt]=0; it[attempts]=(row[Q.attempts]-1).coerceAtLeast(0)
                    } }
                    return@withHash false
                }
                val state = when (e) {
                    is IOException -> "PENDING"
                    is IllegalArgumentException -> "LOST" // 해시 불일치: 사본 보존, 기기 재전송 허용
                    else -> "BLOCKED"
                }
                fail(row, state, "원본 저장 실패 (${e.javaClass.simpleName}): ${e.message ?: "상세 원인 없음"}")
                log.warn("원본 저장 {}: {} ({})", state, row[Q.filename], e.message)
                // 저장소 장애 시 나머지 파일까지 연달아 실패시키지 않는다.
                return@withHash state != "PENDING"
            }
            activity.success("INCOMING","${row[Q.filename]} · 원본 저장 처리 완료")
            runCatching { Files.deleteIfExists(source) }
                .onFailure { log.warn("저장 완료한 로컬 사본 정리 보류: {}", source) }
            true
        }
    }

    private fun finish(hash: String, state: String) = transaction {
        Q.update({ Q.hash eq hash }) { it[status] = state; it[lastError] = null }
    }

    private fun fail(row: ResultRow, state: String, message: String) = transaction {
        val delay = (30_000L shl (row[Q.attempts] - 1).coerceIn(0, 6)).coerceAtMost(1_800_000L)
        activity.issue("INCOMING",message,if(state == "PENDING") System.currentTimeMillis()+delay else null)
        Q.update({ Q.hash eq row[Q.hash] }) {
            it[status] = state; it[lastError] = message.take(500)
            it[nextAttemptAt] = System.currentTimeMillis() + delay
        }
    }

    fun retry(hash: String) = transaction {
        Q.update({ (Q.hash eq hash) and (Q.status inList listOf("PENDING", "BLOCKED")) }) {
            it[status] = "PENDING"; it[nextAttemptAt] = 0
        }
    }

    fun retryPending() = transaction {
        Q.update({ Q.status eq "PENDING" }) { it[nextAttemptAt]=0 }
    }
    fun retryAll(): Int = transaction {
        Q.update({ Q.status inList listOf("PENDING","BLOCKED") }) { it[status]="PENDING"; it[nextAttemptAt]=0 }
    }

    fun summary(): Summary = transaction {
        val filter = with(SqlExpressionBuilder) { Q.status notInList listOf("DONE", "CANCELLED") }
        val count = Q.hash.count(); val bytes = Q.bytes.sum(); val oldest = Q.receivedAt.min()
        val totals = Q.select(count, bytes, oldest).where { filter }.first()
        val items = Q.selectAll().where { filter }.orderBy(Q.receivedAt to SortOrder.ASC).limit(100).map {
            Item(it[Q.hash], it[Q.filename], it[Q.bytes], it[Q.status], it[Q.receivedAt],
                it[Q.nextAttemptAt], it[Q.attempts], it[Q.lastError])
        }
        Summary(totals[count], totals[bytes] ?: 0L, totals[oldest], items)
    }

    private fun localFile(row: ResultRow): Path = props.incomingDir.resolve(row[Q.localName])
    @PreDestroy fun close() { executor.shutdownNow() }
    companion object { private val HELD = listOf("PENDING", "RUNNING", "BLOCKED") }
}
