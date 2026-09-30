package com.homephoto.server.publication

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.api.AuthController
import com.homephoto.server.api.DevicesController
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.Assets
import com.homephoto.server.db.GooglePhotosPublications as P
import com.homephoto.server.db.Jobs
import com.homephoto.server.service.AssetIngestService
import com.homephoto.server.service.ThumbnailService
import com.homephoto.server.service.ImportService
import com.homephoto.server.search.PhotoSearchProcess
import com.homephoto.server.storage.AssetStorageTestConfiguration
import com.homephoto.server.worker.GooglePhotosWorker
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.boot.SpringApplication
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.time.Instant
import javax.imageio.ImageIO

/** 생성 사진과 새 임시 DB만 사용한다. 운영 설정과 실제 Google 인증 파일은 읽지 않는다. */
object GooglePhotosAdminDemo {
    @JvmStatic fun main(args: Array<String>) {
        val directory = Files.createTempDirectory("homephoto-google-admin-demo-")
        val context = SpringApplication(GooglePhotosAdminDemoConfiguration::class.java).run(
            "--spring.config.location=classpath:/application.yml", "--server.port=18082", "--server.address=127.0.0.1",
            "--logging.file.name=", "--logging.level.com.homephoto=WARN",
            "--homephoto.storage-root=${directory.resolve("local")}", "--homephoto.original-storage.root=${directory.resolve("archive")}",
            "--homephoto.db-path=", "--homephoto.thumbs-path=", "--spring.datasource.url=",
            "--homephoto.api-key=google-admin-demo-key", "--homephoto.settings-config-file=${directory.resolve("settings.yml")}",
            "--homephoto.google-photos.enabled=false", "--homephoto.google-photos.client-file=", "--homephoto.google-photos.token-file=",
            "--homephoto.google-photos.auto-publish-new=false", "--homephoto.google-photos.include-videos=false",
        )
        val database = context.getBean(Database::class.java)
        val ingest = context.getBean(AssetIngestService::class.java)
        val thumbnails = context.getBean(ThumbnailService::class.java)
        val export = context.getBean(GooglePhotosExport::class.java)
        val mapper = context.getBean(ObjectMapper::class.java)
        for (n in 1..9) {
            val input = directory.resolve("DEMO_${n}.jpg")
            val image = BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color.getHSBColor(n / 10f, .4f, .6f); graphics.fillRect(0, 0, 1600, 1000)
            graphics.color = Color.WHITE; graphics.font = Font("SansSerif", Font.BOLD, 85)
            graphics.drawString("HOME PHOTO / DEMO $n", 80, 500); graphics.dispose()
            ImageIO.write(image, "jpg", input.toFile())
            val asset = ingest.ingest(input, "IMG_2024010${n}_120000.jpg", null, Instant.now(), skipMlJobs = true).asset
            val originalPath = transaction(database) { Assets.selectAll().where { Assets.id eq asset.id }.single()[Assets.originalPath] }
            thumbnails.generate(asset.hash, originalPath, "PHOTO")
            transaction(database) { Jobs.update({ Jobs.assetId eq asset.id }) { it[status] = "DONE" } }
            if (n <= 3) {
                val prepared = export.prepare(PublicationAsset(asset.id, asset.hash, originalPath, asset.originalFilename,
                    asset.takenAt, "FILENAME", null, null))
                transaction(database) { P.insert {
                    it[assetId] = asset.id; it[status] = listOf("UNKNOWN", "FAILED", "COMPLETED")[n - 1]
                    it[attempts] = 1; it[renditionVersion] = GooglePhotosExport.VERSION
                    it[renditionPath] = if (n == 3) null else prepared.path.toString(); it[renditionSha256] = prepared.sha256
                    it[metadataJson] = mapper.writeValueAsString(prepared.metadata)
                    it[mediaItemId] = if (n == 3) "demo-existing-item" else null
                    it[lastError] = if (n == 1) "CREATE_INTERRUPTED" else if (n == 2) "HTTP_400" else null
                    it[createdAt] = Instant.now().toString(); it[updatedAt] = Instant.now().toString()
                } }
                if (n == 3) export.delete(prepared.path)
            }
            Files.delete(input)
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            context.close()
            TransactionManager.closeAndUnregister(database)
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        })
        check(!context.getBean(AppProperties::class.java).googlePhotos.enabled)
        println("Google Photos admin demo: http://localhost:18082 (generated photos, test key: google-admin-demo-key)")
        println("Storage is temporary; Google Photos stays disabled. Unrelated admin actions are not loaded.")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(AssetStorageTestConfiguration::class, AuthController::class, DevicesController::class,
    GooglePhotosTokenProvider::class, GooglePhotosLibraryPublisher::class,
    GooglePhotosPublicationProcessor::class, GooglePhotosWorker::class, GooglePhotosAdminDemoStatus::class)
class GooglePhotosAdminDemoConfiguration

/** 설정 화면의 공통 상태 polling만 응답한다. 실제 벡터 검색/임포트 실행은 로드하지 않는다. */
@RestController
class GooglePhotosAdminDemoStatus(private val imports: ImportService) {
    @GetMapping("/api/v1/admin/server-info")
    fun serverInfo() = mapOf("port" to 18082, "addresses" to listOf("127.0.0.1"), "version" to "google-photos-demo")
    @GetMapping("/api/v1/admin/import/status")
    fun importStatus() = imports.status()
    @GetMapping("/api/v1/admin/search-service")
    fun searchStatus() = PhotoSearchProcess.Status("disabled", "이 검증 서버에서는 벡터 검색을 실행하지 않습니다.")
}
