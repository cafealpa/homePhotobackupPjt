package com.homephoto.server.publication

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class GooglePhotosOrganizeTest {
    @TempDir lateinit var temp: Path
    @Test fun `organize database connection can read existing rows but cannot write or create a missing database`() {
        val path = temp.resolve("운영 이력.db")
        val writable = Database.connect("jdbc:sqlite:$path", driver = "org.sqlite.JDBC")
        try { transaction(writable) { exec("CREATE TABLE fixture (id INTEGER)"); exec("INSERT INTO fixture VALUES (7)") } }
        finally { TransactionManager.closeAndUnregister(writable) }
        val readOnly = GooglePhotosOrganize.readOnlyDatabase(path)
        try {
            assertEquals(7, transaction(readOnly) { exec("SELECT id FROM fixture") { rs -> rs.next(); rs.getInt(1) } })
            assertFails { transaction(readOnly) { exec("INSERT INTO fixture VALUES (8)") } }
            assertEquals(1, transaction(readOnly) { exec("SELECT COUNT(*) FROM fixture") { rs -> rs.next(); rs.getInt(1) } })
        } finally { TransactionManager.closeAndUnregister(readOnly) }
        val missing = GooglePhotosOrganize.readOnlyDatabase(temp.resolve("missing.db"))
        try { assertFails { transaction(missing) { exec("SELECT 1") } } }
        finally { TransactionManager.closeAndUnregister(missing) }
    }
}
