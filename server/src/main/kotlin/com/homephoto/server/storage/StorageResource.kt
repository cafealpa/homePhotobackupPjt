package com.homephoto.server.storage

import org.springframework.core.io.AbstractResource
import java.io.IOException
import java.io.InputStream
import java.util.Objects

/** Spring의 Resource/Range 응답 계약을 유지하면서 skip된 앞부분은 저장소에서 읽지 않는다. */
class StorageResource(
    private val storage: StorageAdapter,
    private val key: String,
    private val size: Long,
) : AbstractResource() {
    override fun getDescription(): String = "original:$key"
    override fun getFilename(): String = key.substringAfterLast('/').substringAfterLast('\\')
    override fun contentLength(): Long = size
    override fun getInputStream(): InputStream = object : InputStream() {
        private var position = 0L
        private var input: InputStream? = null
        private var closed = false

        private fun stream(): InputStream {
            if (closed) throw IOException("stream closed")
            return input ?: storage.open(key, position).also { input = it }
        }

        override fun read(): Int = stream().read().also { if (it >= 0) position++ }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            Objects.checkFromIndexSize(offset, length, bytes.size)
            if (closed) throw IOException("stream closed")
            if (length == 0) return 0
            return stream().read(bytes, offset, length).also { if (it > 0) position += it }
        }

        override fun skip(count: Long): Long {
            if (closed) throw IOException("stream closed")
            if (count <= 0) return 0
            val limit = minOf(count, (size - position).coerceAtLeast(0))
            val skipped = input?.skip(limit) ?: limit
            position += skipped
            return skipped
        }

        override fun close() {
            closed = true
            input?.close()
        }
    }
}
