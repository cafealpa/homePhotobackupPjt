package com.homephoto.server.search

import com.fasterxml.jackson.databind.ObjectMapper
import com.homephoto.server.config.ApiKeyFilter
import com.homephoto.server.config.AppProperties
import com.homephoto.server.ProcessFixture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.*
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class PhotoSearchProcessTest {
    @TempDir lateinit var root: Path
    private val app get() = AppProperties(root, "process-test-api-key")
    private fun props(port: Int = ServerSocket(0).use { it.localPort }, enabled: Boolean = true) =
        PhotoSearchProperties(enabled, "http://127.0.0.1:$port", "process-test-token-0123456789012345", root.toString())
    private fun installed() {
        val python = if (System.getProperty("os.name").startsWith("Windows")) ".venv-search/Scripts/python.exe" else ".venv-search/bin/python"
        for (file in listOf(python, "search_service.py", "face_search.py", "search-model/homephoto-model.json")) {
            Files.createDirectories(root.resolve(file).parent)
            Files.writeString(root.resolve(file), "fixture")
        }
    }
    private fun fixture() = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", Path.of(ProcessFixture::class.java.protectionDomain.codeSource.location.toURI()).toString(),
        ProcessFixture::class.java.name, "hang").start()

    @Test fun `start deduplicates passes secrets via environment and stops only owned process`() {
        installed()
        val config = props()
        val service = PhotoSearchProcess(config, app, ObjectMapper())
        var launches = 0
        service.launch = { builder ->
            launches++
            assertEquals(2, builder.command().size)
            assertEquals(app.apiKey, builder.environment()["HOMEPHOTO_API_KEY"])
            assertEquals(config.token, builder.environment()["HOMEPHOTO_SEARCH_TOKEN"])
            assertFalse(builder.command().joinToString().contains(config.token))
            fixture()
        }
        try {
            assertEquals("stopped", service.status().state)
            assertEquals("starting", service.start().state)
            assertEquals("starting", service.start().state)
            assertEquals(1, launches)
            assertTrue(service.status().canStop)
            assertEquals("stopped", service.stop().state)
            assertFalse(Files.exists(root.resolve("search-data/service.pid")))
        } finally { service.shutdown() }
    }

    @Test fun `disabled missing installation and external pid never launch`() {
        val disabled = PhotoSearchProcess(props(enabled = false), app, ObjectMapper())
        assertEquals("disabled", disabled.status().state)
        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) { disabled.start() }
        val service = PhotoSearchProcess(props(), app, ObjectMapper())
        assertEquals("not_installed", service.status().state)
        installed()
        Files.createDirectories(root.resolve("search-data"))
        Files.writeString(root.resolve("search-data/service.pid"), ProcessHandle.current().pid().toString())
        assertEquals("external", service.start().state)
        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) { service.stop() }
        assertTrue(ProcessHandle.current().isAlive)
    }

    @Test fun `authenticated health reports counts without claiming an external process`() {
        val http = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val config = props(http.address.port)
        http.createContext("/health") { exchange ->
            assertEquals("Bearer ${config.token}", exchange.requestHeaders.getFirst("Authorization"))
            val bytes = """{"status":"ready","indexed_photos":3,"indexed_faces":6}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            val service = PhotoSearchProcess(config, app, ObjectMapper())
            val state = service.start()
            assertEquals("running", state.state)
            assertEquals(3L, state.indexedPhotos)
            assertEquals(6L, state.indexedFaces)
            assertFalse(state.canStart)
            assertFalse(state.canStop)
        } finally { http.stop(0) }
    }

    @Test fun `admin controls require api auth and action header`() {
        val service = PhotoSearchProcess(props(), app, ObjectMapper())
        val mvc = MockMvcBuilders.standaloneSetup(PhotoSearchProcessController(service))
            .addFilters<StandaloneMockMvcBuilder>(ApiKeyFilter(app)).build()
        assertEquals(401, mvc.perform(get("/api/v1/admin/search-service")).andReturn().response.status)
        assertEquals(401, mvc.perform(post("/api/v1/admin/search-service/start").header("X-HomePhoto-Action", "search-service")).andReturn().response.status)
        assertTrue(mvc.perform(post("/api/v1/admin/search-service/start").header("X-Api-Key", app.apiKey)).andReturn().response.status in 400..499)
        assertEquals(200, mvc.perform(get("/api/v1/admin/search-service").header("X-Api-Key", app.apiKey)).andReturn().response.status)
    }

    @Test fun `launch errors do not disclose exception text or credentials`() {
        installed()
        val service = PhotoSearchProcess(props(), app, ObjectMapper())
        service.launch = { throw IllegalStateException("secret-error-payload") }
        val error = assertThrows(org.springframework.web.server.ResponseStatusException::class.java) { service.start() }
        assertFalse(error.reason!!.contains("secret-error-payload"))
        assertEquals("failed", service.status().state)
        assertTrue(service.status().canStart)
    }
}
