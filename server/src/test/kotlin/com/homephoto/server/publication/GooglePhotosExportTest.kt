package com.homephoto.server.publication

import com.homephoto.server.config.AppProperties
import com.homephoto.server.service.ThumbnailService
import com.homephoto.server.storage.FileSystemAdapter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GooglePhotosExportTest {
    @TempDir lateinit var temp: Path
    private fun asset(time: String? = "2018-04-05T12:34:56", lat: Double? = 37.5665, lon: Double? = 126.9780) =
        PublicationAsset(1, "abc", "originals/A.jpg", "original.HEIC", time, "FILENAME", lat, lon)

    @Test fun `DB date and coordinates win and matching raw EXIF supplies only known offset and altitude`() {
        val samples = temp.resolve("samples")
        GooglePhotosMetadataPoc.prepare(samples)
        val props = AppProperties(temp.resolve("root"), "test")
        val originals = FileSystemAdapter(props)
        originals.initialize()
        Files.copy(samples.resolve("A.jpg"), props.originalsDir.resolve("A.jpg"))
        val provider = PublicationMetadataProvider(originals)
        val result = provider.resolve(asset())
        assertEquals("original.HEIC", result.originalFilename)
        assertEquals("2018-04-05T12:34:56", result.captureTime)
        assertEquals("+09:00", result.offset); assertEquals(38.5, result.altitude)
        val changed = provider.resolve(asset("2022-03-04T12:34:56", 20.0, 30.0))
        assertEquals("2022-03-04T12:34:56", changed.captureTime)
        assertEquals(20.0, changed.latitude); assertEquals(30.0, changed.longitude)
        assertNull(changed.offset); assertNull(changed.altitude)
        val missing = provider.resolve(asset(null, null, null))
        assertEquals(result.captureTime, missing.captureTime); assertEquals(result.latitude, missing.latitude)
        assertEquals(result.offset, missing.offset)
    }

    @Test fun `original read failure still exports known DB metadata without invented timezone`() {
        val props = AppProperties(temp.resolve("offline"), "test")
        val result = PublicationMetadataProvider(FileSystemAdapter(props)).resolve(asset())
        assertEquals("2018-04-05T12:34:56", result.captureTime)
        assertEquals(37.5665, result.latitude); assertNull(result.offset); assertNull(result.altitude)
        assertEquals(1, result.warnings.size)
    }

    @Test fun `export uses local temp files preserves original and thumbnail and cleans failed writes`() {
        val samples = temp.resolve("samples")
        GooglePhotosMetadataPoc.prepare(samples)
        val source = samples.resolve("source-thumbnail.jpg")
        val thumbnails = Mockito.mock(ThumbnailService::class.java)
        Mockito.`when`(thumbnails.thumbPath("abc", 1600)).thenReturn(source)
        val props = AppProperties(temp.resolve("local"), "test")
        val writer = ExportExifWriter()
        val export = GooglePhotosExport(props, thumbnails, PublicationMetadataProvider(FileSystemAdapter(props)), writer)
        val before = GooglePhotosExport.sha256(source)
        val prepared = export.prepare(asset())
        assertTrue(prepared.path.startsWith(props.uploadTmpDir.resolve("google-photos")))
        assertEquals(before, GooglePhotosExport.sha256(source)); assertEquals(prepared.sha256, GooglePhotosExport.sha256(prepared.path))
        assertFalse(Files.exists(props.originalsDir))
        Files.delete(prepared.path)
        val corrupt = Files.write(temp.resolve("corrupt.jpg"), byteArrayOf(1, 2, 3))
        Mockito.`when`(thumbnails.thumbPath("abc", 1600)).thenReturn(corrupt)
        assertFails { export.prepare(asset()) }
        assertEquals(0L, Files.list(props.uploadTmpDir.resolve("google-photos")).use { it.count() })
    }
}
