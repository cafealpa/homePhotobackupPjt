package com.homephoto.server.search

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal fun normalized(vector: FloatArray): FloatArray {
    require(vector.all { it.isFinite() }) { "벡터에 유효하지 않은 값이 있습니다." }
    val norm = sqrt(vector.sumOf { it.toDouble() * it })
    require(norm.isFinite() && norm > 1e-12) { "빈 벡터는 검색할 수 없습니다." }
    return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
}

internal fun faceVector(bytes: ByteArray): FloatArray {
    require(bytes.size == 512 * 4) { "얼굴 벡터는 512차원이어야 합니다." }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return normalized(FloatArray(512).also { buffer.get(it) })
}
