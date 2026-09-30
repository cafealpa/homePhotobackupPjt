package com.homephoto.server.publication

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.GooglePhotosPublications as P
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/** 기존 Jobs의 시작 재시도 정책을 사용하지 않는 별도 영속 큐. 단일 서버/계정에서 생성 호출을 직렬화한다. */
@Service
class GooglePhotosPublicationQueue(private val props: AppProperties, private val mapper: ObjectMapper) {
    internal var clock: () -> Long = System::currentTimeMillis
    class Claimed(val asset: PublicationAsset, val lease: String, val attempts: Int, val connection: String?,
                  val path: String?, val sha256: String?, val token: String?, val tokenCreatedAt: Long?)
    data class Enqueued(val enqueued: Int, val existing: Int, val ineligible: Int)
    data class Item(val assetId: Long, val originalFilename: String, val status: String, val attempts: Int,
                    val nextAttemptAt: Long, val mediaItemId: String?, val productUrl: String?, val uploadedAt: String?,
                    val lastError: String?, val metadata: PublicationMetadata?, val updatedAt: String)

    fun enqueue(ids: List<Long>): Enqueued {
        require(ids.size in 1..1000) { "한 번에 1~1000개 자산을 선택하세요." }
        return transaction {
            var added = 0; var existing = 0; var skipped = 0
            for (id in ids.distinct()) {
                val asset = Assets.selectAll().where { Assets.id eq id }.firstOrNull()
                if (asset == null || asset[Assets.deletedAt] != null || asset[Assets.purgedAt] != null ||
                    (asset[Assets.mediaType] != "PHOTO" && !props.googlePhotos.includeVideos)) { skipped++; continue }
                val row = P.selectAll().where { P.assetId eq id }.firstOrNull()
                if (row == null) {
                    val written = P.insertIgnore {
                        it[assetId] = id; it[renditionVersion] = GooglePhotosExport.VERSION
                        it[createdAt] = now(); it[updatedAt] = now()
                    }.insertedCount
                    if (written > 0) added++ else existing++
                } else if (row[P.status] == "CANCELLED" && row[P.mediaItemId] == null) {
                    P.update({ (P.assetId eq id) and (P.status eq "CANCELLED") }) { it[status] = "PENDING"; it[attempts] = 0; it[updatedAt] = now() }
                    added++
                } else existing++
            }
            Enqueued(added, existing, skipped)
        }
    }

    /** 새 썸네일 완료 후 등록. 비활성/자동 게시 꺼짐은 기존 백업과 큐에 아무 영향을 주지 않는다. */
    fun enqueueNew(id: Long) {
        if (!props.googlePhotos.enabled || !props.googlePhotos.autoPublishNew) return
        val normal = transaction { Assets.selectAll().where { (Assets.id eq id) and Assets.sourceTag.isNull() }.any() }
        if (normal) enqueue(listOf(id))
    }

    fun enqueueRecent(limit: Int): Enqueued {
        require(limit in 1..100) { "소량 게시 준비는 1~100개로 제한합니다." }
        val ids = transaction {
            (Assets leftJoin P).select(Assets.id).where {
                Assets.deletedAt.isNull() and Assets.purgedAt.isNull() and Assets.sourceTag.isNull() and P.assetId.isNull() and
                    (if (props.googlePhotos.includeVideos) Op.TRUE else Assets.mediaType eq "PHOTO")
            }.orderBy(Assets.takenAt to SortOrder.DESC, Assets.id to SortOrder.DESC).limit(limit).map { it[Assets.id] }
        }
        return if (ids.isEmpty()) Enqueued(0, 0, 0) else enqueue(ids)
    }

    fun claim(): Claimed? = transaction {
        val lease = UUID.randomUUID().toString()
        val id = exec("""
            UPDATE google_photos_publications SET status='PREPARING_METADATA', lease_id=?, attempts=attempts+1, updated_at=?
            WHERE asset_id=(SELECT p.asset_id FROM google_photos_publications p JOIN assets a ON a.id=p.asset_id
              WHERE p.status='PENDING' AND p.next_attempt_at<=${clock()} AND a.deleted_at IS NULL AND a.purged_at IS NULL
              AND EXISTS (SELECT 1 FROM jobs j WHERE j.asset_id=a.id AND j.job_type='THUMBNAIL' AND j.status='DONE')
              AND (a.media_type='PHOTO' OR ${if (props.googlePhotos.includeVideos) "1" else "0"}=1)
              AND NOT EXISTS (SELECT 1 FROM google_photos_publications WHERE status IN ('PREPARING_METADATA','UPLOADING','READY_TO_CREATE','CREATING_MEDIA_ITEM'))
              ORDER BY a.taken_at DESC, a.id DESC LIMIT 1) RETURNING asset_id
        """.trimIndent(), args = listOf(TextColumnType() to lease, TextColumnType() to now()), explicitStatementType = StatementType.SELECT) {
            rs -> if (rs.next()) rs.getLong(1) else null
        } ?: return@transaction null
        val row = (P innerJoin Assets).selectAll().where { P.assetId eq id }.first()
        Claimed(row.asset(), lease, row[P.attempts], row[P.connectionId], row[P.renditionPath], row[P.renditionSha256], row[P.uploadToken], row[P.tokenCreatedAt])
    }

