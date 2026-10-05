package com.homephoto.server.search

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.measureTimeMillis
import com.homephoto.server.config.AppProperties
import com.homephoto.server.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/** Explicit, offline verification with invented captions; never reads the photo library. */
object CaptionTextSearchSmoke {
    @JvmStatic fun main(args: Array<String>) {
        val model = Path.of(args[0])
        val samples = listOf(
            "아이가 두 바퀴 자전거를 타고 공원 산책로를 달린다. 태그: 자전거, 공원, 어린이" to "페달을 밟으며 달리는 어린이",
            "아이들이 바닷가 모래 위에서 모래성을 쌓고 있다. 태그: 해변, 모래놀이" to "해변에서 노는 아이들",
            "빨간색 쟁반 위에 채소와 달걀로 만든 샐러드가 있다. 태그: 식단, 샐러드" to "붉은 접시에 담긴 음식",
            "눈 덮인 언덕에서 아이가 썰매를 타고 내려온다. 태그: 겨울, 썰매" to "겨울 눈밭에서 즐기는 놀이",
            "가족이 생일 케이크의 촛불을 함께 끄고 있다. 태그: 생일, 케이크" to "생일을 축하하는 가족",
            "강아지가 잔디밭에서 공을 물고 뛰어온다. 태그: 반려견, 공놀이" to "개가 공을 가지고 노는 모습",
            "고양이가 창가에 누워 잠들어 있다. 태그: 고양이, 낮잠" to "창문 옆에서 자는 반려묘",
            "아이가 실내 수영장에서 물에 떠서 헤엄친다. 태그: 수영, 물놀이" to "풀장에서 수영하는 어린이",
            "사람들이 산 정상에 올라 풍경을 보고 있다. 태그: 등산, 산" to "산을 오른 뒤 경치를 구경하는 사람들",
            "아이 둘이 그네에 앉아 웃으며 놀고 있다. 태그: 놀이터, 그네" to "놀이터에서 그네 타는 아이들",
            "텐트 앞에서 가족이 고기를 구워 먹는다. 태그: 캠핑, 바비큐" to "야영장에서 함께 식사하는 가족",
            "아이들이 책상에 앉아 색연필로 그림을 그린다. 태그: 미술, 그림" to "색칠하며 그림 그리는 어린이들",
            "기차역 승강장에 여행 가방을 든 사람들이 서 있다. 태그: 철도, 여행" to "열차를 기다리는 여행객들",
            "공항 창문 너머로 비행기가 보인다. 태그: 공항, 비행기" to "항공 여행을 떠나는 곳",
            "꽃이 가득 핀 벚나무 아래에서 가족이 사진을 찍는다. 태그: 봄, 벚꽃" to "봄꽃 구경하는 가족",
            "단풍으로 붉게 물든 숲길을 걷는다. 태그: 가을, 산책" to "가을 낙엽 사이를 걷는 모습",
            "아이가 우산을 쓰고 빗속을 걷는다. 태그: 비, 우산" to "비 오는 날 우산 쓴 어린이",
            "아이가 블록을 쌓아 장난감 집을 만든다. 태그: 블록, 장난감" to "조립 장난감으로 집 짓기",
            "축구장에서 아이들이 공을 차며 경기를 한다. 태그: 축구, 운동" to "발로 공을 차는 어린이들",
            "아이가 피아노 건반을 누르며 연주한다. 태그: 피아노, 음악" to "건반 악기를 연주하는 아이",
            "식탁에 따뜻한 국수 한 그릇이 놓여 있다. 태그: 국수, 식사" to "면 요리가 담긴 그릇",
            "밤하늘에 불꽃놀이가 터지고 사람들이 구경한다. 태그: 축제, 불꽃" to "밤 축제에서 폭죽 구경",
            "아이가 미끄럼틀을 타고 내려온다. 태그: 놀이터, 미끄럼틀" to "미끄럼틀에서 노는 어린이",
            "아이가 귤 껍질을 벗기고 과일을 먹는다. 태그: 귤, 간식" to "과일 간식을 먹는 어린이"
        )
        val path = Files.createTempDirectory("caption-e5-smoke-")
        CaptionTextEncoder(model).use { encoder -> CaptionVectorIndex(path).use { index ->
            val buildMs = measureTimeMillis { samples.forEachIndexed { i, pair ->
                index.put(i.toLong(), CaptionTextEncoder.fingerprint(pair.first), encoder.encode(pair.first, false))
            }; index.commit() }
            var top1 = 0; var top3 = 0
            val times = mutableListOf<Long>()
            samples.forEachIndexed { i, pair ->
                var hits = emptyList<CaptionVectorIndex.Hit>()
                times += measureTimeMillis { hits = index.search(encoder.encode(pair.second, true), samples.size) }
                if (hits.first().id == i.toLong()) top1++
                if (hits.take(3).any { it.id == i.toLong() }) top3++
                println("query=$i expected=$i top=${hits.take(3).map { it.id }} cosine=${hits.first().score * 2 - 1}")
            }
            println("MODEL=${CaptionTextEncoder.ID} documents=${samples.size} buildMs=$buildMs recall1=$top1/${samples.size} recall3=$top3/${samples.size} queryMedianMs=${times.sorted()[times.size/2]}")
            check(top3 >= 22) { "Korean smoke recall@3 below 22/24" }
            val longVector = encoder.encode("긴 문장 테스트 ".repeat(1000), true)
            check(longVector.size == 384 && longVector.all { it.isFinite() })
        } }
        println("temporaryIndex=$path")
        val dbDir = Files.createTempDirectory("caption-text-service-smoke-")
        HikariDataSource(HikariConfig().apply { jdbcUrl = "jdbc:sqlite:${dbDir.resolve("test.db")}"; maximumPoolSize = 2 }).use { ds ->
            val db = Database.connect(ds)
            transaction {
                SchemaUtils.create(Assets, Captions)
                val id = Assets.insert {
                    it[hash] = "synthetic"; it[mediaType] = "PHOTO"; it[originalPath] = "unused"; it[originalFilename] = "synthetic.jpg"
                    it[fileSize] = 1; it[takenAtSource] = "UPLOAD_TIME"; it[yearMonth] = "2026-10"; it[createdAt] = "2026-10-05"
                }[Assets.id]
                Captions.insert { it[assetId] = id; it[caption] = samples[0].first; it[tags] = "자전거,공원"; it[createdAt] = "2026-10-05" }
            }
            val service = CaptionTextSearch(CaptionTextSearchProperties(modelDir = model.toString()), AppProperties(dbDir, "synthetic-test-only"))
            try {
                service.tick()
                val deadline = System.nanoTime() + 30_000_000_000L
                while (service.status().state !in setOf("ready", "unavailable", "model_missing") && System.nanoTime() < deadline) Thread.sleep(20)
                check(service.status().state == "ready") { service.status().toString() }
                check(service.captionCandidates("페달을 밟으며 달리는 어린이").isNotEmpty())
                println("SERVICE_SQLITE_HNSW_QUERY=PASS indexed=${service.status().indexed}")
            } finally { service.close(); TransactionManager.closeAndUnregister(db) }
        }
    }
}
