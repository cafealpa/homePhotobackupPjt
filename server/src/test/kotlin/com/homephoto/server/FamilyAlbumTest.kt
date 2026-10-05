package com.homephoto.server

import com.homephoto.server.api.*
import com.homephoto.server.config.DatabaseMigrations
import com.homephoto.server.db.*
import com.homephoto.server.service.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.*

class FamilyAlbumTest {
    @TempDir lateinit var dir: Path
    private lateinit var ds: HikariDataSource
    private lateinit var db: Database
    private val service = FamilyAlbumService(AssetQueryService())
    @BeforeEach fun setup() {
        ds = HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${dir.resolve("test.db")}"; maximumPoolSize = 1 })
        db = Database.connect(ds)
        DatabaseMigrations().migrate()
    }
    @AfterEach fun close() { TransactionManager.closeAndUnregister(db); ds.close() }
    private fun photo(name: String, day: String, device: String? = "a", type: String = "PHOTO", deleted: Boolean = false, source: String? = null): Long = transaction {
        Assets.insert {
            it[hash] = name; it[mediaType] = type; it[originalPath] = name; it[originalFilename] = name
            it[fileSize] = 1; it[takenAt] = "${day}T12:00:00"; it[takenAtSource] = "EXIF"
            it[yearMonth] = day.take(7); it[createdAt] = "${day}T12:00:00"; it[deviceId] = device
            it[sourceTag] = source; if (deleted) it[deletedAt] = day
        }[Assets.id]
    }
    @Test fun `weekly boundaries and known devices exclude hidden media`() {
        val first = photo("mon", "2026-10-05")
        val last = photo("sun", "2026-10-11", "b")
        photo("previous", "2026-10-04"); photo("next", "2026-10-12")
        photo("video", "2026-10-05", "b", "VIDEO")
        photo("trash", "2026-10-05", "b", deleted = true)
        photo("kids", "2026-10-05", "b", source = "KIDSNOTE")
        val unknown = photo("unknown", "2026-10-06", null)
        val known = photo("known", "2026-10-06")
        assertEquals(setOf(first, last, unknown, known), service.detail("WEEKLY", "2026-10-07").selected.map { it.id }.toSet())
        assertTrue(service.home(LocalDate.parse("2026-10-11")).together.isEmpty())
        photo("other-device", "2026-10-06", "b")
        assertEquals(listOf("2026-10-06"), service.home(LocalDate.parse("2026-10-11")).together.map { it.date })
    }
    @Test fun `saved order note and exclusions survive late backup with conflicts detected`() {
        val a = photo("a", "2026-10-05"); val b = photo("b", "2026-10-05", "b")
        val saved = service.save("WEEKLY", "2026-10-06", SaveFamilyAlbum("우리 주말", "산책했어요", listOf(b, a)))
        photo("late", "2026-10-05")
        val read = service.detail("WEEKLY", "2026-10-05")
        assertEquals(listOf(b, a), read.selected.map { it.id }); assertEquals("산책했어요", read.note)
        assertEquals(3, read.summary.photoCount); assertEquals(1, read.revision)
        assertEquals(409, assertFailsWith<ResponseStatusException> {
            service.save("WEEKLY", "2026-10-05", SaveFamilyAlbum("stale", assetIds = listOf(a)))
        }.statusCode.value())
        AlbumController().rename(saved.summary.albumId!!, RenameAlbumRequest("웹에서 변경"))
        assertEquals(2, service.detail("WEEKLY", "2026-10-05").revision)
        assertFailsWith<ResponseStatusException> { service.save("WEEKLY", "2026-10-05", SaveFamilyAlbum("stale", assetIds = listOf(a), revision = 1)) }
    }
    @Test fun `invalid selection is atomic and candidates paginate without repeats`() {
        val ids = (1..65).map { photo("p$it", "2026-10-05") }
        val outside = photo("outside", "2026-10-04")
        assertFailsWith<IllegalArgumentException> { service.save("WEEKLY", "2026-10-05", SaveFamilyAlbum("bad", assetIds = listOf(outside))) }
        assertFailsWith<IllegalArgumentException> { service.save("WEEKLY", "2026-10-05", SaveFamilyAlbum("bad", assetIds = listOf(ids[0], ids[0]))) }
        assertNull(service.detail("WEEKLY", "2026-10-05").summary.albumId)
        val page = service.candidates("WEEKLY", "2026-10-05", null)
        val next = service.candidates("WEEKLY", "2026-10-05", page.nextCursor)
        assertEquals(ids.toSet(), (page.items + next.items).map { it.id }.toSet())
        assertEquals(65, page.items.size + next.items.size)
        assertFailsWith<IllegalArgumentException> { service.detail("bad", "2026-10-05") }
        assertFailsWith<IllegalArgumentException> { service.detail("WEEKLY", "nonsense") }
    }
    @Test fun `migration is idempotent and preserves manual albums`() {
        val old = AlbumController().create(CreateAlbumRequest("기존 앨범"))
        DatabaseMigrations().migrate(); DatabaseMigrations().migrate()
        assertEquals(old.name, AlbumController().list().single().name)
        transaction { assertEquals(0, Albums.selectAll().single()[Albums.revision]) }
    }
    @Test fun `upgrade from old album tables preserves linked photos`() {
        val id = photo("legacy", "2026-10-05")
        transaction {
            exec("DROP TABLE album_assets")
            exec("DROP TABLE albums")
            exec("DELETE FROM homephoto_schema_migrations WHERE version = 6")
            exec("CREATE TABLE albums (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, cover_asset_id BIGINT, created_at TEXT NOT NULL)")
            exec("CREATE TABLE album_assets (id INTEGER PRIMARY KEY AUTOINCREMENT, album_id BIGINT NOT NULL, asset_id BIGINT NOT NULL, added_at TEXT NOT NULL, UNIQUE(album_id, asset_id))")
            exec("INSERT INTO albums(id, name, created_at) VALUES (1, 'legacy', '2026-10-05')")
            exec("INSERT INTO album_assets(album_id, asset_id, added_at) VALUES (1, $id, '2026-10-05')")
        }
        DatabaseMigrations().migrate()
        val album = AlbumController().list().single()
        assertEquals("legacy", album.name); assertEquals(1L, album.count)
        transaction {
            assertNull(Albums.selectAll().single()[Albums.storyKind])
            assertEquals(0, AlbumAssets.selectAll().single()[AlbumAssets.position])
        }
    }
    @Test fun `recommendations balance days and devices and prefer favorites within coverage`() {
        val rows = (0..6).flatMap { day -> (1..2).map { device ->
            FamilyPhoto(day * 2L + device, "2026-10-${day + 10}T12:00:00", "$device", device == 2)
        } }
        val ids = FamilyAlbumService.recommend(rows)
        val chosen = rows.filter { it.id in ids }
        assertEquals(8, ids.size)
        assertEquals(7, chosen.map { it.takenAt.take(10) }.distinct().size)
        assertTrue(chosen.groupBy { it.deviceId }.values.all { it.size in 3..5 })
        assertEquals(2L, ids.first())
    }

}
