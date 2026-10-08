package com.homephoto.server.search

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.ApiKeyFilter
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Faces
import com.homephoto.server.db.Persons
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import org.springframework.web.server.ResponseStatusException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class FaceVectorSearchTest {
    private val mapper = ObjectMapper()
    private val bytes = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).array()
    private val root = Files.createTempDirectory("homephoto-faces-test-")
    private lateinit var db: Database
    private lateinit var index: LocalVectorIndex
    private lateinit var backend: SearchBackend
    private lateinit var service: FaceVectorSearch

    @BeforeEach fun setup() {
        db = Database.connect("jdbc:sqlite:${root.resolve("test.db")}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Assets, Persons, Faces)
            for (person in 1L..2L) Persons.insert { it[id] = person; it[name] = "인물 $person" }
            for (asset in 1L..4L) Assets.insert {
                it[id] = asset; it[hash] = "hash-$asset"; it[mediaType] = "PHOTO"
                it[originalPath] = "private.jpg"; it[originalFilename] = "private.jpg"; it[fileSize] = 1
                it[takenAt] = "2026-09-01T12:00:00"; it[takenAtSource] = "EXIF"
                it[yearMonth] = "2026-09"; it[createdAt] = "2026-09-01T12:00:00"
                it[deletedAt] = if (asset == 4L) "2026-09-02T12:00:00" else null
            }
            for ((face, asset) in listOf(11L to 1L, 12L to 1L, 21L to 2L, 22L to 2L, 23L to 3L, 31L to 3L, 41L to 4L)) {
                Faces.insert {
                    it[id] = face; it[assetId] = asset; it[embedding] = ExposedBlob(bytes)
                    it[bboxX] = if (face % 10 == 2L) .5 else .1; it[bboxY] = .1; it[bboxW] = .2; it[bboxH] = .3
                    it[personId] = if (face % 10 == 2L) 2L else 1L; it[clusterId] = (face % 10).toInt()
                    it[hidden] = face == 31L
                }
            }
        }
        index = LocalVectorIndex(root.resolve("index"), 512)
        for (id in listOf(11L, 21L, 22L, 23L, 31L, 41L, 99L)) {
            val asset = if (id == 23L) 3L else id / 10
            index.put(id, asset, if (id == 23L) "stale" else faceFingerprint(bytes), 0, faceVector(bytes))
        }
        index.commit()
        backend = object : SearchBackend {
            override fun photos(text: String, range: com.homephoto.server.service.PhotoDateRange?) = error("unused")
            override fun faces(embedding: ByteArray, sourceAsset: Long) =
                SearchBackend.Result(index.search(faceVector(embedding), excludeAsset = sourceAsset), index.count())
        }
        service = FaceVectorSearch(PhotoSearchProperties(true), backend)
    }

    @Test fun `multiple faces from one photo remain separate and candidates use current person names`() {
        assertEquals(listOf(11L, 12L), service.faces(1).map { it.faceId })
        transaction(db) { Persons.update({ Persons.id eq 2L }) { it[name] = "이민아" } }
        val found = service.similar(11, 20, .55)
        assertEquals(listOf(21L, 22L), found.matches.map { it.face.faceId })
        assertEquals(listOf(2L, 2L), found.matches.map { it.face.assetId })
        assertEquals("이민아", found.people.first { it.personId == 2L }.name)
        assertEquals(1L, found.source.personId)
        // 조회가 인물 이름이나 연결을 수정하지 않는다.
        assertEquals(1L, transaction(db) { Faces.selectAll().where { Faces.id eq 11L }.single()[Faces.personId] })
        transaction(db) { Faces.deleteWhere { id eq 21L } }
        assertEquals(listOf(22L), service.similar(11, 20, .55).matches.map { it.face.faceId })
    }

    @Test fun `API protects face vectors and preserves per-face geometry`() {
        val mvc = MockMvcBuilders.standaloneSetup(FaceSearchController(service))
            .addFilters<StandaloneMockMvcBuilder>(ApiKeyFilter(AppProperties(root, "test-api"))).build()
        assertEquals(401, mvc.perform(get("/api/v1/internal/search/faces")).andReturn().response.status)
        assertEquals(401, mvc.perform(get("/api/v1/faces/11/similar")).andReturn().response.status)
        val catalog = mvc.perform(get("/api/v1/internal/search/faces").header("X-Api-Key", "test-api")).andReturn().response
        val rows = mapper.readTree(catalog.contentAsString)["items"]
        assertEquals(2, rows.count { it["asset_id"].asLong() == 1L })
        assertFalse(rows.first { it["face_id"].asLong() == 31L }["active"].asBoolean())
        assertTrue(rows.first { it["face_id"].asLong() == 31L }["embedding"].isNull)
        val list = mvc.perform(get("/api/v1/assets/1/faces").header("X-Api-Key", "test-api")).andReturn().response
        val faces = mapper.readTree(list.contentAsString)
        assertEquals(.1, faces[0]["x"].asDouble())
        assertEquals(.5, faces[1]["x"].asDouble())
        assertFalse(faces[0].has("embedding"))
        assertEquals(400, mvc.perform(get("/api/v1/faces/11/similar?limit=0").header("X-Api-Key", "test-api")).andReturn().response.status)
    }

    @Test fun `hidden sources and invalid thresholds are rejected`() {
        assertEquals(404, assertThrows(ResponseStatusException::class.java) { service.similar(31, 20, .55) }.statusCode.value())
        assertThrows(IllegalArgumentException::class.java) { service.similar(11, 20, Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { service.similar(11, 51, .55) }
        val disabled = FaceVectorSearch(PhotoSearchProperties(), backend)
        assertThrows(PhotoSearchUnavailable::class.java) { disabled.similar(11, 20, .55) }
    }

    @AfterEach fun cleanup() {
        index.close()
        TransactionManager.closeAndUnregister(db)
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
