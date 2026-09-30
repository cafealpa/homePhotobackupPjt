package com.homephoto.server.publication

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class GooglePhotosMetadataPocTest {
    @TempDir lateinit var temp: Path

    @Test fun `five fixtures retain compressed pixels and independently readable metadata`() {
        val dir = temp.resolve("synthetic")
        GooglePhotosMetadataPoc.prepare(dir)
        val source = dir.resolve("source-thumbnail.jpg")
        val bytes = Files.readAllBytes(source)
        for (sample in GooglePhotosMetadataPoc.samples) {
            val file = dir.resolve("${sample.id}.jpg")
            assertContentEquals(withoutApp1(bytes), withoutApp1(Files.readAllBytes(file)), sample.id)
            val image = ImageIO.read(file.toFile())
            assertEquals(1600, image.width); assertEquals(1000, image.height)
            val metadata = ImageMetadataReader.readMetadata(file.toFile())
            assertEquals(1, metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java).getInt(ExifIFD0Directory.TAG_ORIENTATION))
            val exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            val expected = sample.captureTime?.replace('-', ':')?.replace('T', ' ')
            assertEquals(expected, exif?.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL))
            assertEquals(expected, exif?.getString(ExifSubIFDDirectory.TAG_DATETIME_DIGITIZED))
            assertEquals(sample.offset, exif?.getString(0x9011))
            assertEquals(sample.offset, exif?.getString(0x9012))
            val gps = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
            if (sample.latitude != null) {
                assertEquals(sample.latitude, gps.geoLocation.latitude, .000001)
                assertEquals(sample.longitude!!, gps.geoLocation.longitude, .000001)
                assertEquals(if (sample.latitude < 0) "S" else "N", gps.getString(GpsDirectory.TAG_LATITUDE_REF))
                assertEquals(if (sample.longitude < 0) "W" else "E", gps.getString(GpsDirectory.TAG_LONGITUDE_REF))
                sample.altitude?.let {
                    assertEquals(kotlin.math.abs(it), gps.getDouble(GpsDirectory.TAG_ALTITUDE), .00001)
                    assertEquals(if (it < 0) 1 else 0, gps.getInt(GpsDirectory.TAG_ALTITUDE_REF))
                }
            } else assertNull(gps)
        }
        val manifest = jacksonObjectMapper().readTree(dir.resolve("manifest.json").toFile())
        assertEquals(GooglePhotosMetadataPoc.checksum(source), manifest.path("sourceSha256").asText())
        assertEquals("2020-12-31T15:15:00Z", manifest.path("items")[3].path("expectedUtc").asText())
        assertTrue(manifest.path("syntheticImage").asBoolean())
        assertContentEquals(bytes, Files.readAllBytes(source))
        assertFailsWith<IllegalArgumentException> { GooglePhotosMetadataPoc.prepare(dir) }
    }

    @Test fun `existing thumbnail is copied unchanged and never written back`() {
        val initial = temp.resolve("generated")
        GooglePhotosMetadataPoc.prepare(initial)
        val thumbnail = initial.resolve("A.jpg") // 기존 EXIF가 있어도 샘플의 필드만 새로 쓴다.
        val checksum = GooglePhotosMetadataPoc.checksum(thumbnail)
        val dir = temp.resolve("existing")
        GooglePhotosMetadataPoc.prepare(dir, thumbnail)
        assertEquals(checksum, GooglePhotosMetadataPoc.checksum(thumbnail))
        assertEquals(checksum, GooglePhotosMetadataPoc.checksum(dir.resolve("source-thumbnail.jpg")))
        val metadata = ImageMetadataReader.readMetadata(dir.resolve("C.jpg").toFile())
        assertNull(metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)?.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL))
        assertFalse(jacksonObjectMapper().readTree(dir.resolve("manifest.json").toFile()).path("syntheticImage").asBoolean())
    }

    // APP1 이외의 DQT/DHT/SOF/SOS 및 압축 scan 바이트를 직접 비교한다.
    private fun withoutApp1(jpeg: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(jpeg, 0, 2)
        var position = 2
        while (position < jpeg.size) {
            check(jpeg[position].toInt() and 0xff == 0xff)
            val marker = jpeg[position + 1].toInt() and 0xff
            if (marker == 0xda || marker == 0xd9) {
                output.write(jpeg, position, jpeg.size - position); break
            }
            val length = ((jpeg[position + 2].toInt() and 0xff) shl 8) + (jpeg[position + 3].toInt() and 0xff)
            if (marker != 0xe1) output.write(jpeg, position, length + 2)
            position += length + 2
        }
        return output.toByteArray()
    }
}
