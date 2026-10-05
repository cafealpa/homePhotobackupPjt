package com.homephoto.server.search

import com.homephoto.server.config.AppProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

class CaptionTextSearchLifecycleTest {
    @TempDir lateinit var dir: Path
    @Test fun `missing model never blocks caller or creates database and falls back`() {
        val service = CaptionTextSearch(CaptionTextSearchProperties(modelDir = dir.resolve("missing").toString()), AppProperties(dir, "test"))
        try {
            service.tick()
            val deadline = System.nanoTime() + 2_000_000_000
            while(service.status().state == "starting" && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("model_missing", service.status().state)
            assertEquals(0, service.status().indexed)
            assertFailsWith<IllegalStateException> { service.captionCandidates("공원") }
            assertFalse(Files.exists(dir.resolve("caption-search")))
        } finally { service.close() }
    }
    @Test fun `unapproved model bytes are rejected before native inference`() {
        Files.write(dir.resolve("model_quantized.onnx"), byteArrayOf(1))
        Files.write(dir.resolve("tokenizer.json"), byteArrayOf(1))
        CaptionTextEncoder(dir).use { encoder ->
            assertFailsWith<IllegalStateException> { encoder.prepare() }
        }
    }
}
