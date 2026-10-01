package com.homephoto.server.publication

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.db.GooglePhotosExistingFilenames as Names
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Google 전체 라이브러리를 읽는 대신 본인 계정의 내보내기 파일명 목록을 사용한다. */
@Service
class GooglePhotosFilenameExclusions(private val mapper: ObjectMapper) {
    data class Status(val count: Long, val updatedAt: String?)
    data class Added(val added: Long, val total: Long)

    fun status(): Status = transaction {
        Status(Names.selectAll().count(), Names.select(Names.importedAt).orderBy(Names.importedAt to SortOrder.DESC)
            .limit(1).firstOrNull()?.get(Names.importedAt))
    }

    fun add(filenames: List<String>): Added {
        val unique = filenames.filter(String::isNotBlank).distinct()
        require(unique.isNotEmpty()) { "기존 Google 포토의 원본 파일명을 입력하세요." }
        return transaction {
            val before = Names.selectAll().count()
            val timestamp = Instant.now().toString()
            Names.batchInsert(unique, ignore = true, shouldReturnGeneratedValues = false) { name ->
                this[Names.filename] = name
                this[Names.importedAt] = timestamp
            }
            val total = Names.selectAll().count()
            Added(total - before, total)
        }
    }

    fun importTakeout(directory: String): Added {
        require(directory.isNotBlank()) { "Google Takeout 폴더 경로를 입력하세요." }
        val root = Path.of(directory)
        require(Files.isDirectory(root)) { "서버 PC에 압축을 해제한 Google Takeout 폴더 경로를 입력하세요." }
        val filenames = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json", ignoreCase = true) }
                .map { path -> mapper.readTree(path.toFile()) }
                .filter { it.has("photoTakenTime") && it.path("title").isTextual }
                .map { it.path("title").asText() }.toList()
        }
        require(filenames.isNotEmpty()) { "사진·동영상 메타데이터 JSON에서 원본 파일명을 찾지 못했습니다." }
        return add(filenames)
    }

    fun clear(): Long = transaction { Names.deleteAll().toLong() }
}
