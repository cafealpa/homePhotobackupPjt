package com.homephoto.server.search

import java.awt.image.BufferedImage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Pillow RGB bilinear resize: antialias when shrinking, separable 8-bit passes, 22-bit coefficients. */
internal object SiglipImageProcessor {
    private data class Kernel(val start: Int, val weights: IntArray)
    private fun kernels(input: Int, output: Int): List<Kernel> {
        val scale = input.toDouble() / output
        val support = max(1.0, scale)
        return List(output) { p ->
            val center = (p + 0.5) * scale
            val start = max(0, (center - support + 0.5).toInt())
            val end = min(input, (center + support + 0.5).toInt())
            val weights = DoubleArray(end - start) { max(0.0, 1.0 - kotlin.math.abs((it + start - center + 0.5) / support)) }
            val total = weights.sum()
            Kernel(start, IntArray(weights.size) { floor(weights[it] / total * (1 shl 22) + 0.5).toInt() })
        }
    }
    internal fun pixels(image: BufferedImage): IntArray {
        val width = image.width
        val height = image.height
        require(width > 0 && height > 0)
        val source = image.getRGB(0, 0, width, height, null, 0, width)
        val horizontal = IntArray(224 * height)
        val xs = kernels(width, 224)
        for (y in 0 until height) for (x in 0 until 224) {
            val kernel = xs[x]
            var rgb = 0
            for (shift in intArrayOf(16, 8, 0)) {
                var value = 1 shl 21
                for (k in kernel.weights.indices) value += ((source[y * width + kernel.start + k] shr shift) and 255) * kernel.weights[k]
                rgb = rgb or ((value shr 22).coerceIn(0, 255) shl shift)
            }
            horizontal[y * 224 + x] = rgb
        }
        val output = IntArray(224 * 224)
        val ys = kernels(height, 224)
        for (y in 0 until 224) for (x in 0 until 224) {
            val kernel = ys[y]
            var rgb = 0
            for (shift in intArrayOf(16, 8, 0)) {
                var value = 1 shl 21
                for (k in kernel.weights.indices) value += ((horizontal[(kernel.start + k) * 224 + x] shr shift) and 255) * kernel.weights[k]
                rgb = rgb or ((value shr 22).coerceIn(0, 255) shl shift)
            }
            output[y * 224 + x] = rgb
        }
        return output
    }
    fun tensor(image: BufferedImage): FloatArray {
        val rgb = pixels(image)
        val data = FloatArray(3 * 224 * 224)
        for (c in 0..2) for (p in rgb.indices)
            data[c * rgb.size + p] = (((rgb[p] shr (16 - c * 8)) and 255) / 255f - 0.5f) / 0.5f
        return data
    }
}
