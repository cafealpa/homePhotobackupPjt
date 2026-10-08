package com.homephoto.server.search

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.*
import java.awt.image.BufferedImage
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

interface PhotoEncoder : AutoCloseable {
    fun prepare()
    fun text(text: String): FloatArray
    fun image(image: BufferedImage): FloatArray
}

/** Fixed FP32 SigLIP 2 artifact. No downloads or Python processes at runtime. */
class SiglipEncoder(private val directory: Path) : PhotoEncoder {
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private var tokenizer: HuggingFaceTokenizer? = null
    private var textSession: OrtSession? = null
    private var visionSession: OrtSession? = null

    @Synchronized override fun prepare() {
        if (textSession != null) return
        for ((name, expected) in HASHES) {
            val path = directory.resolve(name)
            check(Files.isRegularFile(path)) { "SigLIP 모델 파일이 없습니다: $name" }
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val bytes = ByteArray(65536)
                while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected) { "SigLIP 모델 체크섬이 다릅니다: $name" }
        }
        System.setProperty("ai.djl.offline", "true")
        System.setProperty("OPT_OUT_TRACKING", "true")
        try {
            tokenizer = HuggingFaceTokenizer.newInstance(directory.resolve("tokenizer.json"),
                mapOf("truncation" to "LONGEST_FIRST", "maxLength" to "64", "modelMaxLength" to "64", "padding" to "MAX_LENGTH"))
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                visionSession = env.createSession(directory.resolve("vision_model.onnx").toString(), options)
                textSession = env.createSession(directory.resolve("text_model.onnx").toString(), options)
            }
            check(textSession!!.inputNames == setOf("input_ids")) { "SigLIP 텍스트 모델 입력이 다릅니다." }
            check(visionSession!!.inputNames == setOf("pixel_values")) { "SigLIP 이미지 모델 입력이 다릅니다." }
        } catch (e: Throwable) { close(); throw e }
    }
    internal fun tokens(text: String): LongArray {
        prepare()
        val ids = tokenizer!!.encode(text).ids
        require(ids.size == 64) { "SigLIP 토큰 수가 다릅니다: ${ids.size}" }
        return ids
    }
    @Synchronized override fun text(text: String): FloatArray {
        val ids = tokens(text)
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, 64)).use {
            return run(textSession!!, "input_ids", it)
        }
    }
    @Synchronized override fun image(image: BufferedImage): FloatArray {
        prepare()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(SiglipImageProcessor.tensor(image)), longArrayOf(1, 3, 224, 224)).use {
            return run(visionSession!!, "pixel_values", it)
        }
    }
    private fun run(session: OrtSession, name: String, tensor: OnnxTensor): FloatArray =
        session.run(mapOf(name to tensor), setOf("pooler_output")).use { output ->
            val result = output.get("pooler_output").orElseThrow() as OnnxTensor
            require(result.info.shape.contentEquals(longArrayOf(1, DIMENSION.toLong())))
            normalized(FloatArray(DIMENSION).also { result.floatBuffer.get(it) })
        }
    @Synchronized override fun close() {
        textSession?.close(); visionSession?.close(); tokenizer?.close()
        textSession = null; visionSession = null; tokenizer = null
    }
    companion object {
        const val DIMENSION = 768
        const val ID = "siglip2-base-patch16-224-ba1f3b08-fp32-pillow-v1"
        val HASHES = mapOf(
            "text_model.onnx" to "baf12d941beabafafb14f7b4adb38dc15be18681b964a84410ec53d9d65e6293",
            "vision_model.onnx" to "c0573e3f4140c3a7c4e9cc5912bd6b26a033b46a6a8e8af26cbea262b163bcad",
            "tokenizer.json" to "cb9140fae3ac5122c972d37adf83e1248471a38147ad76f8215c8872c6fd8322")
    }
}
