package com.homephoto.server.storage

import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.AtomicFiles
import com.homephoto.server.service.ProcessMonitor
import org.springframework.stereotype.Component
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Objects

/** 원본 전용 root와 기존 original_path를 조합하는 파일시스템 구현. */
@Component
class FileSystemAdapter(props: AppProperties) : StorageAdapter {
    private val root = props.originalStorageRoot.toAbsolutePath().normalize()
    private val realRoot by lazy { root.toRealPath() }
    override val location: String get() = root.toString()

    override fun initialize() {
        Files.createDirectories(root.resolve("originals"))
    }

    override fun space(): StorageAdapter.Space {
        verifyRoot()
        val store = Files.getFileStore(root)
        return StorageAdapter.Space(store.totalSpace, store.usableSpace)
    }

    override fun contains(path: Path): Boolean =
        path.toAbsolutePath().normalize().startsWith(root) || path.toRealPath().startsWith(realRoot)

    override fun save(key: String, source: Path, checksum: String, moveSource: Boolean): StorageAdapter.Write {
        val target = resolve(key)
        Files.createDirectories(target.parent)
        val moved = when {
            stat(key) != null -> {
                check(sha256(target) == checksum) { "stored file hash mismatch: $target" }
                false
            }
            moveSource && Files.getFileStore(source) == Files.getFileStore(target.parent) -> {
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                    true
                } catch (_: AtomicMoveNotSupportedException) {
                    copy(source, target)
                    false
                }
            }
            else -> {
                copy(source, target)
                false
            }
        }
        return object : StorageAdapter.Write {
            override fun commit() {
                if (moveSource && !moved && source.toAbsolutePath().normalize() != target) {
                    Files.deleteIfExists(source)
                }
            }

            override fun rollback() {
                if (moved) Files.move(target, source)
                // DB 실패 시 복사 입력은 보존하며 대상 파일은 기존 ingest처럼 남긴다.
            }
        }
    }

    override fun stat(key: String): StorageAdapter.Stat? = try {
        StorageAdapter.Stat(Files.readAttributes(resolve(key), BasicFileAttributes::class.java).size())
    } catch (_: NoSuchFileException) {
        // 공유/드라이브 자체가 사라진 경우를 원본 파일 부재로 취급하지 않는다.
        verifyRoot()
        null
    }

    override fun open(key: String, offset: Long, length: Long?): InputStream {
        require(offset >= 0) { "offset must be non-negative" }
        require(length == null || length >= 0) { "length must be non-negative" }
        val channel = FileChannel.open(resolve(key), StandardOpenOption.READ)
        try {
            channel.position(offset)
            val input = Channels.newInputStream(channel)
            return if (length == null) input else LimitedInputStream(input, length)
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    override fun delete(key: String) {
        if (!Files.deleteIfExists(resolve(key))) verifyRoot()
    }

    private fun verifyRoot() {
        if (!Files.readAttributes(root, BasicFileAttributes::class.java).isDirectory) {
            throw FileSystemException(root.toString(), null, "storage root is not a directory")
        }
    }

    override fun <T> withReadableFile(key: String, reader: (Path) -> T): T = reader(resolve(key))

    private fun resolve(key: String): Path {
        val relative = Path.of(key)
        require(key.isNotBlank() && !relative.isAbsolute) { "storage key must be a relative path" }
        val target = root.resolve(relative).normalize()
        require(target != root && target.startsWith(root)) { "storage key must stay inside the storage root" }
        return target
    }

    private fun copy(source: Path, target: Path) {
        AtomicFiles.write(target) { temp ->
            ProcessMonitor.stage("원본 파일 복사")
            ProcessMonitor.interruptible {
                Files.newInputStream(source).use { input -> Files.newOutputStream(temp).use { output ->
                    val buffer=ByteArray(256 * 1024)
                    while(true) {
                        ProcessMonitor.checkpoint()
                        val size=input.read(buffer); if(size < 0) break
                        output.write(buffer,0,size)
                    }
                } }
            }
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class LimitedInputStream(private val input: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            return input.read().also { if (it >= 0) remaining-- }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            Objects.checkFromIndexSize(offset, length, bytes.size)
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val read = input.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
            if (read > 0) remaining -= read
            return read
        }

        override fun close() = input.close()
    }
}
