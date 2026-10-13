package com.homephoto.server.search

import com.homephoto.server.config.*
import com.homephoto.server.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CaptionPeopleIndexTest {
    @TempDir lateinit var root: Path
    private lateinit var db: Database
    private val vector = FloatArray(384).apply { this[0] = 1f }
    private val services = mutableListOf<CaptionTextSearch>()
    @BeforeEach fun setup() {
        db = Database.connect("jdbc:sqlite:${root.resolve("test.db")}", driver = "org.sqlite.JDBC")
        DatabaseMigrations().migrate()
    }
    @AfterEach fun cleanup() { services.forEach { it.close() }; TransactionManager.closeAndUnregister(db) }
    private fun asset(id: Long, caption: Boolean = true) = transaction {
        Assets.insert {
            it[Assets.id] = id; it[hash] = "photo-$id"; it[mediaType] = "PHOTO"; it[originalPath] = "unused"; it[originalFilename] = "$id.jpg"
            it[fileSize] = 1; it[takenAtSource] = "UPLOAD_TIME"; it[yearMonth] = "2026-10"; it[createdAt] = "2026-10-13"
        }
        if (caption) Captions.insert {
            it[assetId] = id; it[Captions.caption] = "어린이용 자전거에 탄 어린이가 핸들을 잡고 있다."
            it[tags] = "자전거,산책로"; it[createdAt] = "2026-10-13"
        }
    }
    private fun person(id: Long, name: String) = transaction { Persons.insert { it[Persons.id] = id; it[Persons.name] = name } }
    private fun face(id: Long, asset: Long, group: Int?, person: Long? = null, hidden: Boolean = false) = transaction {
        Faces.insert {
            it[Faces.id] = id; it[assetId] = asset; it[clusterId] = group; it[personId] = person; it[Faces.hidden] = hidden
            it[bboxX] = 0.1; it[bboxY] = 0.1; it[bboxW] = 0.2; it[bboxH] = 0.2; it[embedding] = ExposedBlob(ByteArray(2048))
        }
    }
    private fun pending() = CaptionIndexQueue.pending(100)
    private fun clear() = CaptionIndexQueue.ack(pending())
    private fun source(id: Long) = CaptionSceneSource.load(listOf(id)).getValue(id)
    private fun matches(word: String) = transaction {
        Assets.select(Assets.id).where { CaptionSceneSource.personMatches(word) }.map { it[Assets.id] }.toSet()
    }
    @Test fun `source joins confirmed and unambiguous group names without changing face labels`() {
        asset(1); asset(2); person(1, "승연"); person(2, "다은")
        face(1, 1, 10, 1); face(2, 1, 10, 1); face(3, 1, 20, 2, hidden = true); face(4, 2, 10)
        val first = source(1)
        assertEquals("등장인물: 승연\n장면: 어린이용 자전거에 탄 어린이가 핸들을 잡고 있다.\n태그: 자전거 산책로", first.text)
        assertEquals(listOf(1L), first.personIds); assertEquals(listOf(10), first.clusterIds)
        assertEquals(first, source(2))
        assertEquals(setOf(1L, 2L), matches("승연")); assertTrue(matches("다은").isEmpty())
        transaction { assertNull(Faces.selectAll().where { Faces.id eq 4L }.single()[Faces.personId]) }
        face(5, 1, 10, 2) // conflicting labels must not name an unconfirmed member
        assertTrue(source(2).personIds.isEmpty()); assertFalse(source(2).text.contains("등장인물"))
        assertEquals(setOf(1L), matches("승연")); assertEquals(setOf(1L), matches("다은"))
    }
    @Test fun `rename regroup hide and delete fan out to dependent photos and preserve concurrent changes`() {
        asset(1); asset(2); asset(3); person(1, "승연"); person(2, "다은")
        face(1, 1, 10, 1); face(2, 2, 10); face(3, 3, 20, 2)
        clear(); val before = CaptionIndexQueue.revision(); val oldHash = source(2).fingerprint
        transaction { Persons.update({ Persons.id eq 1L }) { it[name] = "승연이" } }
        assertEquals(setOf(1L, 2L), pending().map { it.id }.toSet()); assertTrue(CaptionIndexQueue.revision() > before)
        assertNotEquals(oldHash, source(2).fingerprint)
        val inFlight = pending()
        transaction { Faces.update({ Faces.id eq 2L }) { it[clusterId] = 20 } }
        CaptionIndexQueue.ack(inFlight)
        assertEquals(setOf(2L), pending().map { it.id }.toSet())
        assertEquals(listOf(2L), source(2).personIds)
        clear()
        transaction { Faces.update({ Faces.id eq 3L }) { it[hidden] = true } }
        assertEquals(setOf(2L, 3L), pending().map { it.id }.toSet()); assertTrue(source(2).personIds.isEmpty())
        clear()
        transaction { Faces.deleteWhere { Faces.id eq 2L } }
        assertEquals(setOf(2L), pending().map { it.id }.toSet()); assertTrue(source(2).clusterIds.isEmpty())
    }
    @Test fun `repeated labels do not repeatedly enqueue an entire group but last removal does`() {
        asset(1); asset(2); asset(3); person(1, "승연")
        face(1, 1, 10, 1); face(2, 2, 10); face(3, 3, 10); clear()
        transaction { Faces.update({ Faces.id eq 2L }) { it[personId] = 1 } }
        assertEquals(listOf(2L), pending().map { it.id }); clear()
        transaction { Faces.update({ Faces.id eq 1L }) { it[personId] = null } }
        assertEquals(listOf(1L), pending().map { it.id }); clear()
        assertEquals(listOf(1L), source(3).personIds)
        transaction { Faces.update({ Faces.id eq 2L }) { it[personId] = null } }
        assertEquals(setOf(1L, 2L, 3L), pending().map { it.id }.toSet())
        assertTrue(source(3).personIds.isEmpty())
    }

    @Test fun `format upgrade seeds old captions once and pending work survives reinitialization`() {
        asset(1); asset(2); clear()
        transaction { exec("UPDATE caption_search_state SET format_version=0") }
        CaptionIndexQueue.initialize(false)
        assertEquals(setOf(1L, 2L), pending().map { it.id }.toSet())
        val queued = pending(); CaptionIndexQueue.initialize(false)
        assertEquals(queued, pending())
        clear(); DatabaseMigrations().migrate(); CaptionIndexQueue.initialize(false)
        assertTrue(pending().isEmpty())
        CaptionIndexQueue.initialize(true) // deleted/moved Lucene index, same SQLite
        assertEquals(setOf(1L, 2L), pending().map { it.id }.toSet())
    }
    @Test fun `face first caption first and visibility changes all converge on current source`() {
        asset(1, caption = false); asset(2); person(1, "승연"); face(1, 1, 10, 1)
        clear(); face(2, 2, 10)
        assertEquals(listOf(2L), pending().map { it.id })
        transaction { Captions.insert { it[assetId] = 1L; it[caption] = "자전거"; it[createdAt] = "2026-10-13" } }
        assertEquals(listOf(1L), source(1).personIds)
        clear()
        transaction { Assets.update({ Assets.id eq 2L }) { it[deletedAt] = "2026-10-13" } }
        assertNull(CaptionSceneSource.load(listOf(2))[2]); assertEquals(listOf(2L), pending().map { it.id })
        transaction { Assets.update({ Assets.id eq 2L }) { it[deletedAt] = null } }
        assertEquals(listOf(1L), source(2).personIds)
    }
    private fun service(encoder: CaptionTextEncoder): CaptionTextSearch {
        val model = root.resolve("model"); Files.createDirectories(model)
        CaptionTextEncoder.HASHES.keys.forEach { Files.writeString(model.resolve(it), "mock model") }
        return CaptionTextSearch(CaptionTextSearchProperties(modelDir = model.toString()), AppProperties(root, "test")).apply {
            encoderFactory = { encoder }; services.add(this)
        }
    }
    private fun run(service: CaptionTextSearch) {
        service.tick()
        val deadline = System.nanoTime() + 10_000_000_000L
        while ((service.status().state != "ready" || pending().isNotEmpty()) && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals("ready", service.status().state); assertTrue(pending().isEmpty())
    }
    @Test fun `worker replaces old scene vectors persists identities and preserves shared document index`() {
        asset(1); person(1, "승연"); face(1, 1, 10, 1)
        val indexPath = root.resolve("caption-search").resolve(CaptionTextEncoder.ID)
        CaptionVectorIndex(indexPath).use {
            it.put(1, "legacy-caption-only", vector)
            it.replaceDocument(99, 1, listOf(vector)); it.commit()
        }
        val encoder = mock(CaptionTextEncoder::class.java)
        `when`(encoder.encode(anyString(), anyBoolean())).thenReturn(vector)
        val service = service(encoder); run(service)
        verify(encoder).encode(source(1).text, false)
        assertEquals(listOf(1L), service.captionCandidates("승연이가 자전거 타는 사진"))
        transaction { Persons.update({ Persons.id eq 1L }) { it[name] = "다은" } }
        assertTrue(service.captionCandidates("승연이가 자전거 타는 사진").isEmpty()) // stale fingerprint rejected before reindex
        run(service); service.close(); services.remove(service)
        CaptionVectorIndex(indexPath).use {
            assertEquals(1, it.documentCount()); assertEquals(1, it.count())
            val hit = it.search(vector).single()
            assertEquals(source(1).fingerprint, hit.fingerprint)
            assertEquals(listOf(1L), hit.personIds); assertEquals(listOf(10), hit.clusterIds)
            assertEquals(99L, it.searchDocuments(vector).single().id)
        }
    }
}
