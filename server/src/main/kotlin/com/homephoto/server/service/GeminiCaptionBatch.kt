package com.homephoto.server.service

import com.fasterxml.jackson.databind.JsonNode
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Path
import java.util.UUID

/** 짧은 제출/조회만 수행한다. 원격 대기 중에는 스레드와 DB 트랜잭션을 점유하지 않는다. */
internal class GeminiCaptionBatch(
    private val props: AppProperties,
    private val queue: JobQueueService,
    private val client: GeminiCaptionClient = GeminiCaptionClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun waiting(): Long = transaction { CaptionBatchItems.selectAll().count() }

    fun poll(allowed: () -> Boolean = { true }, save: (Long, CaptionService.CaptionResult) -> Unit) {
        // 접수 응답 직후 DB 저장이 실패해도 다음 주기에 중복 제출 없이 확인 대상으로 복구한다.
        val unknown = transaction { CaptionBatches.selectAll().where { CaptionBatches.remoteName.isNull() }.map { it[CaptionBatches.id] } }
        unknown.forEach { finishFailed(it, UNKNOWN_SUBMISSION) }
        val batches = transaction { CaptionBatches.selectAll().where {
            CaptionBatches.remoteName.isNotNull() and (CaptionBatches.nextPoll lessEq clock())
        }.toList() }
        for (batch in batches) {
            if (!allowed()) return
            ProcessMonitor.checkpoint()
            val id = batch[CaptionBatches.id]
            val cfg = props.caption.copy(geminiModel = batch[CaptionBatches.model], geminiApiKeyFile = batch[CaptionBatches.keyFile])
            transaction { CaptionBatches.update({ CaptionBatches.id eq id }) { it[nextPoll] = clock() + POLL_MS } }
            ProcessMonitor.stage("Gemini 배치 결과 확인")
            val response = try { client.pollBatch(batch[CaptionBatches.remoteName]!!, cfg) }
            catch (e: GeminiCaptionClient.HttpFailure) {
                if (e.code == 404) { finishFailed(id, "Gemini 배치가 만료되었거나 삭제되었습니다. 확인 후 재시도하세요."); continue }
                throw CaptionUnavailableException(e.message!!, retrySeconds = e.waitSeconds)
            }
            val state = response.path("metadata").path("state").asText()
            if (response.has("error") || state in setOf("BATCH_STATE_FAILED", "BATCH_STATE_CANCELLED", "BATCH_STATE_EXPIRED", "JOB_STATE_FAILED", "JOB_STATE_CANCELLED", "JOB_STATE_EXPIRED")) {
                finishFailed(id, "Gemini 배치 실패·취소·만료. 확인 후 재시도하세요.")
                continue
            }
            if (!response.path("done").asBoolean(false)) continue
            val entries = response.path("response").path("inlinedResponses").path("inlinedResponses")
            if (!entries.isArray) {
                finishFailed(id, "Gemini 배치 결과 형식을 확인할 수 없습니다. 확인 후 재시도하세요.")
                continue
            }
            // 순서 대신 제출한 키로 매칭한다. 중복/누락 키를 다른 사진에 적용하지 않는다.
            val grouped = entries.groupBy { it.path("metadata").path("key").asText() }
            // JSON 검증은 쓰기 잠금을 잡기 전에 끝낸다.
            val results = grouped.mapValues { (_, responses) ->
                val entry = responses.singleOrNull()
                if (entry != null && entry.has("response") && !entry.has("error"))
                    runCatching { client.captionResult(client.parseResponse(entry.path("response")), cfg.geminiModel) }.getOrNull() else null
            }
            transaction {
                if (!reserveBatchWrite(id)) return@transaction
                val items = CaptionBatchItems.selectAll().where { CaptionBatchItems.batchId eq id }.toList()
                for (item in items) {
                    val jobId = item[CaptionBatchItems.jobId]
                    val result = results[jobId.toString()]
                    val job = (Jobs innerJoin Assets).selectAll().where {
                        (Jobs.id eq jobId) and (Jobs.status eq "RUNNING") and Assets.deletedAt.isNull() and Assets.purgedAt.isNull()
                    }.firstOrNull()
                    if (job != null && result != null) queue.complete(jobId, "CAPTION") { assetId -> save(assetId, result) }
                    else if (job != null) failJob(jobId, "Gemini 배치 항목 실패 또는 응답 누락. 확인 후 재시도하세요.")
                    else Jobs.update({ (Jobs.id eq jobId) and (Jobs.status eq "RUNNING") }) { it[status] = "PENDING" }
                }
                remove(id)
            }
        }
    }

    fun submit(prepare: (JobQueueService.Claimed) -> Path, allowed: () -> Boolean) {
        val cfg = props.caption
        if (cfg.provider != "GEMINI" || !allowed()) return
        if (transaction { CaptionBatches.selectAll().count() } >= MAX_IN_FLIGHT) return
        client.key(cfg)
        require(cfg.geminiModel.matches(Regex("[a-zA-Z0-9._-]+"))) { "Gemini 모델명을 확인하세요." }
        val requests = mutableListOf<Pair<Long, JsonNode>>()
        var size = 0
        for (index in 0 until MAX_ITEMS) {
            if (!allowed()) break
            val job = queue.claim("CAPTION") ?: break
            try {
                val request = client.captionRequest(CaptionImage.jpeg(prepare(job)))
                val bytes = request.toString().toByteArray(Charsets.UTF_8).size + 256
                if (size + bytes > MAX_BYTES) { queue.release(job.jobId, "CAPTION"); break }
                requests.add(job.jobId to request)
                size += bytes
            } catch (e: Exception) {
                if (ProcessMonitor.cancelled()) throw e
                queue.fail(job.jobId, "CAPTION", "Gemini 배치용 이미지 준비 실패")
            }
        }
        if (requests.isEmpty()) return
        if (!allowed()) { requests.forEach { queue.release(it.first, "CAPTION") }; return }
        val id = UUID.randomUUID().toString()
        transaction {
            CaptionBatches.insert {
                it[CaptionBatches.id] = id; it[model] = cfg.geminiModel; it[keyFile] = cfg.geminiApiKeyFile
                it[nextPoll] = clock() + POLL_MS
            }
            requests.forEach { (jobId, _) -> CaptionBatchItems.insert { it[CaptionBatchItems.jobId] = jobId; it[batchId] = id } }
        }
        queue.detachCaptionBatch(requests.map { it.first })
        ProcessMonitor.stage("Gemini 배치 제출", "${requests.size}장")
        val remote = try { client.submitBatch(id, requests, cfg) }
        catch (e: GeminiCaptionClient.HttpFailure) {
            if (e.code in setOf(400, 401, 403, 404, 413, 429)) {
                transaction { requests.forEach { queue.release(it.first, "CAPTION") }; remove(id) }
                throw CaptionUnavailableException(e.message!!, retrySeconds = e.waitSeconds)
            }
            finishFailed(id, UNKNOWN_SUBMISSION)
            throw CaptionUnavailableException(UNKNOWN_SUBMISSION)
        } catch (_: Exception) {
            finishFailed(id, UNKNOWN_SUBMISSION)
            throw CaptionUnavailableException(UNKNOWN_SUBMISSION)
        }
        // DB 저장 실패 시 SUBMITTING을 보존한다. 자동 재제출하지 않고 재시작 복구에서 확인 대상으로 분리한다.
        transaction { CaptionBatches.update({ CaptionBatches.id eq id }) { it[remoteName] = remote } }
    }

    private fun finishFailed(id: String, message: String) = transaction {
        if (!reserveBatchWrite(id)) return@transaction
        CaptionBatchItems.selectAll().where { CaptionBatchItems.batchId eq id }.toList().forEach { failJob(it[CaptionBatchItems.jobId], message) }
        remove(id)
    }

    /**
     * SELECT보다 먼저 실제 UPDATE를 실행해 SQLite 쓰기 잠금을 확보한다.
     * 읽기 스냅샷을 만든 뒤 다른 연결이 커밋하면 busy_timeout으로 기다릴 수 없는
     * SQLITE_BUSY_SNAPSHOT이 발생한다. JDBC 전역 트랜잭션 모드는 바꾸지 않는다.
     */
    private fun reserveBatchWrite(id: String): Boolean = CaptionBatches.update({ CaptionBatches.id eq id }) {
        it[nextPoll] = clock() + POLL_MS
    } > 0

    private fun failJob(id: Long, message: String) {
        Jobs.update({ (Jobs.id eq id) and (Jobs.status eq "RUNNING") }) {
            it[status] = "FAILED"; it[lastError] = message
            with(SqlExpressionBuilder) { it[attempts] = attempts + 1 }
        }
    }

    private fun remove(id: String) {
        CaptionBatchItems.deleteWhere { batchId eq id }
        CaptionBatches.deleteWhere { CaptionBatches.id eq id }
    }

    companion object {
        const val POLL_MS = 60_000L
        const val MAX_ITEMS = 20
        const val MAX_BYTES = 18_000_000
        const val MAX_IN_FLIGHT = 100
        const val UNKNOWN_SUBMISSION = "Gemini 배치 접수 여부 불명확. Google AI Studio에서 확인 후 재시도하세요."
    }
}
