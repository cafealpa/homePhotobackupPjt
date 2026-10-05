package com.homephoto.server.search

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.*
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.sqrt

/** Pinned E5 ONNX + local tokenizer; no model hub calls or Python at runtime. */
class CaptionTextEncoder(private val directory: Path) : AutoCloseable {
    private var tokenizer: HuggingFaceTokenizer? = null
    private var session: OrtSession? = null
    private val env by lazy { OrtEnvironment.getEnvironment() }

    @Synchronized fun prepare() {
        if (session != null) return
        for ((name, expected) in HASHES) {
            val path = directory.resolve(name)
            check(Files.isRegularFile(path)) { "설명 검색 모델 파일이 없습니다." }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val bytes = ByteArray(65536)
                while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "설명 검색 모델 체크섬이 다릅니다." }
        }
        System.setProperty("ai.djl.offline", "true")
        System.setProperty("OPT_OUT_TRACKING", "true")
        val tok = HuggingFaceTokenizer.newInstance(directory.resolve("tokenizer.json"),
            mapOf("truncation" to "LONGEST_FIRST", "maxLength" to "512", "modelMaxLength" to "512", "padding" to "false"))
        try {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                session = env.createSession(directory.resolve("model_quantized.onnx").toString(), options)
            }
            tokenizer = tok
        } catch (e: Throwable) { tok.close(); throw e }
    }

    @Synchronized fun encode(text: String, query: Boolean): FloatArray {
        prepare()
        val encoded = tokenizer!!.encode((if (query) "query: " else "passage: ") + text.take(16000))
        val ids = encoded.ids
        require(ids.isNotEmpty() && ids.size <= 512)
        val mask = encoded.attentionMask
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            val shape = longArrayOf(1, ids.size.toLong())
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
            if ("token_type_ids" in session!!.inputNames)
                inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape)
            session!!.run(inputs).use { output ->
                val tensor = output[0] as OnnxTensor
                val dims = (tensor.info as TensorInfo).shape
                require(dims.contentEquals(longArrayOf(1, ids.size.toLong(), DIMENSION.toLong())))
                val data = tensor.floatBuffer
                val vector = FloatArray(DIMENSION)
                var count = 0
                for (token in ids.indices) {
                    for (d in vector.indices) { val value = data.get(); if (mask[token] != 0L) vector[d] += value }
                    if (mask[token] != 0L) count++
                }
                require(count > 0)
                for (d in vector.indices) vector[d] /= count
                val norm = sqrt(vector.sumOf { it.toDouble() * it })
                require(norm.isFinite() && norm > 0)
                for (d in vector.indices) vector[d] = (vector[d] / norm).toFloat()
                return vector
            }
        } finally { inputs.values.forEach { it.close() } }
    }
    @Synchronized override fun close() { session?.close(); tokenizer?.close(); session = null; tokenizer = null }
    companion object {
        const val DIMENSION = 384
        const val ID = "multilingual-e5-small-761b726d-int8-mean512-v1"
        val HASHES = mapOf(
            "model_quantized.onnx" to "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193",
            "tokenizer.json" to "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39")
        fun text(caption: String, tags: String?) = caption.trim() + "\n" + tags.orEmpty().replace(',', ' ').trim()
        fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
