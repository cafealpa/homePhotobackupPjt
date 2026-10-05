package com.homephoto.server.service

import com.homephoto.server.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 미분류 얼굴만 배정한다. 기존 묶음 번호/이름/사용자 이동·숨김은 재계산하지 않는다. */
@Service
class FaceGroupingService {
    fun assignPending(limit: Int = 100): Int = transaction {
        fun visible() = (Faces innerJoin Assets).selectAll().where {
            (Faces.hidden eq false) and Assets.deletedAt.isNull() and Assets.purgedAt.isNull()
        }
        val pending = visible().andWhere { Faces.clusterId.isNull() }.orderBy(Faces.id).limit(limit).toList()
        if (pending.isEmpty()) return@transaction 0
        data class Known(val asset: Long, val cluster: Int, val vector: FloatArray)
        val known = visible().andWhere { Faces.clusterId.isNotNull() }.map {
            Known(it[Faces.assetId], it[Faces.clusterId]!!, decode(it[Faces.embedding].bytes))
        }.toMutableList()
        val maxCluster = Faces.clusterId.max()
        var next = (Faces.select(maxCluster).single()[maxCluster] ?: -1) + 1
        for (row in pending) {
            val vector = decode(row[Faces.embedding].bytes)
            val occupied = known.filter { it.asset == row[Faces.assetId] }.map { it.cluster }.toSet()
            val best = known.asSequence().filter { it.cluster !in occupied }
                .map { it to cosine(vector, it.vector) }.filter { it.second >= 0.55 }.maxByOrNull { it.second }?.first
            val cluster = best?.cluster ?: next++
            Faces.update({ Faces.id eq row[Faces.id] }) {
                it[clusterId] = cluster
                // 자동 일치가 사용자의 확정 이름을 대신하지 않는다.
            }
            known.add(Known(row[Faces.assetId], cluster, vector))
        }
        pending.size
    }

    companion object {
        fun encode(vector: FloatArray): ByteArray = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { vector.forEach { putFloat(it) } }.array()
        fun decode(bytes: ByteArray): FloatArray {
            require(bytes.size == 2048) { "얼굴 벡터 크기 오류" }
            return FloatArray(512).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
        }
        internal fun cosine(a: FloatArray, b: FloatArray): Double {
            var dot = 0.0; var aa = 0.0; var bb = 0.0
            for (i in a.indices) { dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
            return if (aa <= 0 || bb <= 0) -1.0 else dot / sqrt(aa * bb)
        }
    }
}
