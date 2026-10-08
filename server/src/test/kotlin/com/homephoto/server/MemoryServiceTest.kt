package com.homephoto.server

import com.homephoto.server.api.AlbumController
import com.homephoto.server.api.CreateAlbumRequest
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
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.*

class MemoryServiceTest {
    @TempDir lateinit var dir: Path
    private lateinit var ds: HikariDataSource
    private lateinit var db: Database
    private val service = MemoryService()
    private val today = LocalDate.parse("2026-10-08")
    @BeforeEach fun setup() {
        ds = HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${dir.resolve("test.db")}"; maximumPoolSize = 1 })
        db = Database.connect(ds); DatabaseMigrations().migrate()
    }
    @AfterEach fun close() { TransactionManager.closeAndUnregister(db); ds.close() }
    private fun photo(day: String, minute: Int = 0, source: String = "EXIF", favorite: Boolean = false): Long = transaction {
        val name = java.util.UUID.randomUUID().toString()
        Assets.insert {
            it[hash] = name; it[mediaType] = "PHOTO"; it[originalPath] = name; it[originalFilename] = name
            it[fileSize] = 1; it[takenAt] = "${day}T12:${minute.toString().padStart(2,'0')}:00"; it[takenAtSource] = source
            it[yearMonth] = day.take(7); it[createdAt] = "${day}T12:00:00"; it[deviceId] = "phone"; it[Assets.favorite] = favorite
        }[Assets.id]
    }
    private fun day(value: String) = (0..5).map { photo(value, it * 10) }

    @Test fun `daily snapshots survive service recreation and late uploads`() {
        day("2025-10-07"); day("2022-05-14"); day("2026-10-08")
        val first = service.home(today)
        assertEquals(3, first.memories.size)
        val ids = first.memories.flatMap { service.detail(it.id).photos.map { p -> p.id } }
        assertEquals(ids.size, ids.distinct().size)
        photo("2025-10-07", favorite = true)
        assertEquals(first, MemoryService().home(today))
        assertEquals(ids, first.memories.flatMap { MemoryService().detail(it.id).photos.map { p -> p.id } })
    }
    @Test fun `deleted documents videos uncertain dates and kidsnote are not historical candidates`() {
        val ids = day("2025-10-08")
        val invalid = listOf(photo("2025-10-08"), photo("2025-10-08"), photo("2025-10-08"), photo("2025-10-08", source = "UPLOAD_TIME"), photo("2025-10-08"))
        transaction {
            Assets.update({ Assets.id eq invalid[0] }) { it[deletedAt] = "now" }
            exec("INSERT INTO document_analysis(asset_id,classification) VALUES(${invalid[1]},'DOCUMENT')")
            Assets.update({ Assets.id eq invalid[2] }) { it[mediaType] = "VIDEO" }
            Assets.update({ Assets.id eq invalid[4] }) { it[sourceTag] = "KIDSNOTE" }
        }
        val memory = service.home(today).memories.single()
        assertEquals(ids.toSet(), service.detail(memory.id).photos.map { it.id }.toSet())
        transaction {
            Assets.update({ Assets.id eq ids[0] }) { it[deletedAt] = "now" }
            exec("INSERT INTO document_analysis(asset_id,classification) VALUES(${ids[1]},'DOCUMENT')")
        }
        assertEquals(4, service.detail(memory.id).photos.size)
        assertEquals(4, service.home(today).memories.single().photoCount)
    }
    @Test fun `save is ordered atomic idempotent and independent of future recommendations`() {
        day("2022-05-14")
        val legacy = AlbumController().create(CreateAlbumRequest("기존 앨범"))
        val memory = service.home(today).memories.single()
        val ids = service.detail(memory.id).photos.map { it.id }.reversed().take(3)
        assertFailsWith<IllegalArgumentException> { service.save(memory.id, SaveMemoryAlbum("실패", ids + 99999)) }
        assertEquals(1, AlbumController().list().size)
        val saved = service.save(memory.id, SaveMemoryAlbum("우리 하루", ids))
        assertEquals(ids, saved.photos.map { it.id })
        assertEquals(ids.first(), saved.summary.coverAssetId)
        assertEquals(saved, service.save(memory.id, SaveMemoryAlbum("재시도", ids)))
        service.home(today.plusDays(1))
        assertEquals(ids, service.savedAlbum(saved.summary.albumId!!).photos.map { it.id })
        assertEquals("우리 하루", service.home(today).saved.single().title)
        assertEquals(ids.first(), service.home(today).saved.single().coverAssetId)
        assertEquals(2, AlbumController().list().size)
        assertNotNull(AlbumController().list().find { it.id == legacy.id })
        // New albums must not enter FamilyAlbumService's WEEKLY/TOGETHER parser.
        assertTrue(FamilyAlbumService(AssetQueryService()).home(today).saved.isEmpty())
        DatabaseMigrations().migrate()
        assertEquals(saved, service.savedAlbum(saved.summary.albumId!!))
    }
    @Test fun `recommendation cooldown prefers another old day`() {
        day("2022-05-14"); day("2023-06-01")
        val first = service.home(today).memories.single().startDate
        val second = service.home(today.plusDays(1)).memories.single().startDate
        assertNotEquals(first, second)
        assertEquals(first, service.home(today.plusDays(2)).memories.single().startDate)
    }
    @Test fun `recent added fallback and empty day are stable`() {
        assertTrue(service.home(today).memories.isEmpty())
        photo("2026-10-08", source = "UPLOAD_TIME")
        assertTrue(service.home(today).memories.isEmpty())
        assertEquals("최근 추가한 사진", service.home(today.plusDays(1)).memories.single().title)
    }
    @Test fun `validated filename dates follow the existing capture date contract`() {
        repeat(4) { photo("2025-10-08", source = "FILENAME") }
        repeat(4) { photo("2024-10-08", source = "FILE_MTIME") }
        val memory = service.home(today).memories.single()
        assertEquals("1년 전 이맘때", memory.title)
        assertTrue(service.detail(memory.id).photos.all { it.takenAtSource == "FILENAME" })
    }
    @Test fun `selection covers days and time buckets before filling a burst`() {
        repeat(20) { photo("2026-10-08", it % 10, favorite = true) }
        val otherBucket = photo("2026-10-08", 45)
        val otherDay = photo("2026-10-07")
        val memory = service.home(today).memories.single()
        val selected = service.detail(memory.id).photos
        assertEquals(12, selected.size)
        assertTrue(selected.any { it.id == otherBucket })
        assertTrue(selected.any { it.id == otherDay })
        assertEquals(selected.sortedBy { it.takenAt }, selected)
    }
    @Test fun `anniversary handles leap days and year boundaries`() {
        assertEquals(0, MemoryService.anniversaryDistance(LocalDate.parse("2024-02-29"), LocalDate.parse("2023-02-28")))
        assertEquals(1, MemoryService.anniversaryDistance(LocalDate.parse("2026-01-01"), LocalDate.parse("2023-12-31")))
        day("2023-12-31")
        assertEquals("2년 전 이맘때", service.home(LocalDate.parse("2026-01-01")).memories.single().title)
    }
}
