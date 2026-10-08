package com.homephoto.server.search

import com.homephoto.server.config.ApiKeyFilter
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Faces
import com.homephoto.server.db.Persons
import com.homephoto.server.service.ThumbnailService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class PhotoSearchProcessTest {
    @TempDir lateinit var root: Path
    private lateinit var db: Database
    private lateinit var service: PhotoSearchProcess
    private lateinit var thumbnail: Path
    private val thumbnails = Mockito.mock(ThumbnailService::class.java)
    private val activity = com.homephoto.server.service.ServerActivity()
    private val vector = FloatArray(768).also { it[0] = 1f }
    private var encoded = 0
    private var preparations = 0
    private val face = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).array()
    private val app get() = AppProperties(root, "test-api")

    @BeforeEach fun setup() {
        db = Database.connect("jdbc:sqlite:${root.resolve("test.db")}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Assets, Persons, Faces)
            for (n in 1L..3L) {
                Assets.insert {
                    it[id] = n; it[hash] = "hash-$n"; it[mediaType] = "PHOTO"
                    it[originalPath] = "unused.jpg"; it[originalFilename] = "unused.jpg"; it[fileSize] = 1
                    it[takenAt] = "2026-10-01T12:00:00"; it[takenAtSource] = "EXIF"
                    it[yearMonth] = "2026-10"; it[createdAt] = "2026-10-01T12:00:00"
                }
                Faces.insert {
                    it[id] = n; it[assetId] = n; it[embedding] = ExposedBlob(face)
                    it[bboxX] = .1; it[bboxY] = .1; it[bboxW] = .2; it[bboxH] = .2
                }
            }
        }
        thumbnail = root.resolve("image.png")
        ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", thumbnail.toFile())
        Mockito.`when`(thumbnails.thumbPath(Mockito.anyString(), Mockito.eq(400))).thenReturn(thumbnail)
        service = newService()
    }
    private fun newService(enabled: Boolean = true) = PhotoSearchProcess(PhotoSearchProperties(enabled, root.resolve("model").toString()), app, thumbnails, activity).apply {
        encoderFactory = { object : PhotoEncoder {
            override fun prepare() { preparations++ }
            override fun text(text: String) = vector
            override fun image(image: BufferedImage): FloatArray { encoded++; return vector }
            override fun close() {}
        } }
    }
    private fun install() {
        Files.createDirectories(root.resolve("model"))
        SiglipEncoder.HASHES.keys.forEach { Files.writeString(root.resolve("model").resolve(it), "test fixture") }
    }
    @Test fun `faces work without photo model and stale hidden deleted vectors are removed`() {
        service.scan()
        assertEquals("model_missing", service.status().state)
        assertEquals(3, service.status().indexedFaces)
        assertEquals(setOf(2L, 3L), service.faces(face, 1).hits.map { it.id }.toSet())
        assertThrows(PhotoSearchUnavailable::class.java) { service.photos("사진", null) }
        transaction(db) {
            Faces.update({ Faces.id eq 2L }) { it[hidden] = true }
            Faces.deleteWhere { id eq 3L }
        }
        service.scan()
        assertEquals(1L, service.status().indexedFaces)
        assertTrue(service.faces(face, 1).hits.isEmpty())
    }
    @Test fun `photo hash and day changes reuse vectors failures retry and restart reopens index`() {
        install()
        service.scan()
        assertEquals("ready", service.status().state)
        assertEquals(3, encoded)
        transaction(db) { Assets.update({ Assets.id eq 2L }) { it[takenAt] = "2026-10-02T00:00:00" } }
        service.scan()
        assertEquals(3, encoded)
        val date = com.homephoto.server.service.PhotoDateRange.parse(mapOf("date" to "2026-10-02"))
        assertEquals(2L, service.photos("사진", date).hits.single().id)
        transaction(db) { Assets.update({ Assets.id eq 1L }) { it[hash] = "changed" } }
        Files.delete(thumbnail)
        service.scan()
        assertEquals("partial", service.status().state)
        assertEquals(1L, service.status().failed)
        org.junit.jupiter.api.Assertions.assertNotNull(service.status().lastError)
        // Current DB hash prevents stale vector from leaking into the API.
        assertFalse(PhotoSemanticSearch(PhotoSearchProperties(true), service).search("사진", null, 12).items.any { it.id == 1L })
        ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", thumbnail.toFile())
        service.scan()
        assertEquals("ready", service.status().state)
        assertEquals(4, encoded)
        service.shutdown()
        service = newService()
        service.scan()
        assertEquals(4, encoded)
        assertEquals(3L, service.status().indexedPhotos)
    }
    @Test fun `invalid face vector is excluded and reported without blocking other faces`() {
        transaction(db) { Faces.update({ Faces.id eq 2L }) { it[embedding] = ExposedBlob(ByteArray(2048)) } }
        service.scan()
        assertEquals(2L, service.status().indexedFaces)
        assertEquals(1L, service.status().failed)
        assertTrue(service.status().lastError!!.contains("얼굴 #2"))
    }
    @Test fun `start deduplicates and stop during prepare waits before resources are reused`() {
        install()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.encoderFactory = { object : PhotoEncoder {
            override fun prepare() { preparations++; entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            override fun text(text: String) = vector
            override fun image(image: BufferedImage) = vector
            override fun close() {}
        } }
        try {
            service.start()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals(1, activity.count())
            service.start()
            assertEquals(1, preparations)
            assertEquals("stopping", service.stop().state)
            assertFalse(service.status().canStart)
        } finally { release.countDown() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (service.status().state == "stopping" && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("stopped", service.status().state)
        assertEquals(0, activity.count())
        assertTrue(service.status().canStart)
        assertEquals(3L, service.faces(face, 9).indexed)
    }
    @Test fun `pausing commits the partial batch and a drained server admits no new scan`() {
        install()
        service.encoderFactory = { object : PhotoEncoder {
            override fun prepare() {}
            override fun text(text: String) = vector
            override fun image(image: BufferedImage): FloatArray { service.stop(); return vector }
            override fun close() {}
        } }
        service.scan()
        assertEquals("stopped", service.status().state)
        assertEquals(1L, service.status().indexedPhotos)
        assertEquals(1, service.photos("사진", null).hits.size)
        activity.begin()
        service.start()
        assertEquals(0, activity.count())
        assertEquals(1L, service.status().indexedPhotos)
    }
    @Test fun `disabled mode and admin authentication remain enforced`() {
        service.shutdown()
        service = newService(false)
        assertEquals("disabled", service.status().state)
        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) { service.start() }
        val mvc = MockMvcBuilders.standaloneSetup(PhotoSearchProcessController(service))
            .addFilters<StandaloneMockMvcBuilder>(ApiKeyFilter(app)).build()
        assertEquals(401, mvc.perform(get("/api/v1/admin/search-service")).andReturn().response.status)
        assertEquals(401, mvc.perform(post("/api/v1/admin/search-service/start").header("X-HomePhoto-Action", "search-service")).andReturn().response.status)
        assertTrue(mvc.perform(post("/api/v1/admin/search-service/start").header("X-Api-Key", app.apiKey)).andReturn().response.status in 400..499)
        assertEquals(200, mvc.perform(get("/api/v1/admin/search-service").header("X-Api-Key", app.apiKey)).andReturn().response.status)
    }
    @AfterEach fun cleanup() { service.shutdown(); TransactionManager.closeAndUnregister(db) }
}
