package com.homephoto.server.publication

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.homephoto.server.config.AppProperties
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** 명시적으로 지정한 운영 DB는 읽기 전용. 앨범 생성/기존 ID 연결만 실행하며 사진을 업로드하지 않는다. */
object GooglePhotosOrganize {
    internal fun readOnlyDatabase(path: Path): Database =
        Database.connect("jdbc:sqlite:${path.toAbsolutePath().toUri()}?mode=ro&busy_timeout=5000", driver = "org.sqlite.JDBC")
    @JvmStatic fun main(args: Array<String>) {
        try {
            fun required(name: String) = requireNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) { "$name 경로가 필요합니다." }
            val dbPath = Path.of(required("HOMEPHOTO_GOOGLE_PHOTOS_DATABASE")).toAbsolutePath()
            require(Files.isRegularFile(dbPath)) { "기존 DB 파일이 필요합니다." }
            val props = AppProperties(dbPath.parent, "", googlePhotos = AppProperties.GooglePhotosProperties(
                clientFile = required("HOMEPHOTO_GOOGLE_PHOTOS_CLIENT_JSON"), tokenFile = required("HOMEPHOTO_GOOGLE_PHOTOS_TOKENS_JSON")))
            val mapper = jacksonObjectMapper()
            val publisher = GooglePhotosLibraryPublisher(GooglePhotosTokenProvider(props, mapper), mapper)
            val db = readOnlyDatabase(dbPath)
            TransactionManager.defaultDatabase = db
            try {
                val result = GooglePhotosPublicationAlbum(props, mapper, publisher).organize(GooglePhotosPublicationQueue(props, mapper))
                println(mapper.writeValueAsString(result))
                if (result.stoppedCode != null || result.failedAssetIds.isNotEmpty()) exitProcess(2)
            } finally { TransactionManager.closeAndUnregister(db) }
        } catch (error: PublicationFailure) { System.err.println(error.code); exitProcess(1) }
        catch (_: Exception) { System.err.println("ORGANIZE_CONFIGURATION_OR_STORAGE_FAILED"); exitProcess(1) }
    }
}
