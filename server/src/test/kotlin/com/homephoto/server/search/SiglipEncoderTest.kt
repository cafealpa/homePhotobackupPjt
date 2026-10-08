package com.homephoto.server.search

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SiglipEncoderTest {
    @TempDir lateinit var root: Path
    @Test fun `missing or replaced model is rejected before native inference`() {
        SiglipEncoder(root).use { encoder ->
            assertTrue(assertThrows(IllegalStateException::class.java) { encoder.prepare() }.message!!.contains("없습니다"))
            SiglipEncoder.HASHES.keys.forEach { Files.writeString(root.resolve(it), "incorrect artifact") }
            assertTrue(assertThrows(IllegalStateException::class.java) { encoder.prepare() }.message!!.contains("체크섬"))
        }
    }
}
