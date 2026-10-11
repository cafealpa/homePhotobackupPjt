package com.homephoto.server

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.homephoto.server.api.FacesController
import com.homephoto.server.config.*
import com.homephoto.server.db.*
import com.homephoto.server.service.*
import com.homephoto.server.storage.FileSystemAdapter
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.ExposedBlob
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class FaceThumbnailGeometryTest {
    @TempDir lateinit var temp: Path

    @Test fun `all EXIF directions preserve aspect orientation normalized boxes and old face crop contract`() =
        checkGeometry("jpg", 1..8)

    @Test fun `ffmpeg path auto-orients portrait pixels and retains aspect for normalized face crops`() {
        val available = runCatching { ProcessBuilder("ffmpeg", "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0 }.getOrDefault(false)
        org.junit.jupiter.api.Assumptions.assumeTrue(available, "ffmpeg가 설치된 환경에서 검증")
        // JPEG 픽셀에 HEIC 확장자를 붙여 실제 ffmpeg 분기를 실행한다. HEIC 코덱 품질 검증은 아니다.
        checkGeometry("heic", listOf(6, 8))
    }

    private fun checkGeometry(extension: String, orientations: Iterable<Int>) {
        val db = Database.connect("jdbc:sqlite:${temp.resolve("geometry.db")}", driver = "org.sqlite.JDBC")
        try {
            DatabaseMigrations().migrate()
            val props = AppProperties(temp.resolve("local"), "test")
            val originals = FileSystemAdapter(props); originals.initialize()
            val thumbnails = ThumbnailService(props, ThumbnailStorage(props), AssetLocks(), MediaProcessRunner(), originals)
            val controller = FacesController(props, thumbnails)
            nu.pattern.OpenCV.loadLocally()
            val expected = listOf(
                doubleArrayOf(.65, .2, .2, .3), doubleArrayOf(.15, .2, .2, .3),
                doubleArrayOf(.15, .5, .2, .3), doubleArrayOf(.65, .5, .2, .3),
                doubleArrayOf(.2, .65, .3, .2), doubleArrayOf(.5, .65, .3, .2),
                doubleArrayOf(.5, .15, .3, .2), doubleArrayOf(.2, .15, .3, .2),
            )
            for (orientation in orientations) {
                val source = Files.write(temp.resolve("orientation-$orientation.$extension"), jpeg(orientation))
                val asset = AssetIngestService(originals, ExifService(), TakenAtResolver(), AssetLocks())
                    .ingest(source, source.fileName.toString(), null, null).asset
                val key = transaction { Assets.selectAll().where { Assets.id eq asset.id }.single()[Assets.originalPath] }
                thumbnails.generate(asset.hash, key, "PHOTO")
                val box = expected[orientation - 1]
                for (size in ThumbnailService.SIZES) {
                    val input = thumbnails.thumbPath(asset.hash, size)
                    val image = ImageIO.read(input.toFile())
                    val width = if (extension == "jpg") { if (orientation < 5) size else size / 2 }
                        else minOf(size, if (orientation < 5) 2400 else 1200)
                    val height = if (orientation < 5) width / 2 else width * 2
                    assertEquals(width, image.width, "orientation $orientation width")
                    assertEquals(height, image.height, "orientation $orientation height")
                    assertRed(image.getRGB(((box[0] + box[2] / 2) * image.width).toInt(), ((box[1] + box[3] / 2) * image.height).toInt()))
                    val exif = ImageMetadataReader.readMetadata(input.toFile()).getFirstDirectoryOfType(ExifIFD0Directory::class.java)
                    assertTrue(exif == null || !exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION) || exif.getInt(ExifIFD0Directory.TAG_ORIENTATION) == 1)
                    // FaceEngine과 같은 OpenCV 디코딩으로 EXIF가 이중 적용되지 않는지 확인한다.
                    val bytes = MatOfByte(*Files.readAllBytes(input))
                    val decoded = try { Imgcodecs.imdecode(bytes, Imgcodecs.IMREAD_COLOR) } finally { bytes.release() }
                    try {
                        assertEquals(image.width, decoded.cols()); assertEquals(image.height, decoded.rows())
                        val pixel = decoded.get(((box[1] + box[3] / 2) * decoded.rows()).toInt(), ((box[0] + box[2] / 2) * decoded.cols()).toInt())
                        assertTrue(pixel[2] > 200 && pixel[0] < 60)
                    } finally { decoded.release() }
                }
                // 기존 결과와 신규 결과가 같은 정규화 좌표 계약으로 같은 크롭 API를 사용한다.
                for (legacy in listOf(true, false)) {
                    val face = transaction { Faces.insert {
                        it[assetId] = asset.id; it[bboxX] = box[0]; it[bboxY] = box[1]; it[bboxW] = box[2]; it[bboxH] = box[3]
                        it[embedding] = ExposedBlob(FaceGroupingService.encode(FloatArray(512)))
                        it[clusterId] = if (legacy) 9 else 10
                    }[Faces.id] }
                    val crop = ImageIO.read(ByteArrayInputStream(controller.faceThumb(face).body!!))
                    assertRed(crop.getRGB(crop.width / 2, crop.height / 2))
                }
            }
        } finally { TransactionManager.closeAndUnregister(db) }
    }

    private fun assertRed(pixel: Int) {
        val color = Color(pixel)
        assertTrue(color.red > 200 && color.blue < 60 && color.green < 60, "expected red marker, got $color")
    }

    private fun jpeg(orientation: Int): ByteArray {
        val image = BufferedImage(2400, 1200, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.BLUE; graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color.RED; graphics.fillRect(1560, 240, 480, 360)
        } finally { graphics.dispose() }
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        val exif = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            .put(byteArrayOf(69, 120, 105, 102, 0, 0)).putShort(0x4949).putShort(42).putInt(8)
            .putShort(1).putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0).putInt(0).array()
        return ByteArrayOutputStream().also {
            it.write(jpeg, 0, 2); it.write(byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 34))
            it.write(exif); it.write(jpeg, 2, jpeg.size - 2)
        }.toByteArray()
    }
}
