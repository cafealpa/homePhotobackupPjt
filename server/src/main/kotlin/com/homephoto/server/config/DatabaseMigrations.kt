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
    }

    private fun Transaction.addColumnIfMissing(table: String, column: String, definition: String) {
        val names = exec("PRAGMA table_info($table)") { rs ->
            buildSet { while (rs.next()) add(rs.getString("name")) }
        }.orEmpty()
        if (column !in names) exec("ALTER TABLE $table ADD COLUMN $column $definition")
    }
}
