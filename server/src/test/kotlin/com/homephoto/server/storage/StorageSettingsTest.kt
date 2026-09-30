package com.homephoto.server.storage

import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.SettingsService
import com.homephoto.server.service.ThumbnailService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.FileSystemResource
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class StorageSettingsTest {
    @TempDir lateinit var temp: Path
    private val config get() = temp.resolve("config/application.yml")
    private val props get() = AppProperties(temp.resolve("local"), "test-key")
    private fun settings(p: AppProperties) = SettingsService(p, mock(ThumbnailService::class.java), config.toString())
    private fun rebound(): AppProperties {
        val source = YamlPropertySourceLoader().load("saved", FileSystemResource(config)).single()
        return Binder(ConfigurationPropertySources.from(source)).bind("homephoto", Bindable.of(AppProperties::class.java)).get()
    }
    private fun saved(): Map<String, Any?> = Yaml(SafeConstructor(LoaderOptions())).load(Files.readString(config))

    @Test fun `web save binds a separate original root and leaves local data paths unchanged`() {
        val p = props
        val service = settings(p)
        val archive = temp.resolve("archive")
        val result = service.save(service.current().copy(originalStorageRoot = archive.toString()))
        assertEquals(listOf("originalStorageRoot"), result.restartRequired)
        assertNull(p.originalStorage.root) // 현재 프로세스는 재시작 전 루트를 유지한다.
        assertEquals(archive.toString().replace('\\', '/'), service.current().originalStorageRoot)
        val next = rebound()
        assertEquals(archive, next.originalStorageRoot)
        assertEquals(p.dbDir, next.dbDir)
        assertEquals(p.thumbsDir, next.thumbsDir)
        assertEquals(p.uploadTmpDir, next.uploadTmpDir)
    }

    @Test fun `older requests preserve a pending root and unrelated YAML settings`() {
        Files.createDirectories(config.parent)
        Files.writeString(config, """
            homephoto:
              mcp:
                enabled: false
                mode: local
              caption:
                custom-option: keep
            server:
              port: 12345
        """.trimIndent())
        val service = settings(props)
        val archive = temp.resolve("pending-archive")
        service.save(service.current().copy(originalStorageRoot = archive.toString()))
        service.save(service.current().copy(originalStorageRoot = null, trashRetentionDays = 45))
        assertEquals(archive, rebound().originalStorageRoot)
        val values = saved()
        assertEquals(12345, (values["server"] as Map<*, *>)["port"])
        val homephoto = values["homephoto"] as Map<*, *>
        assertEquals("local", (homephoto["mcp"] as Map<*, *>)["mode"])
        assertEquals("keep", (homephoto["caption"] as Map<*, *>)["custom-option"])
        assertEquals(45L, rebound().trashRetentionDays)
    }

    @Test fun `UNC path round trips and an explicit blank resets to the local root`() {
        val archive = Path.of("""\\STORAGE-PC\PhotoArchive""")
        val p = props.copy(originalStorage = AppProperties.OriginalStorageProperties(archive))
        val service = settings(p)
        service.save(service.current().copy(originalStorageRoot = null))
        assertEquals(archive, rebound().originalStorageRoot)
        service.save(service.current().copy(originalStorageRoot = ""))
        service.save(service.current().copy(originalStorageRoot = null))
        assertEquals("", service.current().originalStorageRoot)
        assertNull(rebound().originalStorage.root)
        assertEquals(p.storageRoot, rebound().originalStorageRoot)
    }

    @Test fun `invalid original root does not overwrite settings or mutate active properties`() {
        val p = props
        val service = settings(p)
        service.save(service.current())
        val before = Files.readAllBytes(config)
        assertFailsWith<IllegalArgumentException> {
            service.save(service.current().copy(originalStorageRoot = "relative/archive", apiKey = "changed-key"))
        }
        assertContentEquals(before, Files.readAllBytes(config))
        assertEquals("test-key", p.apiKey)
    }

    @Test fun `invalid existing YAML is preserved and runtime settings are not applied`() {
        Files.createDirectories(config.parent)
        Files.writeString(config, "homephoto: [invalid")
        val p = props
        val service = settings(p)
        assertFailsWith<Exception> { service.save(service.current().copy(apiKey = "changed-key")) }
        assertEquals("homephoto: [invalid", Files.readString(config))
        assertEquals("test-key", p.apiKey)
    }

    @Test fun `concurrent old and new clients cannot lose the new original root`() {
        val service = settings(props)
        val archive = temp.resolve("archive")
        val oldRequest = service.current().copy(originalStorageRoot = null)
        val newRequest = service.current().copy(originalStorageRoot = archive.toString())
        val gate = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(oldRequest, newRequest).map { request ->
                pool.submit(java.util.concurrent.Callable { gate.await(); service.save(request) })
            }
            gate.countDown()
            tasks.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(archive, rebound().originalStorageRoot)
        } finally { pool.shutdownNow() }
    }

    @Test fun `originals cannot be placed inside local database thumbnails or staging`() {
        val p = props
        val service = settings(p)
        for (dir in listOf(p.dbDir, p.thumbsDir, p.uploadTmpDir)) {
            assertFailsWith<IllegalArgumentException> {
                service.save(service.current().copy(originalStorageRoot = dir.toString()))
            }
        }
        assertFalse(Files.exists(config))
    }
}
