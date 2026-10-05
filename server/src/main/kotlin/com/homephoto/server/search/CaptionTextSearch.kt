package com.homephoto.server.search

import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Captions
import jakarta.annotation.PreDestroy
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@ConfigurationProperties("homephoto.caption-search")
data class CaptionTextSearchProperties(val enabled: Boolean = true, val modelDir: String = "models/caption-e5", val indexDir: String = "",
    val minSimilarity: Double = 0.80, val maxScoreGap: Double = 0.06)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CaptionTextSearchProperties::class)
class CaptionTextSearchConfiguration

@Service
class CaptionTextSearch(private val cfg: CaptionTextSearchProperties, private val app: AppProperties) {
    data class Status(val state: String, val indexed: Int, val model: String = CaptionTextEncoder.ID)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "caption-text-index").apply { isDaemon = true } }
    private val busy = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var state = if (cfg.enabled) "starting" else "disabled"
    @Volatile private var index: CaptionVectorIndex? = null
    private var encoder: CaptionTextEncoder? = null
    private var retryAt = 0L
    val enabled get() = cfg.enabled
    fun status() = Status(state, index?.count() ?: 0)

    @Scheduled(fixedDelay = 1000, initialDelay = 15000)
    fun tick() {
        if (!cfg.enabled || closed || System.currentTimeMillis() < retryAt || !busy.compareAndSet(false, true)) return
        executor.submit {
            try {
                if (index == null) initialize()
                val jobs = CaptionIndexQueue.pending(32)
                for (job in jobs) {
                    if (closed) break
                    val text = CaptionIndexQueue.text(job.id)
                    if (text == null) index!!.delete(job.id)
                    else {
                        val hash = CaptionTextEncoder.fingerprint(text)
                        if (index!!.fingerprint(job.id) != hash) index!!.put(job.id, hash, encoder!!.encode(text, false))
                    }
                }
                // Commit before ack: a crash can repeat work, but cannot lose a SQLite change.
                index!!.commit()
                if (!closed) CaptionIndexQueue.ack(jobs)
                state = if (jobs.size == 32) "indexing" else "ready"
            } catch (_: Exception) { if (state != "model_missing") state = "unavailable"; retryAt = System.currentTimeMillis() + 60000 }
              catch (_: LinkageError) { state = "unavailable"; retryAt = System.currentTimeMillis() + 60000 }
            finally { busy.set(false) }
        }
    }
    private fun initialize() {
        require(cfg.minSimilarity in -1.0..1.0 && cfg.maxScoreGap in 0.0..2.0)
        val model = Path.of(cfg.modelDir)
        if (!CaptionTextEncoder.HASHES.keys.all { Files.isRegularFile(model.resolve(it)) }) {
            state = "model_missing"
            retryAt = System.currentTimeMillis() + 60000
            throw IllegalStateException("model missing")
        }
        val candidate = CaptionTextEncoder(model)
        try {
            candidate.prepare()
            val root = if (cfg.indexDir.isBlank()) app.storageRoot.resolve("caption-search") else Path.of(cfg.indexDir)
            val store = CaptionVectorIndex(root.resolve(CaptionTextEncoder.ID))
            try { CaptionIndexQueue.initialize(store.count() == 0) }
            catch (e: Exception) { store.close(); throw e }
            encoder = candidate; index = store
        } catch (e: Throwable) { candidate.close(); throw e }
    }
    fun captionCandidates(text: String): List<Long> {
        require(text.isNotBlank() && text.length <= 200)
        val store = index ?: throw IllegalStateException("text index not ready")
        val ranked = store.search(encoder!!.encode(text, true))
        // Recheck current text and visibility before applying the score window.
        val current = CaptionIndexQueue.texts(ranked.map { it.id })
        val valid = ranked.filter { hit -> current[hit.id]?.let(CaptionTextEncoder::fingerprint) == hit.fingerprint }
        return select(valid, cfg.minSimilarity, cfg.maxScoreGap)

    }
    companion object {
        internal fun select(hits: List<CaptionVectorIndex.Hit>, minimum: Double, gap: Double): List<Long> {
            val top = hits.firstOrNull()?.let { it.score * 2 - 1 } ?: return emptyList()
            return hits.filter { val cosine = it.score * 2 - 1
                cosine >= minimum && cosine >= top - gap }.map { it.id }
        }
    }
    @PreDestroy fun close() {
        closed = true; executor.shutdown()
        if (executor.awaitTermination(30, TimeUnit.SECONDS)) { index?.close(); encoder?.close() }
    }
}

internal object CaptionIndexQueue {
    data class Job(val id: Long, val version: Long)
    fun initialize(seed: Boolean) = transaction {
        exec("CREATE TABLE IF NOT EXISTS caption_search_changes(asset_id INTEGER PRIMARY KEY, version INTEGER NOT NULL DEFAULT 1)")
        fun trigger(name: String, event: String, table: String, id: String) {
            exec("CREATE TRIGGER IF NOT EXISTS $name AFTER $event ON $table BEGIN INSERT INTO caption_search_changes(asset_id) VALUES($id) ON CONFLICT(asset_id) DO UPDATE SET version=version+1; END")
        }
        trigger("caption_search_insert", "INSERT", "captions", "NEW.asset_id")
        trigger("caption_search_update", "UPDATE", "captions", "NEW.asset_id")
        trigger("caption_search_delete", "DELETE", "captions", "OLD.asset_id")
        trigger("caption_search_visibility", "UPDATE OF deleted_at,purged_at,media_type", "assets", "NEW.id")
        trigger("caption_search_asset_delete", "DELETE", "assets", "OLD.id")
        if (seed) exec("INSERT OR IGNORE INTO caption_search_changes(asset_id) SELECT asset_id FROM captions")
    }
    fun pending(limit: Int): List<Job> = transaction {
        require(limit in 1..100)
        exec("SELECT asset_id,version FROM caption_search_changes ORDER BY asset_id LIMIT $limit") { rs ->
            buildList { while(rs.next()) add(Job(rs.getLong(1), rs.getLong(2))) }
        }.orEmpty()
    }
    fun ack(jobs: List<Job>) = transaction {
        jobs.forEach { exec("DELETE FROM caption_search_changes WHERE asset_id=${it.id} AND version=${it.version}") }
    }
    fun text(id: Long) = texts(listOf(id))[id]
    fun texts(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        return transaction {
            Captions.innerJoin(Assets).selectAll().where {
                (Captions.assetId inList ids) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull() and (Assets.mediaType eq "PHOTO")
            }.associate { it[Captions.assetId] to CaptionTextEncoder.text(it[Captions.caption], it[Captions.tags]) }
        }
    }
}
