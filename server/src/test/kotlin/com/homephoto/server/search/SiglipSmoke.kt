package com.homephoto.server.search

import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.abs

/** Explicit real-model parity verification. Synthetic data only, no Spring/production DB. */
object SiglipSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val reference = ObjectMapper().readTree(Path.of(args[1]).toFile())
        SiglipEncoder(Path.of(args[0])).use { encoder ->
            encoder.prepare()
            val vectors = mutableListOf<FloatArray>()
            for (sample in reference["images"]) {
                val image = BufferedImage(sample["width"].asInt(), sample["height"].asInt(), BufferedImage.TYPE_INT_RGB)
                for (y in 0 until image.height) for (x in 0 until image.width)
                    image.setRGB(x, y, (((x * 7 + y * 3) % 256) shl 16) or (((x * 2 + y * 11) % 256) shl 8) or ((x * 13 + y * 5) % 256))
                val rgb = SiglipImageProcessor.pixels(image)
                val bytes = ByteArray(rgb.size * 3) { i -> (rgb[i / 3] shr (16 - i % 3 * 8)).toByte() }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(hash == sample["pixelsSha256"].asText()) { "Pillow resize mismatch: ${image.width}x${image.height}" }
                val vector = encoder.image(image)
                compare(vector, sample["vector"].map { it.floatValue() }.toFloatArray(), "image ${image.width}x${image.height}")
                vectors.add(vector)
            }
            for (sample in reference["texts"]) {
                val text = sample["text"].asText()
                check(encoder.tokens(text).contentEquals(sample["tokens"].map { it.asLong() }.toLongArray())) { "Tokenizer mismatch: $text" }
                compare(encoder.text(text), sample["vector"].map { it.floatValue() }.toFloatArray(), "text")
            }
            val root = Files.createTempDirectory("siglip-smoke-")
            try {
                LocalVectorIndex(root, 768).use { index ->
                    vectors.forEachIndexed { i, v -> index.put(i + 1L, i + 1L, "test-$i", 0, v) }
                    index.commit()
                    vectors.forEachIndexed { i, v -> check(index.search(v).first().id == i + 1L) }
                }
                LocalVectorIndex(root, 768).use { check(it.count() == vectors.size.toLong()) }
            } finally { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
        println("PASS: Python/JVM pixel, token, embedding parity; real Lucene ranking and reopen")
    }
    private fun compare(actual: FloatArray, expected: FloatArray, label: String) {
        check(actual.size == expected.size)
        val cosine = actual.indices.sumOf { actual[it].toDouble() * expected[it] }
        val error = actual.indices.maxOf { abs(actual[it] - expected[it]) }
        println("$label cosine=$cosine maxError=$error")
        check(cosine > .99999 && error < .0001) { "Embedding parity failed: $label" }
    }
}
