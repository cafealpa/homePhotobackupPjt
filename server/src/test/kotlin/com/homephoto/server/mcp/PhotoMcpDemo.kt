package com.homephoto.server.mcp

import com.homephoto.server.db.Assets
import com.homephoto.server.service.ThumbnailService
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.mockito.Mockito
import org.springframework.boot.SpringApplication
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/** 수동 UI 검증용. 저장소 설정/실제 사진/백그라운드 워커를 로드하지 않는다. */
object PhotoMcpDemo {
    @JvmStatic fun main(args: Array<String>) {
        val dir = Files.createTempDirectory("homephoto-mcp-demo-")
        val db = Database.connect("jdbc:sqlite:${dir.resolve("demo.db")}", driver = "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Assets)
            for (number in 1..14) Assets.insert {
                it[id] = number.toLong(); it[hash] = "demo-$number"; it[mediaType] = "PHOTO"
                it[originalPath] = "demo"; it[originalFilename] = "demo-$number.jpg"; it[fileSize] = 1
                it[takenAt] = "2025-11-03T12:00:00"; it[takenAtSource] = "FILENAME"
                it[yearMonth] = "2025-11"; it[createdAt] = "2025-11-03T12:00:00"
            }
        }
        val context = SpringApplication(McpTestApplication::class.java).run(
            "--spring.config.location=optional:classpath:/application.yml", "--server.port=18081",
            "--server.address=127.0.0.1", "--homephoto.mcp.enabled=true", "--homephoto.mcp.token=$TEST_TOKEN",
            "--homephoto.mcp.base-url=http://localhost:18081", "--logging.file.name=",
        )
        val thumbnails = context.getBean(ThumbnailService::class.java)
        for (number in 1..14) {
            val image = BufferedImage(640, 440, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = Color.getHSBColor(number / 18f, .2f, .92f); g.fillRect(0,0,640,440)
            g.color = Color.getHSBColor(number / 18f, .45f, .60f); g.fillOval(250,70,160,160)
            g.fillPolygon(intArrayOf(0,200,430), intArrayOf(440,180,440),3)
            g.color = Color(255,255,255); g.font = Font("SansSerif", Font.BOLD, 30)
            g.drawString("HOME PHOTO / DEMO $number", 30, 400); g.dispose()
            val path = dir.resolve("demo-$number.jpg"); ImageIO.write(image,"jpg",path.toFile())
            for (size in listOf(400,1600)) Mockito.`when`(thumbnails.thumbPath("demo-$number",size)).thenReturn(path)
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            context.close()
            org.jetbrains.exposed.sql.transactions.TransactionManager.closeAndUnregister(db)
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        })
        println("MCP demo ready: http://localhost:18081/mcp-dev (date: 2025-11-03, token: TEST_TOKEN in test source)")
    }
}
