package com.homephoto.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.FaceEngine
import com.homephoto.server.service.FaceGroupingService
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.*

/** 명시한 공개 샘플/모델만 읽으며 운영 DB와 HTTP 서버에 접근하지 않는다. */
object FaceEngineSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val props = AppProperties(Path.of("build/face-smoke"), "test", face = AppProperties.FaceProperties(true, args[0]))
        val engine = FaceEngine(props)
        try {
            val faces = engine.analyze(Path.of(args[1]))
            val expected = jacksonObjectMapper().readTree(Path.of(args[2]).toFile())
            assertEquals(expected.size(), faces.size)
            assertTrue(faces.isNotEmpty())
            for ((index, face) in faces.withIndex()) {
                val reference = expected[index]
                val vector = reference["vector"].map { it.floatValue() }.toFloatArray()
                val similarity = FaceGroupingService.cosine(face.vector, vector)
                assertTrue(similarity > 0.995, "face $index cosine=$similarity")
                for ((i, value) in listOf(face.x, face.y, face.w, face.h).withIndex()) {
                    assertTrue(abs(value - reference["box"][i].asDouble()) < 0.002)
                }
                println("face=$index cosine=$similarity dimensions=${face.vector.size}")
            }
        } finally { engine.close() }
    }
}
