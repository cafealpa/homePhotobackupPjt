package com.homephoto.server.publication

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.coobird.thumbnailator.Thumbnails
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets.UTF_8
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 5단계 수동 PoC. Spring/운영 DB/작업 큐를 시작하지 않는다. */
object GooglePhotosMetadataPoc {
    data class Sample(
        val id: String,
        val fileName: String,
        val captureTime: String? = null,
        val offset: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitude: Double? = null,
    )

    val samples = listOf(
        Sample("A", "IMG_20180405_123456.jpg", "2018-04-05T12:34:56", "+09:00", 37.5665, 126.9780, 38.5),
        Sample("B", "촬영시각만_20200607.jpg", "2020-06-07T08:09:10"),
        Sample("C", "GPS_ONLY.jpg", latitude = 33.4996, longitude = 126.5312),
        Sample("D", "MIDNIGHT_20210101.jpg", "2021-01-01T00:15:00", "+09:00"),
        Sample("E", "IMG_20190203_040506.HEIC", "2019-02-03T04:05:06", "-03:00", -34.6037, -58.3816, -10.0),
    )
    private val mapper = jacksonObjectMapper()

    @JvmStatic fun main(args: Array<String>) {
        System.setOut(PrintStream(System.out, true, UTF_8))
        System.setErr(PrintStream(System.err, true, UTF_8))
        when (args.firstOrNull() ?: "prepare") {
            "prepare" -> {
                val dir = if (args.size > 1) Path.of(args[1]) else {
                    Files.createDirectories(Path.of("build/google-photos-poc"))
                    Files.createTempDirectory(Path.of("build/google-photos-poc"), "run-")
                }
                val source = System.getenv("HOMEPHOTO_GOOGLE_PHOTOS_POC_THUMBNAIL")?.let(Path::of)
                prepare(dir, source)
                println("PoC 이미지 5장 준비 완료: ${dir.toAbsolutePath()}")
                println("Google 업로드는 실행하지 않았습니다. manifest.json에서 기대값을 확인하세요.")
            }
            "upload" -> {
                require(args.size == 2) { "upload에는 준비한 PoC 폴더가 필요합니다." }
                val clientFile = System.getenv("HOMEPHOTO_GOOGLE_PHOTOS_CLIENT_JSON")
                    ?: error("HOMEPHOTO_GOOGLE_PHOTOS_CLIENT_JSON에 Desktop OAuth JSON 경로를 지정하세요.")
                GooglePhotosPocUpload.upload(Path.of(args[1]), Path.of(clientFile))
            }
            else -> error("지원 모드: prepare | upload")
        }
    }

    fun prepare(dir: Path, thumbnail: Path? = null) {
        Files.createDirectories(dir)
        require(Files.list(dir).use { it.findAny().isEmpty }) { "새 빈 폴더로 준비하세요. 기존 검증 결과를 덮어쓰지 않습니다." }
        val source = dir.resolve("source-thumbnail.jpg")
        if (thumbnail != null) {
            require(thumbnail.fileName.toString().substringAfterLast('.').lowercase() in setOf("jpg", "jpeg"))
            val image = javax.imageio.ImageIO.read(thumbnail.toFile()) ?: error("JPEG 썸네일을 읽을 수 없습니다.")
            require(image.width <= 1600 && image.height <= 1600) { "기존 1600 썸네일을 지정하세요." }
            Files.copy(thumbnail, source)
        } else {
            val image = BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color(239, 244, 251); g.fillRect(0, 0, 1600, 1000)
            g.color = Color(40, 75, 110); g.font = Font("SansSerif", Font.BOLD, 72)
            g.drawString("HOME PHOTO / METADATA POC", 80, 150)
            g.drawString("TOP", 700, 290)
            g.fillPolygon(intArrayOf(800, 690, 750, 750, 850, 850, 910), intArrayOf(320, 460, 460, 720, 720, 460, 460), 7)
            for (n in 0..19) {
                g.color = Color.getHSBColor(n / 20f, .7f, .9f)
                g.fillRect(80 + n * 72, 820, 65, 90)
            }
            g.dispose()
            Thumbnails.of(image).size(1600, 1600).outputFormat("jpg").outputQuality(.85).toFile(source.toFile())
        }
        val sourceHash = checksum(source)
        val manifest = samples.map { sample ->
            val file = dir.resolve("${sample.id}.jpg")
            writeExif(source, file, sample)
            mapOf("sample" to sample, "file" to file.fileName.toString(), "sha256" to checksum(file),
                "expectedUtc" to sample.offset?.let {
                    LocalDateTime.parse(sample.captureTime).toInstant(ZoneOffset.of(it)).toString()
                }, "orientation" to 1)
        }
        check(checksum(source) == sourceHash) { "입력 썸네일이 변경되었습니다." }
        mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("manifest.json").toFile(),
            mapOf("sourceSha256" to sourceHash, "syntheticImage" to (thumbnail == null), "items" to manifest))
        Files.writeString(dir.resolve("UI-RESULTS.md"), """
            # Google Photos 실제 계정 검증 결과

            상태: 미실행

            | 샘플 | 날짜/타임라인 | GPS 표시 | 지도/위치 검색 | 파일명 | 방향/화질 | 비고 |
            |---|---|---|---|---|---|---|
            | A | 미확인 | 미확인 | 미확인 | 미확인 | 미확인 | 날짜+GPS+시간대 |
            | B | 미확인 | 해당 없음 | 해당 없음 | 미확인 | 미확인 | 날짜만, 시간대 없음 |
            | C | 날짜 없음 관찰 | 미확인 | 미확인 | 미확인 | 미확인 | GPS만 |
            | D | 미확인 | 해당 없음 | 해당 없음 | 미확인 | 미확인 | 자정 근처 +09:00 |
            | E | 미확인 | 미확인 | 미확인 | 미확인 | 미확인 | 남/서반구, 고도 -10m, JPEG bytes / HEIC 이름 |

            합성 이미지로는 실제 사진의 체감 품질과 검색 인식을 확정할 수 없다.
            실제 1600 썸네일을 지정한 별도 실행도 확인한다. 위치 검색의 색인 지연은 관찰 시각과 함께 기록한다.
        """.trimIndent() + "\n")
    }

    fun writeExif(source: Path, output: Path, sample: Sample) {
        ExportExifWriter().write(source, output, PublicationMetadata(sample.fileName, sample.captureTime,
            sample.offset, sample.latitude, sample.longitude, sample.altitude))
    }

    fun checksum(path: Path): String = GooglePhotosExport.sha256(path)
}
