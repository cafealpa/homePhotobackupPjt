package com.homephoto.server.service

import ai.onnxruntime.*
import com.homephoto.server.config.AppProperties
import jakarta.annotation.PreDestroy
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.springframework.stereotype.Service
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.*

data class DetectedFace(val x: Double, val y: Double, val w: Double, val h: Double, val vector: FloatArray)

/** buffalo_l의 기존 640px 검출 / 112px 정렬 / RGB NCHW 계약을 유지한다. */
@Service
class FaceEngine(private val props: AppProperties) {
    private var detector: OrtSession? = null
    private var recognizer: OrtSession? = null
    private var loaded: Path? = null
    private val env by lazy { OrtEnvironment.getEnvironment() }

    fun modelDirectory(): Path = props.face.modelDir.takeIf { it.isNotBlank() }?.let(Path::of)
        ?: listOf(Path.of("models", "buffalo_l"), Path.of(System.getProperty("user.home"), ".insightface", "models", "buffalo_l"))
            .firstOrNull { Files.isRegularFile(it.resolve("det_10g.onnx")) && Files.isRegularFile(it.resolve("w600k_r50.onnx")) }
        ?: Path.of("models", "buffalo_l")

    @Synchronized fun prepare() {
        val dir = modelDirectory().toAbsolutePath().normalize()
        if (loaded == dir) return
        close()
        // 다른 임베딩 모델이 기존 512차원 벡터에 섞이지 않도록 알려진 buffalo_l만 허용한다.
        for ((name, hash) in MODEL_HASHES) {
            check(Files.isRegularFile(dir.resolve(name))) { "얼굴 모델이 없습니다: ${dir.resolve(name)}" }
            ReleaseArtifacts.verify(dir.resolve(name), hash)
        }
        nu.pattern.OpenCV.loadLocally()
        try {
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                detector = env.createSession(dir.resolve("det_10g.onnx").toString(), options)
                recognizer = env.createSession(dir.resolve("w600k_r50.onnx").toString(), options)
            }
            check(detector!!.numOutputs == 9L && recognizer!!.numOutputs == 1L) { "지원하지 않는 얼굴 모델 출력" }
            loaded = dir
        } catch (e: Exception) { close(); throw e }
    }

    @Synchronized fun analyze(path: Path): List<DetectedFace> {
        prepare()
        val raw = MatOfByte(*Files.readAllBytes(path))
        val image = try { Imgcodecs.imdecode(raw, Imgcodecs.IMREAD_COLOR) } finally { raw.release() }
        try {
            require(!image.empty()) { "얼굴 분석용 이미지 디코딩 실패" }
            return detect(image).map { face ->
                val aligned = Mat()
                val transform = Mat(2, 3, CvType.CV_64F)
                try {
                    transform.put(0, 0, *alignment(face.points))
                    Imgproc.warpAffine(image, aligned, transform, Size(112.0, 112.0), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(0.0))
                    val vector = run(recognizer!!, aligned, 127.5f).single()
                    require(vector.size == 512 && vector.all { it.isFinite() }) { "잘못된 얼굴 임베딩" }
                    val norm = sqrt(vector.sumOf { it.toDouble() * it })
                    require(norm > 1e-8)
                    for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
                    val x = face.box[0].coerceIn(0.0, image.cols().toDouble())
                    val y = face.box[1].coerceIn(0.0, image.rows().toDouble())
                    DetectedFace(x / image.cols(), y / image.rows(),
                        (face.box[2].coerceIn(x, image.cols().toDouble()) - x) / image.cols(),
                        (face.box[3].coerceIn(y, image.rows().toDouble()) - y) / image.rows(), vector)
                } finally { aligned.release(); transform.release() }
            }
        } finally { image.release() }
    }

    private data class Detection(val score: Float, val box: DoubleArray, val points: DoubleArray)
    private fun detect(image: Mat): List<Detection> {
        val ratio = min(640.0 / image.cols(), 640.0 / image.rows())
        val width = (image.cols() * ratio).toInt().coerceAtLeast(1)
        val height = (image.rows() * ratio).toInt().coerceAtLeast(1)
        val scale = height.toDouble() / image.rows() // InsightFace의 det_scale과 동일
        val padded = Mat.zeros(640, 640, CvType.CV_8UC3)
        val resized = Mat()
        val outputs = try {
            Imgproc.resize(image, resized, Size(width.toDouble(), height.toDouble()))
            val roi = padded.submat(0, height, 0, width)
            try { resized.copyTo(roi) } finally { roi.release() }
            run(detector!!, padded, 128f)
        } finally { padded.release(); resized.release() }
        val candidates = mutableListOf<Detection>()
        for ((level, stride) in listOf(8, 16, 32).withIndex()) {
            val scores = outputs[level]; val boxes = outputs[level + 3]; val points = outputs[level + 6]
            val size = 640 / stride
            check(scores.size == size * size * 2 && boxes.size == scores.size * 4 && points.size == scores.size * 10)
            for (i in scores.indices) {
                if (scores[i] < 0.5f) continue
                val cx = (i / 2 % size) * stride.toDouble(); val cy = (i / 2 / size) * stride.toDouble()
                val box = doubleArrayOf(cx - boxes[i * 4] * stride, cy - boxes[i * 4 + 1] * stride,
                    cx + boxes[i * 4 + 2] * stride, cy + boxes[i * 4 + 3] * stride).map { it / scale }.toDoubleArray()
                val landmarks = DoubleArray(10) { j -> ((if (j % 2 == 0) cx else cy) + points[i * 10 + j] * stride) / scale }
                candidates.add(Detection(scores[i], box, landmarks))
            }
        }
        val kept = mutableListOf<Detection>()
        for (face in candidates.sortedByDescending { it.score }) {
            if (kept.none { overlap(it.box, face.box) > 0.4 }) kept.add(face)
        }
        return kept
    }

    private fun run(session: OrtSession, image: Mat, std: Float): List<FloatArray> {
        val pixels = ByteArray(image.rows() * image.cols() * 3)
        image.get(0, 0, pixels)
        val plane = image.rows() * image.cols()
        val input = FloatArray(plane * 3)
        for (i in 0 until plane) for (c in 0..2) input[c * plane + i] = ((pixels[i * 3 + 2 - c].toInt() and 255) - 127.5f) / std
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, image.rows().toLong(), image.cols().toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                return (0 until result.size()).map { index ->
                    val buffer = (result[index] as OnnxTensor).floatBuffer
                    FloatArray(buffer.remaining()).also { buffer.get(it) }
                }
            }
        }
    }

    @PreDestroy @Synchronized fun close() { detector?.close(); recognizer?.close(); detector = null; recognizer = null; loaded = null }

    companion object {
        val MODEL_HASHES = mapOf("det_10g.onnx" to "5838f7fe053675b1c7a08b633df49e7af5495cee0493c7dcf6697200b85b5b91",
            "w600k_r50.onnx" to "4c06341c33c2ca1f86781dab0e829f88ad5b64be9fba56e56bc9ebdefc619e43")
        private val target = doubleArrayOf(38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041)
        /** 5개 랜드마크의 최소제곱 유사 변환. 반사 없이 ArcFace 정렬 기준으로 옮긴다. */
        internal fun alignment(points: DoubleArray): DoubleArray {
            require(points.size == 10)
            val sx = (0..4).sumOf { points[it * 2] } / 5; val sy = (0..4).sumOf { points[it * 2 + 1] } / 5
            val tx = (0..4).sumOf { target[it * 2] } / 5; val ty = (0..4).sumOf { target[it * 2 + 1] } / 5
            var denom = 0.0; var a = 0.0; var b = 0.0
            for (i in 0..4) {
                val x = points[i * 2] - sx; val y = points[i * 2 + 1] - sy
                val u = target[i * 2] - tx; val v = target[i * 2 + 1] - ty
                denom += x * x + y * y; a += x * u + y * v; b += x * v - y * u
            }
            require(denom > 1e-8) { "얼굴 정렬 좌표가 겹칩니다" }
            a /= denom; b /= denom
            return doubleArrayOf(a, -b, tx - a * sx + b * sy, b, a, ty - b * sx - a * sy)
        }
        private fun overlap(a: DoubleArray, b: DoubleArray): Double {
            val intersection = max(0.0, min(a[2], b[2]) - max(a[0], b[0]) + 1) * max(0.0, min(a[3], b[3]) - max(a[1], b[1]) + 1)
            return intersection / ((a[2] - a[0] + 1) * (a[3] - a[1] + 1) + (b[2] - b[0] + 1) * (b[3] - b[1] + 1) - intersection)
        }
    }
}
