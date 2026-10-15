package com.homephoto.server.search

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

class SearchModelInstallerTest {
    @TempDir lateinit var dir: Path
    private val bytes = "verified-model".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun model() = SearchModelInstaller.Model("test", "test", dir, "https://example.invalid/pinned", mapOf("model.onnx" to hash, "tokenizer.json" to hash))

    @Test fun `valid copied files are reused without network and missing files use fixed paths`() {
        Files.write(dir.resolve("model.onnx"), bytes)
        val urls = mutableListOf<String>()
        install(model(), source = { urls.add(it); bytes.inputStream() })
        assertEquals(listOf("https://example.invalid/pinned/tokenizer.json"), urls)
        install(model(), source = { error("verified files must not download") })
        assertEquals(hash, modelHash(dir.resolve("model.onnx")))
        assertEquals(hash, modelHash(dir.resolve("tokenizer.json")))
    }

    @Test fun `failed checksum preserves old file and removes incomplete download`() {
        Files.writeString(dir.resolve("model.onnx"), "old-model")
        assertFailsWith<ModelChecksumException> { install(model(), source = { "bad-download".byteInputStream() }) }
        assertEquals("old-model", Files.readString(dir.resolve("model.onnx")))
        Files.list(dir).use { assertEquals(listOf("model.onnx"), it.map { file -> file.fileName.toString() }.toList()) }
    }

    @Test fun `interrupted transfer preserves existing file and can retry`() {
        Files.writeString(dir.resolve("model.onnx"), "old-model")
        assertFailsWith<java.io.IOException> {
            install(model(), source = { object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("offline") } })
        }
        assertEquals("old-model", Files.readString(dir.resolve("model.onnx")))
        val urls = mutableListOf<String>()
        install(model(), source = { urls.add(it); bytes.inputStream() })
        assertEquals("https://example.invalid/pinned/onnx/model.onnx", urls.first())
        assertEquals(hash, modelHash(dir.resolve("model.onnx")))
    }

    @Test fun `status shows absolute configured paths and only approved model ids are accepted`() {
        SearchModelInstaller(PhotoSearchProperties(modelDir = dir.resolve("photo").toString()),
            CaptionTextSearchProperties(modelDir = dir.resolve("scene").toString())).use { installer ->
            val status = installer.status()
            assertEquals(2, status.size)
            assertEquals(dir.resolve("photo").toString(), status.first().directory)
            assertEquals(SiglipEncoder.HASHES.keys.toList(), status.first().missing)
            assertFailsWith<org.springframework.web.server.ResponseStatusException> { installer.start("../other") }
        }
    }

    @Test fun `download is asynchronous rejects duplicates and releases active work after failure`() {
        val activity = com.homephoto.server.service.ServerActivity()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        SearchModelInstaller(PhotoSearchProperties(modelDir = dir.resolve("photo").toString()),
            CaptionTextSearchProperties(modelDir = dir.resolve("scene").toString()), activity).use { installer ->
            installer.downloadSource = {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                throw java.io.IOException("offline")
            }
            try {
                installer.start("photo")
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(installer.status().all { it.busy })
                assertEquals(1, activity.count())
                assertFailsWith<org.springframework.web.server.ResponseStatusException> { installer.start("scene") }
            } finally { release.countDown() }
            val deadline = System.nanoTime() + 5_000_000_000
            while (activity.count() != 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(0, activity.count())
            assertEquals("failed", installer.status().first().state)
            assertFalse(installer.status().first().busy)
        }
    }

    @Test fun `model endpoints require admin authentication and dedicated action header`() {
        SearchModelInstaller(PhotoSearchProperties(modelDir = dir.resolve("photo").toString()),
            CaptionTextSearchProperties(modelDir = dir.resolve("scene").toString())).use { installer ->
            val mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(SearchModelController(installer))
                .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(com.homephoto.server.config.ApiKeyFilter(
                    com.homephoto.server.config.AppProperties(dir, "test-key"))).build()
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/search-models"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized)
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/admin/search-models").header("X-Api-Key", "test-key"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/search-models/photo/download").header("X-Api-Key", "test-key"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound)
        }
    }
}
