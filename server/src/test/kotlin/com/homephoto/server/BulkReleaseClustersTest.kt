package com.homephoto.server

import com.homephoto.server.api.*
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.*
import com.homephoto.server.service.ThumbnailService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import org.jetbrains.exposed.sql.statements.StatementContext
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import org.jetbrains.exposed.sql.statements.StatementType
import kotlin.test.*

class BulkReleaseClustersTest {
    @TempDir lateinit var temp: Path

    @Test fun `release uses distinct visible photos and includes named clusters in batches`() {
        val url = "jdbc:sqlite:${temp.resolve("bulk.db")}?journal_mode=WAL&busy_timeout=30000"
        val db = Database.connect(url, driver = "org.sqlite.JDBC")
        try {
            transaction(db) {
                SchemaUtils.create(Assets, Persons, Faces)
                for (asset in 1L..3L) Assets.insert {
                    it[id] = asset; it[hash] = "hash-$asset"; it[mediaType] = "PHOTO"
                    it[originalPath] = "test.jpg"; it[originalFilename] = "test.jpg"; it[fileSize] = 1
                    it[takenAtSource] = "UPLOAD_TIME"; it[yearMonth] = "2026-10"; it[createdAt] = "2026-10-16T00:00:00"
                }
                val person = Persons.insert { it[name] = "이름 있는 인물" }[Persons.id]
                fun face(cluster: Int?, asset: Long, hidden: Boolean = false) = Faces.insert {
                    it[clusterId] = cluster; it[assetId] = asset; it[personId] = person
                    it[bboxX] = .1; it[bboxY] = .1; it[bboxW] = .2; it[bboxH] = .2
                    it[embedding] = ExposedBlob(byteArrayOf(0)); it[Faces.hidden] = hidden
                }
                for (cluster in 1..1001) face(cluster, 1)
                face(1, 1) // 같은 사진의 얼굴 두 개는 한 장.
                face(1, 2, true) // 이미 숨긴 얼굴은 장수에서 제외.
                face(2000, 1); face(2000, 2) // 경계보다 큰 인물.
                face(3000, 3, true); face(null, 3)
            }
            val controller = FacesController(AppProperties(temp, "test"), mock(ThumbnailService::class.java))
            assertEquals(1L, controller.clusters().single { it.clusterId == 1 }.faceCount)
            assertFailsWith<ResponseStatusException> { controller.bulkReleaseClusters(BulkReleaseClustersRequest(0)) }
            assertFailsWith<ResponseStatusException> { controller.bulkReleaseClusters(BulkReleaseClustersRequest(-1)) }
            val result = DriverManager.getConnection(url).use { other ->
                other.createStatement().use { it.execute("PRAGMA busy_timeout=0") }
                var blocked = false
                transaction(db) {
                    maxAttempts = 1
                    val interceptor = object : StatementInterceptor {
                        override fun beforeExecution(transaction: Transaction, context: StatementContext) {
                            if (context.statement.type != StatementType.SELECT) return
                            try {
                                other.createStatement().use { it.executeUpdate("UPDATE faces SET cluster_id=4000 WHERE cluster_id=2000") }
                            } catch (e: SQLException) {
                                assertEquals(5, e.errorCode)
                                blocked = true
                            }
                        }
                    }
                    registerInterceptor(interceptor)
                    try {
                        controller.bulkReleaseClusters(BulkReleaseClustersRequest(1)).also { assertTrue(blocked) }
                    } finally { unregisterInterceptor(interceptor) }
                }
            }
            assertEquals(1001, result["releasedClusters"])
            assertEquals(1002, result["hiddenFaces"])
            assertEquals(listOf(2000), controller.clusters().map { it.clusterId })
            assertEquals(0, controller.bulkReleaseClusters(BulkReleaseClustersRequest(1))["releasedClusters"])
            assertEquals(1, controller.bulkReleaseClusters(BulkReleaseClustersRequest(2))["releasedClusters"])
            transaction(db) {
                assertEquals(3L, Assets.selectAll().count())
                assertEquals(1007L, Faces.selectAll().count())
                assertFalse(Faces.selectAll().where { Faces.clusterId.isNull() }.single()[Faces.hidden])
                assertTrue(Faces.selectAll().all { it[Faces.personId] != null })
            }
        } finally { TransactionManager.closeAndUnregister(db) }
    }
}