    fun prepared(job: Claimed, export: GooglePhotosExport.Prepared, connection: String): Boolean = transaction {
        P.update({ owned(job) and (P.status eq "PREPARING_METADATA") }) {
            it[status] = "UPLOADING"; it[connectionId] = connection; it[renditionPath] = export.path.toAbsolutePath().toString()
            it[renditionSha256] = export.sha256; it[metadataJson] = mapper.writeValueAsString(export.metadata)
            it[renditionVersion] = GooglePhotosExport.VERSION; it[uploadToken] = null; it[tokenCreatedAt] = null; it[updatedAt] = now()
        } > 0
    }

    fun resume(job: Claimed, ready: Boolean, connection: String): Boolean = transaction {
        P.update({ owned(job) and (P.status eq "PREPARING_METADATA") }) {
            it[status] = if (ready) "READY_TO_CREATE" else "UPLOADING"; it[updatedAt] = now(); it[connectionId] = connection
            if (!ready) { it[uploadToken] = null; it[tokenCreatedAt] = null }
        } > 0
    }

    fun tokenSaved(job: Claimed, token: String): Boolean = transaction {
        P.update({ owned(job) and (P.status eq "UPLOADING") }) {
            it[status] = "READY_TO_CREATE"; it[uploadToken] = token; it[tokenCreatedAt] = clock(); it[updatedAt] = now()
        } > 0
    }

    fun beginCreate(job: Claimed): Boolean = transaction {
        if (!active(job.asset.id)) {
            P.update({ owned(job) }) {
                it[status] = "CANCELLED"; it[leaseId] = null; it[lastError] = "ASSET_INACTIVE"; it[updatedAt] = now()
                it[uploadToken] = null; it[tokenCreatedAt] = null; it[renditionPath] = null
            }
            return@transaction false
        }
        P.update({ owned(job) and (P.status eq "READY_TO_CREATE") }) { it[status] = "CREATING_MEDIA_ITEM"; it[updatedAt] = now() } > 0
    }

    fun complete(job: Claimed, published: GooglePhotosPublisher.Published): Boolean = transaction {
        P.update({ owned(job) and (P.status eq "CREATING_MEDIA_ITEM") }) {
            it[status] = "COMPLETED"; it[mediaItemId] = published.mediaItemId; it[productUrl] = published.productUrl
            it[uploadedAt] = now(); it[updatedAt] = now(); it[lastError] = null; it[leaseId] = null
            it[uploadToken] = null; it[tokenCreatedAt] = null; it[renditionPath] = null
        } > 0
    }

    fun fail(job: Claimed, failure: PublicationFailure): Boolean = transaction {
        P.update({ owned(job) }) {
            it[status] = when (failure.kind) {
                PublicationFailure.Kind.UNCERTAIN -> "UNKNOWN"
                PublicationFailure.Kind.AUTH -> "AUTH_REQUIRED"
                PublicationFailure.Kind.PERMANENT -> "FAILED"
                PublicationFailure.Kind.RETRYABLE -> if (job.attempts >= props.googlePhotos.maxAttempts) "FAILED" else "PENDING"
            }
            it[leaseId] = null; it[lastError] = failure.code; it[updatedAt] = now()
            it[nextAttemptAt] = clock() + maxOf((30L shl (job.attempts - 1).coerceIn(0, 7)).coerceAtMost(3600), failure.retryAfterSeconds) * 1000
        } > 0
    }

    fun release(job: Claimed): Boolean = transaction {
        P.update({ owned(job) and (P.status neq "CREATING_MEDIA_ITEM") }) {
            it[status] = "PENDING"; it[leaseId] = null; it[updatedAt] = now(); it[attempts] = (job.attempts - 1).coerceAtLeast(0)
        } > 0
    }

    fun recover() = transaction {
        P.update({ P.status eq "CREATING_MEDIA_ITEM" }) { it[status] = "UNKNOWN"; it[leaseId] = null; it[lastError] = "CREATE_INTERRUPTED"; it[updatedAt] = now() }
        P.update({ P.status inList listOf("PREPARING_METADATA", "UPLOADING", "READY_TO_CREATE") }) {
            it[status] = "PENDING"; it[leaseId] = null; it[updatedAt] = now()
        }
    }

