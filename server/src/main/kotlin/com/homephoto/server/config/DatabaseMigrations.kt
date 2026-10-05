package com.homephoto.server.config

import com.homephoto.server.db.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Component

/** 적용 이력을 같은 트랜잭션에 기록한다. 예상하지 못한 DDL 실패는 시작 실패로 드러낸다. */
@Component
class DatabaseMigrations {
    fun migrate() = transaction {
        SchemaUtils.create(Assets, Jobs, Persons, Faces, Devices, Captions, KidsnoteChildren, KidsnotePosts, KidsnotePostImages, Albums, AlbumAssets)
        exec("CREATE TABLE IF NOT EXISTS homephoto_schema_migrations (version INTEGER PRIMARY KEY)")
        val applied = exec("SELECT version FROM homephoto_schema_migrations") { rs ->
            buildSet { while (rs.next()) add(rs.getInt(1)) }
        }.orEmpty()
        if (1 !in applied) {
            addColumnIfMissing("assets", "favorite", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("assets", "device_id", "TEXT")
            addColumnIfMissing("assets", "purged_at", "TEXT")
            addColumnIfMissing("assets", "source", "TEXT")
            addColumnIfMissing("faces", "hidden", "INTEGER NOT NULL DEFAULT 0")
            exec("CREATE INDEX IF NOT EXISTS idx_assets_gps ON assets(gps_lat, gps_lon) WHERE gps_lat IS NOT NULL")
            exec("CREATE INDEX IF NOT EXISTS idx_assets_taken_at ON assets(taken_at, id)")
            exec("INSERT INTO homephoto_schema_migrations(version) VALUES (1)")
        }
        if (2 !in applied) {
            SchemaUtils.create(GooglePhotosPublications)
            exec("INSERT INTO homephoto_schema_migrations(version) VALUES (2)")
        }
        if (3 !in applied) {
            SchemaUtils.create(GooglePhotosExistingFilenames)
            exec("INSERT INTO homephoto_schema_migrations(version) VALUES (3)")
        }
        if (4 !in applied) {
            SchemaUtils.create(IncomingUploads)
            exec("INSERT INTO homephoto_schema_migrations(version) VALUES (4)")
        }
        if (5 !in applied) {
            // JVM 얼굴 워커 전환 시 사용자 요청에 따른 1회 초기화. 원본/썸네일/캡션은 보존한다.
            exec("DELETE FROM faces")
            exec("DELETE FROM persons")
            exec("DELETE FROM jobs WHERE job_type = 'FACE'")
            exec("""INSERT INTO jobs(asset_id, job_type, status, attempts, priority, updated_at)
                SELECT id, 'FACE', 'PENDING', 0, 0, strftime('%Y-%m-%dT%H:%M:%f', 'now') FROM assets
                WHERE media_type = 'PHOTO' AND deleted_at IS NULL AND purged_at IS NULL""")
            exec("INSERT INTO homephoto_schema_migrations(version) VALUES (5)")
        }
        if (6 !in applied) {
            addColumnIfMissing("albums", "story_kind", "TEXT")
            addColumnIfMissing("albums", "period_start", "TEXT")
            addColumnIfMissing("albums", "period_end", "TEXT")
            addColumnIfMissing("albums", "note", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing("albums", "revision", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing("album_assets", "position", "INTEGER NOT NULL DEFAULT 0")
            exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_albums_story_period ON albums(story_kind, period_start) WHERE story_kind IS NOT NULL")
            exec("INSERT INTO homephoto_schema_migrations(version) VALUES (6)")
        }
    }

    private fun Transaction.addColumnIfMissing(table: String, column: String, definition: String) {
        val names = exec("PRAGMA table_info($table)") { rs ->
            buildSet { while (rs.next()) add(rs.getString("name")) }
        }.orEmpty()
        if (column !in names) exec("ALTER TABLE $table ADD COLUMN $column $definition")
    }
}
