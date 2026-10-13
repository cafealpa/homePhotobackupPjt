package com.homephoto.server.search

import org.jetbrains.exposed.sql.transactions.transaction

/** SQLite에 변경과 형식 전환을 함께 기록한다. 모델이 없어도 변경을 놓치지 않는다. */
internal object CaptionIndexQueue {
    data class Job(val id: Long, val version: Long)
    fun initialize(seed: Boolean) = transaction {
        exec("CREATE TABLE IF NOT EXISTS caption_search_changes(asset_id INTEGER PRIMARY KEY, version INTEGER NOT NULL DEFAULT 1)")
        exec("CREATE TABLE IF NOT EXISTS caption_search_state(id INTEGER PRIMARY KEY CHECK(id=1),format_version INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 0)")
        exec("INSERT OR IGNORE INTO caption_search_state(id) VALUES(1)")
        for (event in listOf("INSERT", "UPDATE", "DELETE")) {
            exec("CREATE TRIGGER IF NOT EXISTS caption_search_revision_${event.lowercase()} AFTER $event ON caption_search_changes BEGIN UPDATE caption_search_state SET revision=revision+1 WHERE id=1; END")
        }
        fun change(name: String, event: String, table: String, ids: String) {
            // SQLite SELECT UPSERT의 구문 모호성을 피하도록 SELECT에 WHERE를 포함한다.
            exec("CREATE TRIGGER IF NOT EXISTS $name AFTER $event ON $table BEGIN INSERT INTO caption_search_changes(asset_id) $ids ON CONFLICT(asset_id) DO UPDATE SET version=version+1; END")
        }
        change("caption_search_insert", "INSERT", "captions", "SELECT NEW.asset_id WHERE 1")
        change("caption_search_update", "UPDATE", "captions", "SELECT NEW.asset_id WHERE 1")
        change("caption_search_delete", "DELETE", "captions", "SELECT OLD.asset_id WHERE 1")
        change("caption_search_visibility", "UPDATE OF deleted_at,purged_at,media_type", "assets", "SELECT NEW.id WHERE 1")
        change("caption_search_asset_delete", "DELETE", "assets", "SELECT OLD.id WHERE 1")
        // 확정 라벨 변경은 이전/새 그룹 전체에 영향을 준다. 미확정 얼굴의 합류는 해당 사진만 갱신한다.
        fun faceIds(refs: List<String>): String {
            val assets = refs.joinToString(" OR ") { "c.asset_id=$it.asset_id" }
            val clusters = refs.joinToString(" OR ") { ref ->
                val otherFace = if (ref == "NEW") " AND label.id<>NEW.id" else ""
                """(f.cluster_id=$ref.cluster_id AND $ref.person_id IS NOT NULL AND $ref.hidden=0
                    AND NOT EXISTS (SELECT 1 FROM faces label WHERE label.cluster_id=$ref.cluster_id
                    AND label.person_id=$ref.person_id AND label.hidden=0$otherFace))"""
            }
            return "SELECT c.asset_id FROM captions c WHERE $assets OR c.asset_id IN (SELECT f.asset_id FROM faces f WHERE $clusters)"
        }
        change("caption_search_face_insert", "INSERT", "faces", faceIds(listOf("NEW")))
        change("caption_search_face_update", "UPDATE OF asset_id,person_id,cluster_id,hidden", "faces", faceIds(listOf("OLD", "NEW")))
        change("caption_search_face_delete", "DELETE", "faces", faceIds(listOf("OLD")))
        val personAssets = "SELECT c.asset_id FROM captions c WHERE c.asset_id IN (SELECT f.asset_id FROM faces f WHERE f.person_id=OLD.id OR f.cluster_id IN (SELECT cluster_id FROM faces WHERE person_id=OLD.id))"
        change("caption_search_person_name", "UPDATE OF name", "persons", personAssets)
        change("caption_search_person_delete", "DELETE", "persons", personAssets)
        val format = exec("SELECT format_version FROM caption_search_state WHERE id=1") { it.next(); it.getInt(1) }!!
        if (seed || format < CaptionSceneSource.FORMAT_VERSION) {
            exec("INSERT INTO caption_search_changes(asset_id) SELECT asset_id FROM captions WHERE 1 ON CONFLICT(asset_id) DO UPDATE SET version=version+1")
            exec("UPDATE caption_search_state SET format_version=${CaptionSceneSource.FORMAT_VERSION} WHERE id=1")
        }
    }
    fun revision(): Long = transaction {
        exec("SELECT revision FROM caption_search_state WHERE id=1") { it.next(); it.getLong(1) }!!
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
    fun text(id: Long) = CaptionSceneSource.load(listOf(id))[id]?.text
}
