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

class MergeClustersTest {
    @TempDir lateinit var temp: Path

    @Test fun `merge of twenty thousand photos preserves target name and originals under concurrent writes`() {
        val url = "jdbc:sqlite:${temp.resolve("bulk.db")}?journal_mode=WAL&busy_timeout=30000"
        val db = Database.connect(url, driver = "org.sqlite.JDBC")
        try {
            transaction(db) {
                SchemaUtils.create(Assets, Persons, Faces)
                for (asset in 1L..20001L) Assets.insert {
                    it[id] = asset; it[hash] = "hash-$asset"; it[mediaType] = "PHOTO"
                    it[originalPath] = "test.jpg"; it[originalFilename] = "test.jpg"; it[fileSize] = 1
                    it[takenAtSource] = "UPLOAD_TIME"; it[yearMonth] = "2026-10"; it[createdAt] = "2026-10-16T00:00:00"
                }
                val person = Persons.insert { it[name] = "이름 있는 인물" }[Persons.id]
                fun face(cluster: Int?, asset: Long, hidden: Boolean = false) = Faces.insert {
                    it[clusterId] = cluster; it[assetId] = asset; it[personId] = person
                    it[bboxX] = .1; it[bboxY] = .1; it[bboxW] = .2; it[bboxH] = .2
                    it[embedding] = ExposedBlob(ByteArray(2048)); it[Faces.hidden] = hidden
                }
                for (asset in 1L..20000L) face(if (asset <= 10000) 1 else 2, asset)
                face(3, 20001, true)
                val targetPerson = Persons.insert { it[name] = "대상 이름" }[Persons.id]
                Faces.update({ Faces.clusterId eq 2 }) { it[personId] = targetPerson }
            }
            val controller = FacesController(AppProperties(temp, "test"), mock(ThumbnailService::class.java))
            assertFailsWith<ResponseStatusException> { controller.mergeCluster(1, MergeClusterRequest(1)) }
            assertFailsWith<ResponseStatusException> { controller.mergeCluster(1, MergeClusterRequest(3)) }
            assertFailsWith<ResponseStatusException> { controller.mergeCluster(1, MergeClusterRequest(999)) }
            val result = DriverManager.getConnection(url).use { other ->
                other.createStatement().use { it.execute("PRAGMA busy_timeout=0") }
                var blocked = false
                var reads = 0
                transaction(db) {
                    maxAttempts = 1
                    val interceptor = object : StatementInterceptor {
                        override fun beforeExecution(transaction: Transaction, context: StatementContext) {
                            if (context.statement.type != StatementType.SELECT || ++reads != 2) return
                            try {
                                other.createStatement().use { it.executeUpdate("UPDATE faces SET bbox_x=0.15 WHERE cluster_id=2") }
                            } catch (e: SQLException) {
                                assertEquals(5, e.errorCode)
                                blocked = true
                            }
                        }
                    }
                    registerInterceptor(interceptor)
                    try {
                        controller.mergeCluster(1, MergeClusterRequest(2)).also { assertTrue(blocked) }
                    } finally { unregisterInterceptor(interceptor) }
                }
            }
            assertEquals(2, result["into"])
            val clusters = controller.clusters()
            assertEquals(1, clusters.size)
            assertEquals("대상 이름", clusters.single().name)
            assertEquals(20000L, clusters.single().faceCount)
            assertFailsWith<ResponseStatusException> { controller.mergeCluster(1, MergeClusterRequest(2)) }
            transaction(db) {
                assertEquals(20001L, Assets.selectAll().count())
                assertEquals(20001L, Faces.selectAll().count())
                assertTrue(Faces.selectAll().where { Faces.clusterId eq 3 }.single()[Faces.hidden])
            }
        } finally { TransactionManager.closeAndUnregister(db) }
    }
}
