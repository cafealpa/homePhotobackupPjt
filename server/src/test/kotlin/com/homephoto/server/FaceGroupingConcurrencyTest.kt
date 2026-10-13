package com.homephoto.server

import com.homephoto.server.db.*
import com.homephoto.server.service.FaceGroupingService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.StatementContext
import org.jetbrains.exposed.sql.statements.StatementInterceptor
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.*

class FaceGroupingConcurrencyTest {
    @TempDir lateinit var temp: Path

    @Test fun `caption write between face reads waits until grouping commits without snapshot retries`() {
        val url = "jdbc:sqlite:${temp.resolve("grouping.db")}?journal_mode=WAL&busy_timeout=30000"
        val db = Database.connect(url, driver = "org.sqlite.JDBC")
        try {
            transaction(db) {
                SchemaUtils.create(Assets, Persons, Faces, Captions)
                for (asset in 1L..2L) {
                    Assets.insert {
                        it[id] = asset; it[hash] = "hash-$asset"; it[mediaType] = "PHOTO"
                        it[originalPath] = "test.jpg"; it[originalFilename] = "test.jpg"; it[fileSize] = 1
                        it[takenAtSource] = "UPLOAD_TIME"; it[yearMonth] = "2026-10"; it[createdAt] = "2026-10-13T00:00:00"
                    }
                    Faces.insert {
                        it[assetId] = asset; it[bboxX] = .1; it[bboxY] = .1; it[bboxW] = .2; it[bboxH] = .2
                        it[embedding] = ExposedBlob(FaceGroupingService.encode(FloatArray(512) { if (it == 0) 1f else 0f }))
                        it[clusterId] = if (asset == 1L) 7 else null
                    }
                }
                Captions.insert { it[assetId] = 2; it[caption] = "before"; it[createdAt] = "2026-10-13T00:00:00" }
            }
            DriverManager.getConnection(url).use { captionConnection ->
                // 기다리지 않고 충돌을 확인한 뒤 그룹화 커밋 후 같은 연결에서 저장한다.
                captionConnection.createStatement().use { it.execute("PRAGMA busy_timeout=0") }
                var reads = 0
                var captionWriteError: SQLException? = null
                transaction(db) {
                    maxAttempts = 1 // Exposed의 재시도로 버그가 가려지지 않도록 한다.
                    val interceptor = object : StatementInterceptor {
                        override fun beforeExecution(transaction: Transaction, context: StatementContext) {
                            if (context.statement.type != StatementType.SELECT || ++reads != 2) return
                            // 첫 얼굴 SELECT가 읽기 스냅샷을 확보한 직후 다른 연결이 저장한다.
                            try {
                                captionConnection.createStatement().use { it.executeUpdate("UPDATE captions SET caption='after' WHERE asset_id=2") }
                            } catch (e: SQLException) {
                                captionWriteError = e
                            }
                        }
                    }
                    registerInterceptor(interceptor)
                    try { assertEquals(1, FaceGroupingService().assignPending()) }
                    finally { unregisterInterceptor(interceptor) }
                }
                assertTrue(reads >= 2)
                assertEquals(5, captionWriteError?.errorCode, "그룹화가 먼저 쓰기 권한을 확보해 장면 저장이 SQLITE_BUSY로 대기해야 한다")
                captionConnection.createStatement().use {
                    assertEquals(1, it.executeUpdate("UPDATE captions SET caption='after' WHERE asset_id=2"))
                }
            }
            transaction(db) {
                assertEquals(listOf(7, 7), Faces.selectAll().orderBy(Faces.id).map { it[Faces.clusterId] })
                assertEquals("after", Captions.selectAll().single()[Captions.caption])
            }
            assertEquals(0, FaceGroupingService().assignPending())
        } finally {
            TransactionManager.closeAndUnregister(db)
        }
    }
}