    /** 원본 삭제를 외부 요청에 묶지 않는다. 생성 중/결과 불명/완료 이력은 중복 방지를 위해 유지한다. */
    fun cancelInactive(id: Long) { cancel(id) }
    fun cancel(id: Long): Boolean {
        val result = transaction {
            val row = P.selectAll().where { P.assetId eq id }.firstOrNull() ?: return@transaction false to null
            val changed = P.update({ (P.assetId eq id) and (P.status inList listOf("PENDING", "PREPARING_METADATA", "UPLOADING", "READY_TO_CREATE", "FAILED", "AUTH_REQUIRED")) }) {
                it[status] = "CANCELLED"; it[leaseId] = null; it[uploadToken] = null; it[tokenCreatedAt] = null
                it[renditionPath] = null; it[lastError] = "ASSET_INACTIVE"; it[updatedAt] = now()
            } > 0
            changed to if (changed) row[P.renditionPath]?.let(Path::of) else null
        }
        val path = result.second
        if (path != null && path.toAbsolutePath().normalize().parent == props.uploadTmpDir.resolve("google-photos").toAbsolutePath().normalize())
            runCatching { Files.deleteIfExists(path) }
        return result.first
    }

    fun retry(id: Long): Boolean = transaction {
        if (!active(id)) return@transaction false
        val auth = P.select(P.status).where { P.assetId eq id }.firstOrNull()?.get(P.status) == "AUTH_REQUIRED"
        P.update({ (P.assetId eq id) and (P.status inList listOf("FAILED", "AUTH_REQUIRED")) }) {
            it[status] = "PENDING"; it[attempts] = 0; it[nextAttemptAt] = 0; it[lastError] = null; it[updatedAt] = now()
            if (auth) { it[connectionId] = null; it[uploadToken] = null; it[tokenCreatedAt] = null }
        } > 0
    }

    fun resolve(id: Long, mediaItemId: String?, productUrl: String?, confirmedNotCreated: Boolean): Boolean = transaction {
        require(!mediaItemId.isNullOrBlank() || confirmedNotCreated) { "기존 Google 항목 ID 또는 미생성 확인이 필요합니다." }
        P.update({ (P.assetId eq id) and (P.status eq "UNKNOWN") }) {
            if (!mediaItemId.isNullOrBlank()) {
                it[status] = "COMPLETED"; it[P.mediaItemId] = mediaItemId; it[P.productUrl] = productUrl; it[uploadedAt] = now(); it[renditionPath] = null
            } else { it[status] = "PENDING"; it[attempts] = 0; it[nextAttemptAt] = 0 }
            it[uploadToken] = null; it[tokenCreatedAt] = null; it[leaseId] = null; it[lastError] = null; it[updatedAt] = now()
        } > 0
    }

    fun items(limit: Int = 100): List<Item> = transaction {
        (P innerJoin Assets).selectAll().orderBy(P.updatedAt to SortOrder.DESC, P.assetId to SortOrder.DESC).limit(limit).map { row ->
            Item(row[P.assetId], row[Assets.originalFilename], row[P.status], row[P.attempts], row[P.nextAttemptAt], row[P.mediaItemId], row[P.productUrl],
                row[P.uploadedAt], row[P.lastError], row[P.metadataJson]?.let { mapper.readValue(it, PublicationMetadata::class.java) }, row[P.updatedAt])
        }
    }
    fun counts(): Map<String, Long> = transaction { P.select(P.status, P.assetId.count()).groupBy(P.status).associate { it[P.status] to it[P.assetId.count()] } }
    fun renditionPath(id: Long): Path? = transaction { P.select(P.renditionPath).where { P.assetId eq id }.firstOrNull()?.get(P.renditionPath)?.let(Path::of) }
    fun referencedPaths(): Set<String> = transaction { P.select(P.renditionPath).where { P.renditionPath.isNotNull() }.mapNotNull { it[P.renditionPath] }.toSet() }
    private fun SqlExpressionBuilder.owned(job: Claimed) = (P.assetId eq job.asset.id) and (P.leaseId eq job.lease)
    private fun Transaction.active(id: Long): Boolean = Assets.selectAll().where { (Assets.id eq id) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull() }.any()
    private fun now() = Instant.ofEpochMilli(clock()).toString()
    private fun ResultRow.asset() = PublicationAsset(this[Assets.id], this[Assets.hash], this[Assets.originalPath], this[Assets.originalFilename],
        this[Assets.takenAt], this[Assets.takenAtSource], this[Assets.gpsLat], this[Assets.gpsLon])
}
