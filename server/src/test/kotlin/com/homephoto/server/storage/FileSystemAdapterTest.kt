package com.homephoto.server.storage

import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.AssetIngestService
import com.homephoto.server.service.AssetLocks
import com.homephoto.server.service.ExifService
import com.homephoto.server.service.TakenAtResolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class FileSystemAdapterTest {
    @TempDir lateinit var temp: Path
    private val root get() = temp.resolve("storage")
    private val storage: StorageAdapter get() = FileSystemAdapter(AppProperties(root, "test"))
    private val bytes = "original media bytes".toByteArray()
    private val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val key = "originals/2024/01/legacy-name.jpg"
    private fun source() = temp.resolve("input.jpg").also { Files.write(it, bytes) }

    @Test fun `copy keeps input and legacy key while exposing complete bytes`() {
        val source = source()
        storage.save(key, source, checksum).commit()
        assertContentEquals(bytes, Files.readAllBytes(source))
        assertEquals(bytes.size.toLong(), assertNotNull(storage.stat(key)).size)
        storage.withReadableFile(key) { path ->
            assertEquals(root.resolve(key).toAbsolutePath(), path)
            assertContentEquals(bytes, Files.readAllBytes(path))
        }
        storage.open(key).use { assertContentEquals(bytes, it.readAllBytes()) }
    }

    @Test fun `same volume move can roll back without losing bytes`() {
        val source = source()
        val write = storage.save(key, source, checksum, moveSource = true)
        assertFalse(Files.exists(source))
        storage.open(key).use { assertContentEquals(bytes, it.readAllBytes()) }
        write.rollback()
        assertContentEquals(bytes, Files.readAllBytes(source))
        assertNull(storage.stat(key))
    }

    @Test fun `existing matching target removes move input only after commit`() {
        val source = source()
        storage.save(key, source, checksum).commit()
        val write = storage.save(key, source, checksum, moveSource = true)
        assertTrue(Files.exists(source))
        write.rollback()
        assertTrue(Files.exists(source))
        // rollback은 복사/기존 대상에서 입력을 유지한다. 별도 재시도의 성공 시에만 입력을 정리한다.
        storage.save(key, source, checksum, moveSource = true).commit()
        assertFalse(Files.exists(source))
        storage.open(key).use { assertContentEquals(bytes, it.readAllBytes()) }
    }

    @Test fun `mismatched existing target is preserved and input is not consumed`() {
        val source = source()
        val target = root.resolve(key)
        Files.createDirectories(target.parent)
        Files.writeString(target, "different content")
        assertFailsWith<IllegalStateException> { storage.save(key, source, checksum, moveSource = true) }
        assertEquals("different content", Files.readString(target))
        assertContentEquals(bytes, Files.readAllBytes(source))
    }

    @Test fun `move cleanup never deletes an input already at its storage key`() {
        storage.save(key, source(), checksum).commit()
        storage.save(key, root.resolve(key), checksum, moveSource = true).commit()
        storage.open(key).use { assertContentEquals(bytes, it.readAllBytes()) }
    }

    @Test fun `partial reads stop at the requested length and close their file handle`() {
        storage.save(key, source(), checksum).commit()
        storage.open(key, offset = 3, length = 5).use { input ->
            assertEquals(bytes[3].toInt(), input.read())
            assertEquals(1L, input.skip(1))
            assertContentEquals(bytes.copyOfRange(5, 8), input.readAllBytes())
            assertEquals(-1, input.read())
            assertEquals(0, input.read(ByteArray(0)))
        }
        storage.open(key, offset = 3).use { assertContentEquals(bytes.copyOfRange(3, bytes.size), it.readAllBytes()) }
        storage.open(key, length = 0).use { assertEquals(-1, it.read()) }
        storage.open(key, offset = bytes.size.toLong() + 5).use { assertEquals(-1, it.read()) }
        assertFailsWith<IllegalArgumentException> { storage.open(key, offset = -1) }
        assertFailsWith<IllegalArgumentException> { storage.open(key, length = -1) }
        storage.delete(key)
        assertNull(storage.stat(key))
        storage.delete(key)
    }

    @Test fun `missing files report absence and cannot be opened`() {
        Files.createDirectories(root)
        assertNull(storage.stat(key))
        assertFailsWith<NoSuchFileException> { storage.open(key) }
        storage.delete(key)
    }

    @Test fun `keys cannot address the root or files outside it`() {
        val outside = source()
        val invalidKeys = listOf("", ".", "../input.jpg", outside.toAbsolutePath().toString())
        for (invalidKey in invalidKeys) {
            assertFailsWith<IllegalArgumentException> { storage.stat(invalidKey) }
            assertFailsWith<IllegalArgumentException> { storage.open(invalidKey) }
            assertFailsWith<IllegalArgumentException> { storage.delete(invalidKey) }
            assertFailsWith<IllegalArgumentException> { storage.withReadableFile(invalidKey) { fail("invalid callback") } }
            assertFailsWith<IllegalArgumentException> { storage.save(invalidKey, outside, checksum, moveSource = true) }
        }
        assertContentEquals(bytes, Files.readAllBytes(outside))
    }

    @Test fun `empty copy is rejected without publishing a target or consuming input`() {
        val source = temp.resolve("empty.jpg").also { Files.createFile(it) }
        assertFailsWith<IllegalStateException> { storage.save(key, source, "unused") }
        assertTrue(Files.exists(source))
        assertNull(storage.stat(key))
        Files.list(root.resolve(key).parent).use { assertEquals(0L, it.count()) }
    }

    @Test fun `existing homephoto settings wire the adapter and ingest through Spring`() {
        ApplicationContextRunner().withUserConfiguration(StorageTestConfiguration::class.java)
            .withPropertyValues("homephoto.storage-root=$root", "homephoto.api-key=test")
            .run { context ->
                assertNull(context.startupFailure)
                assertIs<FileSystemAdapter>(context.getBean(StorageAdapter::class.java))
                assertNotNull(context.getBean(AssetIngestService::class.java))
                context.getBean(StorageAdapter::class.java).save(key, source(), checksum).commit()
                assertContentEquals(bytes, Files.readAllBytes(root.resolve(key)))
            }
    }

    @Test fun `separate original root does not move local database thumbnails or staging`() {
        val archive = temp.resolve("archive")
        val p = AppProperties(root, "test", originalStorage = AppProperties.OriginalStorageProperties(archive))
        val adapter = FileSystemAdapter(p)
        adapter.initialize()
        adapter.save(key, source(), checksum).commit()
        assertContentEquals(bytes, Files.readAllBytes(archive.resolve(key)))
        assertFalse(Files.exists(root.resolve(key)))
        assertEquals(root.resolve("db"), p.dbDir)
        assertEquals(root.resolve("thumbs"), p.thumbsDir)
        assertEquals(root.resolve("tmp"), p.uploadTmpDir)
        assertTrue(adapter.contains(archive.resolve("originals")))
        assertFalse(adapter.contains(temp))
        assertTrue(assertNotNull(adapter.space()).totalBytes > 0)
        assertEquals(archive.toString(), adapter.location)
    }

    @Test fun `missing storage root is an error for stat and delete rather than a missing object`() {
        val adapter = storage
        assertFailsWith<NoSuchFileException> { adapter.stat(key) }
        assertFailsWith<NoSuchFileException> { adapter.delete(key) }
    }

    @Test fun `a file at the storage root is an error rather than an empty archive`() {
        Files.writeString(root, "not a directory")
        assertFailsWith<java.nio.file.FileSystemException> { storage.stat(key) }
        assertFailsWith<java.nio.file.FileSystemException> { storage.delete(key) }
        assertFailsWith<java.nio.file.FileSystemException> { storage.space() }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AppProperties::class)
    @Import(FileSystemAdapter::class, AssetIngestService::class, ExifService::class, TakenAtResolver::class, AssetLocks::class)
    class StorageTestConfiguration
}
