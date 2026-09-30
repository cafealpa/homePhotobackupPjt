package com.homephoto.server.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.api.*
import com.homephoto.server.config.*
import com.homephoto.server.db.*
import com.homephoto.server.service.*
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.sql.DataSource
import kotlin.test.*

@SpringBootTest(classes = [AssetStorageTestConfiguration::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.config.location=classpath:/application.yml", "logging.file.name=", "logging.level.com.homephoto=WARN"])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssetStorageHttpTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var props: AppProperties
    @Autowired private lateinit var db: Database
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var thumbnails: ThumbnailService
    @Autowired private lateinit var imports: ImportService
    private val http = HttpClient.newHttpClient()
    private val jpeg = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpg", it)
    }.toByteArray()

    @BeforeEach fun resetDatabase() {
        TransactionManager.defaultDatabase = db
        transaction(db) {
            Jobs.deleteAll(); Faces.deleteAll(); Captions.deleteAll(); Assets.deleteAll()
        }
    }

    private fun request(method: String, path: String, bytes: ByteArray? = null, contentType: String? = null,
                        range: String? = null, authenticated: Boolean = true): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(java.time.Duration.ofSeconds(10))
        if (authenticated) builder.header("X-Api-Key", "storage-http-test-key")
        contentType?.let { builder.header("Content-Type", it) }
        range?.let { builder.header("Range", it) }
        return http.send(builder.method(method, bytes?.let(HttpRequest.BodyPublishers::ofByteArray)
            ?: HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun upload(bytes: ByteArray = jpeg, filename: String = "IMG_20240102_030405.jpg", hash: String? = null): HttpResponse<ByteArray> {
        val boundary = "HomePhotoStorageTestBoundary"
        val body = ByteArrayOutputStream()
        fun text(value: String) { body.write(value.toByteArray()) }
        text("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n")
        body.write(bytes)
        text("\r\n")
        for ((name, value) in mapOf("fileMtime" to "1700000000000", "hash" to hash).filterValues { it != null }) {
            text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }
        text("--$boundary--\r\n")
        return request("POST", "/api/v1/assets", body.toByteArray(), "multipart/form-data; boundary=$boundary")
    }

    private fun create(bytes: ByteArray = jpeg, filename: String = "IMG_20240102_030405.jpg"): Long {
        val response = upload(bytes, filename)
        assertEquals(201, response.statusCode(), response.body().decodeToString())
        return mapper.readTree(response.body())["id"].asLong()
    }

    private fun row(id: Long) = transaction(db) { Assets.selectAll().where { Assets.id eq id }.single() }
    private fun original(id: Long) = archive.resolve(row(id)[Assets.originalPath])

    @Test fun `multipart upload preserves status bytes metadata and local staging with a separate archive`() {
        val id = create()
        val stored = row(id)
        assertContentEquals(jpeg, Files.readAllBytes(original(id)))
        assertFalse(Files.exists(local.resolve(stored[Assets.originalPath])))
        assertEquals("FILENAME", stored[Assets.takenAtSource])
        assertEquals("2024-01", stored[Assets.yearMonth])
        assertEquals(32, stored[Assets.width])
        assertTrue(Files.exists(props.dbDir.resolve("photos.db")))
        Files.list(props.uploadTmpDir).use { assertEquals(0L, it.count()) }
        val duplicate = upload()
        assertEquals(409, duplicate.statusCode())
        assertEquals(id, mapper.readTree(duplicate.body())["id"].asLong())
        assertEquals(400, upload(hash = "0".repeat(64)).statusCode())
        assertEquals(415, upload(filename = "unsupported.txt").statusCode())
        val download = request("GET", "/api/v1/assets/$id/file")
        assertEquals(200, download.statusCode())
        assertEquals("image/jpeg", download.headers().firstValue("Content-Type").orElse(""))
        assertContentEquals(jpeg, download.body())
    }

    @Test fun `video ranges include closed suffix open ended and invalid ranges`() {
        val bytes = ByteArray(256) { it.toByte() }
        val id = create(bytes, "20240102_030405.mp4")
        for ((range, start, end) in listOf(Triple("bytes=10-31", 10, 31), Triple("bytes=-5", 251, 255), Triple("bytes=250-", 250, 255))) {
            val response = request("GET", "/api/v1/assets/$id/file", range = range)
            assertEquals(206, response.statusCode())
            assertEquals("bytes $start-$end/256", response.headers().firstValue("Content-Range").orElse(""))
            assertEquals((end - start + 1).toString(), response.headers().firstValue("Content-Length").orElse(""))
            assertEquals("video/mp4", response.headers().firstValue("Content-Type").orElse(""))
            assertContentEquals(bytes.copyOfRange(start, end + 1), response.body())
        }
        assertEquals(416, request("GET", "/api/v1/assets/$id/file", range = "bytes=512-").statusCode())
    }

    @Test fun `multiple ranges preserve multipart byte bodies`() {
        val bytes = ByteArray(256) { it.toByte() }
        val id = create(bytes, "range.mp4")
        val response = request("GET", "/api/v1/assets/$id/file", range = "bytes=0-3,10-13")
        assertEquals(206, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("multipart/byteranges"))
        val body = response.body().toString(Charsets.ISO_8859_1)
        assertTrue(body.contains("Content-Range: bytes 0-3/256"))
        assertTrue(body.contains("Content-Range: bytes 10-13/256"))
        assertTrue(body.contains(bytes.copyOfRange(0, 4).toString(Charsets.ISO_8859_1)))
        assertTrue(body.contains(bytes.copyOfRange(10, 14).toString(Charsets.ISO_8859_1)))
    }

    @Test fun `trash restore purge and reupload retain the original API lifecycle`() {
        val id = create()
        assertEquals(200, request("DELETE", "/api/v1/assets/$id").statusCode())
        assertEquals(200, request("GET", "/api/v1/assets/$id/file").statusCode())
        assertEquals(200, request("POST", "/api/v1/trash/$id/restore").statusCode())
        assertNull(row(id)[Assets.deletedAt])
        request("DELETE", "/api/v1/assets/$id")
        assertEquals(200, request("DELETE", "/api/v1/trash/$id").statusCode())
        assertFalse(Files.exists(original(id)))
        assertNotNull(row(id)[Assets.purgedAt])
        assertEquals(404, request("GET", "/api/v1/assets/$id/file").statusCode())
        assertEquals(id, create())
        assertContentEquals(jpeg, request("GET", "/api/v1/assets/$id/file").body())
    }

    @Test fun `confirmed missing original is not found and cannot be restored from trash`() {
        val id = create()
        request("DELETE", "/api/v1/assets/$id")
        Files.delete(original(id))
        assertEquals(404, request("GET", "/api/v1/assets/$id/file").statusCode())
        assertEquals(409, request("POST", "/api/v1/trash/$id/restore").statusCode())
        assertNotNull(row(id)[Assets.deletedAt])
        assertEquals(404, request("GET", "/api/v1/assets/999999/file").statusCode())
    }

    @Test fun `unavailable archive is not reported as absent and cannot advance trash state`() {
        val id = create()
        request("DELETE", "/api/v1/assets/$id")
        val offline = directory.resolve("offline-archive")
        Files.move(archive, offline)
        try {
            assertEquals(500, request("GET", "/api/v1/assets/$id/file").statusCode())
            assertEquals(500, request("POST", "/api/v1/trash/$id/restore").statusCode())
            assertEquals(500, request("DELETE", "/api/v1/trash/$id").statusCode())
            assertNotNull(row(id)[Assets.deletedAt])
            assertNull(row(id)[Assets.purgedAt])
        } finally { Files.move(offline, archive) }
        assertContentEquals(jpeg, request("GET", "/api/v1/assets/$id/file").body())
    }

    @Test fun `thumbnails use the archive but remain in local storage`() {
        val id = create()
        val asset = row(id)
        thumbnails.generate(asset[Assets.hash], asset[Assets.originalPath], "PHOTO")
        for (size in ThumbnailService.SIZES) {
            val path = thumbnails.thumbPath(asset[Assets.hash], size)
            assertTrue(path.startsWith(local.resolve("thumbs")))
            assertNotNull(ImageIO.read(path.toFile()))
            val response = request("GET", "/api/v1/assets/$id/thumb?size=$size")
            assertEquals(200, response.statusCode())
            assertContentEquals(Files.readAllBytes(path), response.body())
            assertEquals("image/jpeg", response.headers().firstValue("Content-Type").orElse(""))
        }
    }

    @Test fun `dashboard capacity identifies the original archive`() {
        create()
        val response = request("GET", "/api/v1/stats/summary")
        assertEquals(200, response.statusCode())
        val storage = mapper.readTree(response.body())["storage"]
        assertEquals(archive.toString(), storage["root"].asText())
        assertEquals(jpeg.size.toLong(), storage["usedByOriginals"].asLong())
        assertTrue(storage["totalBytes"].asLong() > 0)
    }

    @Test fun `import excludes both roots and preserves scan copy move behavior`() {
        val incoming = directory.resolve("incoming")
        Files.createDirectories(incoming)
        val input = incoming.resolve("input.jpg")
        Files.write(input, jpeg)
        try {
            assertFailsWith<IllegalArgumentException> { imports.start(archive.toString(), "COPY") }
            assertFailsWith<IllegalArgumentException> { imports.start(local.toString(), "COPY") }
            assertEquals(1, runImport(directory, "SCAN").total)
            transaction(db) { assertEquals(0L, Assets.selectAll().count()) }
            assertEquals(1, runImport(incoming, "COPY").imported)
            assertTrue(Files.exists(input))
            val moved = incoming.resolve("move.jpg")
            Files.write(moved, jpeg + byteArrayOf(1)) // 해시가 다른 신규 입력으로 MOVE를 확인한다.
            assertEquals(1, runImport(incoming, "MOVE").imported)
            assertFalse(Files.exists(moved))
            assertTrue(Files.exists(input)) // 기존 중복 입력은 기존 정책대로 유지한다.
        } finally {
            Files.list(incoming).use { files -> files.forEach(Files::deleteIfExists) }
            Files.delete(incoming)
        }
    }

    @Test fun `original API still requires authentication`() {
        val id = create()
        assertEquals(401, request("GET", "/api/v1/assets/$id/file", authenticated = false).statusCode())
    }

    @Test fun `settings HTTP saves and preserves the original root for older clients`() {
        val response = request("GET", "/api/v1/admin/settings")
        assertEquals(200, response.statusCode())
        val settings = mapper.readTree(response.body()) as com.fasterxml.jackson.databind.node.ObjectNode
        assertEquals(archive.toString().replace('\\', '/'), settings["originalStorageRoot"].asText())
        settings.remove("originalStorageRoot")
        val saved = request("PUT", "/api/v1/admin/settings", mapper.writeValueAsBytes(settings), "application/json")
        assertEquals(200, saved.statusCode())
        assertTrue(Files.exists(directory.resolve("settings/application.yml")))
    }

    private fun runImport(path: Path, mode: String): ImportStatusDto {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!imports.start(path.toString(), mode)) {
            check(System.nanoTime() < deadline) { "import start timeout" }
            Thread.sleep(5)
        }
        while (imports.status().running) {
            check(System.nanoTime() < deadline) { "import completion timeout" }
            Thread.sleep(5)
        }
        return imports.status().also { assertEquals("DONE", it.phase, it.message) }
    }

    @AfterAll fun cleanup() {
        TransactionManager.closeAndUnregister(db)
        (dataSource as HikariDataSource).close()
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        val directory: Path = Files.createTempDirectory("homephoto-storage-http-")
        val local: Path = directory.resolve("local")
        val archive: Path = directory.resolve("archive")
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("homephoto.storage-root") { local.toString() }
            registry.add("homephoto.original-storage.root") { archive.toString() }
            registry.add("homephoto.api-key") { "storage-http-test-key" }
            registry.add("homephoto.db-path") { "" }
            registry.add("homephoto.thumbs-path") { "" }
            registry.add("spring.datasource.url") { "" }
            registry.add("homephoto.settings-config-file") { directory.resolve("settings/application.yml").toString() }
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(excludeName = ["org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.jetbrains.exposed.spring.autoconfigure.ExposedAutoConfiguration",
    "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
    "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"])
@EnableConfigurationProperties(AppProperties::class)
@Import(DataSourceConfig::class, DatabaseMigrations::class, StartupJobRecovery::class, FileSystemAdapter::class,
    AssetIngestService::class, ExifService::class, TakenAtResolver::class, AssetLocks::class, ThumbnailService::class,
    ThumbnailStorage::class, MediaProcessRunner::class, TrashService::class, ImportService::class,
    AssetQueryService::class, SettingsService::class, AssetController::class, TrashController::class,
    StatsController::class, SettingsController::class, ApiExceptionHandler::class, ApiKeyFilter::class)
class AssetStorageTestConfiguration {
    @Bean fun database(dataSource: DataSource): Database = Database.connect(dataSource)
    @Bean fun initializer(props: AppProperties, database: Database, migrations: DatabaseMigrations,
                          recovery: StartupJobRecovery, storage: StorageAdapter): DataInitializer {
        TransactionManager.defaultDatabase = database
        return DataInitializer(props, migrations, recovery, storage)
    }
}
