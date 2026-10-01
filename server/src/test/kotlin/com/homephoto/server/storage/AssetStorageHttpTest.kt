package com.homephoto.server.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.api.*
import com.homephoto.server.config.*
import com.homephoto.server.db.*
import com.homephoto.server.service.*
import com.homephoto.server.publication.*
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
    @Autowired private lateinit var publications: GooglePhotosPublicationQueue
    private val http = HttpClient.newHttpClient()
    private val jpeg = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB), "jpg", it)
    }.toByteArray()

    @BeforeEach fun resetDatabase() {
        TransactionManager.defaultDatabase = db
        transaction(db) {
            GooglePhotosPublications.deleteAll(); Jobs.deleteAll(); Faces.deleteAll(); Captions.deleteAll(); Assets.deleteAll()
        }
    }

    private fun request(method: String, path: String, bytes: ByteArray? = null, contentType: String? = null,
                        range: String? = null, authenticated: Boolean = true, action: String? = null): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path")).timeout(java.time.Duration.ofSeconds(10))
        if (authenticated) builder.header("X-Api-Key", "storage-http-test-key")
        contentType?.let { builder.header("Content-Type", it) }
        range?.let { builder.header("Range", it) }
        action?.let { builder.header("X-HomePhoto-Action", it) }
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
        Files.walk(props.uploadTmpDir).use { assertEquals(0L, it.filter(Files::isRegularFile).count()) }
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

    private fun publicationAction(path: String, body: Any) = request("POST", "/api/v1/admin/google-photos$path",
        mapper.writeValueAsBytes(body), "application/json", action = "google-photos")

    @Test fun `publication administration requires authentication action header and queues while disabled`() {
        val id = create()
        val base = "/api/v1/admin/google-photos"
        assertEquals(401, request("GET", base, authenticated = false).statusCode())
        val status = request("GET", base)
        assertFalse(mapper.readTree(status.body())["enabled"].asBoolean())
        assertFalse(mapper.readTree(status.body())["credentialsConfigured"].asBoolean())
        assertTrue(mapper.readTree(status.body())["album"].isNull)
        for (path in listOf("/album/organize", "/album/resolve")) {
            assertEquals(401, request("POST", "$base$path", "{}".toByteArray(), "application/json", authenticated = false, action = "google-photos").statusCode())
            assertNotEquals(200, request("POST", "$base$path", "{}".toByteArray(), "application/json").statusCode())
        }
        val noCredentials = publicationAction("/album/organize", emptyMap<String, Any>())
        assertEquals(409, noCredentials.statusCode())
        assertEquals("CREDENTIALS_NOT_CONFIGURED", mapper.readTree(noCredentials.body())["error"].asText())
        assertEquals(400, publicationAction("/album/resolve", mapOf("productUrl" to "javascript:alert(1)")).statusCode())
        val selection = mapOf("assetIds" to listOf(id))
        assertNotEquals(200, request("POST", "$base/enqueue", mapper.writeValueAsBytes(selection), "application/json").statusCode())
        assertEquals(1, mapper.readTree(publicationAction("/enqueue", selection).body())["enqueued"].asInt())
        assertEquals(1, mapper.readTree(publicationAction("/enqueue", selection).body())["existing"].asInt())
        val queued = request("GET", base).body().decodeToString()
        assertTrue(queued.contains("PENDING"))
        assertFalse(queued.contains("uploadToken"))
        assertFalse(queued.contains("refreshToken"))
        assertFalse(queued.contains("connectionId"))
        assertEquals(409, publicationAction("/$id/retry", emptyMap<String, Any>()).statusCode())
        assertEquals(200, publicationAction("/$id/cancel", emptyMap<String, Any>()).statusCode())
        assertEquals("CANCELLED_BY_USER", publications.items().single().lastError)
        assertEquals(409, publicationAction("/$id/cancel", emptyMap<String, Any>()).statusCode())
        assertContentEquals(jpeg, Files.readAllBytes(original(id)))
    }

    @Test fun `publication preview preserves originals writes EXIF and removes its temporary rendition`() {
        val id = create()
        assertEquals(400, request("GET", "/api/v1/admin/google-photos/$id/preview").statusCode())
        val asset = row(id)
        thumbnails.generate(asset[Assets.hash], asset[Assets.originalPath], "PHOTO")
        val response = request("GET", "/api/v1/admin/google-photos/$id/preview")
        assertEquals(200, response.statusCode(), response.body().decodeToString())
        assertEquals("image/jpeg", response.headers().firstValue("Content-Type").orElse(""))
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"))
        val metadata = com.drew.imaging.ImageMetadataReader.readMetadata(response.body().inputStream())
        val exif = metadata.getFirstDirectoryOfType(com.drew.metadata.exif.ExifSubIFDDirectory::class.java)
        assertEquals("2024:01:02 03:04:05", exif.getString(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL))
        assertContentEquals(jpeg, Files.readAllBytes(original(id)))
        assertTrue(publications.items().isEmpty())
        Files.list(props.uploadTmpDir.resolve("google-photos")).use { assertEquals(0L, it.count()) }
        assertEquals(404, request("GET", "/api/v1/admin/google-photos/999999/preview").statusCode())
    }

    @Test fun `uncertain publication requires explicit resolution and protects completed history`() {
        val id = create()
        publicationAction("/recent", mapOf("limit" to 5))
        transaction(db) { GooglePhotosPublications.update({ GooglePhotosPublications.assetId eq id }) { it[status] = "UNKNOWN" } }
        assertEquals(409, publicationAction("/$id/retry", emptyMap<String, Any>()).statusCode())
        assertEquals(400, publicationAction("/$id/resolve", emptyMap<String, Any>()).statusCode())
        assertEquals(400, publicationAction("/$id/resolve", mapOf("mediaItemId" to "existing", "productUrl" to "javascript:alert(1)")).statusCode())
        assertEquals(400, publicationAction("/$id/resolve", mapOf("mediaItemId" to "existing", "productUrl" to "bad url")).statusCode())
        assertEquals(200, publicationAction("/$id/resolve", mapOf("confirmedNotCreated" to true)).statusCode())
        assertEquals("PENDING", publications.items().single().status)
        val staging = props.uploadTmpDir.resolve("google-photos/$id-http-resolution.jpg")
        Files.createDirectories(staging.parent); Files.write(staging, jpeg)
        transaction(db) { GooglePhotosPublications.update({ GooglePhotosPublications.assetId eq id }) {
            it[status] = "UNKNOWN"; it[renditionPath] = staging.toString(); it[uploadToken] = "private-upload-token"
        } }
        assertFalse(request("GET", "/api/v1/admin/google-photos").body().decodeToString().contains("private-upload-token"))
        assertEquals(200, publicationAction("/$id/resolve", mapOf("mediaItemId" to "existing", "productUrl" to "https://photos.google.com/photo/existing")).statusCode())
        assertFalse(Files.exists(staging))
        assertEquals("COMPLETED", publications.items().single().status)
        assertEquals("existing", publications.items().single().mediaItemId)
        assertEquals(409, publicationAction("/$id/cancel", emptyMap<String, Any>()).statusCode())
        assertEquals(1, mapper.readTree(publicationAction("/enqueue", mapOf("assetIds" to listOf(id))).body())["existing"].asInt())
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
    StatsController::class, SettingsController::class, ApiExceptionHandler::class, ApiKeyFilter::class,
    GooglePhotosPublicationQueue::class, GooglePhotosExport::class, ExportExifWriter::class,
    PublicationMetadataProvider::class, GooglePhotosController::class, GooglePhotosTokenProvider::class,
    GooglePhotosLibraryPublisher::class, GooglePhotosPublicationAlbum::class)
class AssetStorageTestConfiguration {
    @Bean fun database(dataSource: DataSource): Database = Database.connect(dataSource)
    @Bean fun initializer(props: AppProperties, database: Database, migrations: DatabaseMigrations,
                          recovery: StartupJobRecovery, storage: StorageAdapter, publications: GooglePhotosPublicationQueue): DataInitializer {
        TransactionManager.defaultDatabase = database
        return DataInitializer(props, migrations, recovery, storage, publications)
    }
}
