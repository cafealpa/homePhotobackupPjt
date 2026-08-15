package com.homephoto.server.config

import com.homephoto.server.db.AlbumAssets
import com.homephoto.server.db.Albums
import com.homephoto.server.db.Assets
import com.homephoto.server.db.Captions
import com.homephoto.server.db.Devices
import com.homephoto.server.db.Faces
import com.homephoto.server.db.Jobs
import com.homephoto.server.db.KidsnoteChildren
import com.homephoto.server.db.KidsnotePostImages
import com.homephoto.server.db.KidsnotePosts
import com.homephoto.server.db.Persons
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component
import java.nio.file.Files

/**
 * 시작 시 저장 디렉토리 생성 + 스키마 생성. (WAL/busy_timeout은 JDBC URL 파라미터로 설정)
 *
 * ApplicationRunner가 아니라 SmartInitializingSingleton인 이유: Tomcat은 모든 싱글턴
 * 초기화가 끝난 뒤(finishRefresh) 포트를 연다. ApplicationRunner는 포트가 열린 뒤에
 * 실행되므로, 빈 DB로 재시작한 직후 업로드가 들어오면 테이블 생성 도중의 요청이
 * "no such table: jobs"로 실패한다 (assets는 생성됐고 jobs는 아직인 틈).
 */
@Component
class DataInitializer(private val props: AppProperties) : SmartInitializingSingleton {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    override fun afterSingletonsInstantiated() {
        log.info("저장소 초기화: {}", props.storageRoot.toAbsolutePath())
        Files.createDirectories(props.originalsDir)
        Files.createDirectories(props.thumbsDir)
        Files.createDirectories(props.dbDir)
        Files.createDirectories(props.uploadTmpDir)
        // 비정상 종료로 남은 업로드 임시 파일 정리 (이 시점엔 포트가 안 열려 있어 진행 중 업로드가 없다)
        Files.list(props.uploadTmpDir).use { files ->
            files.forEach { runCatching { Files.deleteIfExists(it) } }
        }

        transaction {
            SchemaUtils.create(Assets, Jobs, Persons, Faces, Devices, Captions, KidsnoteChildren, KidsnotePosts, KidsnotePostImages, Albums, AlbumAssets)
            // 마이그레이션: 기존 DB에 컬럼 추가 (이미 있으면 조용히 무시)
            runCatching { exec("ALTER TABLE assets ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0") }
            runCatching { exec("ALTER TABLE assets ADD COLUMN device_id TEXT") }
            runCatching { exec("ALTER TABLE assets ADD COLUMN purged_at TEXT") }
            runCatching { exec("ALTER TABLE assets ADD COLUMN source TEXT") }
            runCatching { exec("ALTER TABLE faces ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0") }
            // 지도 뷰 bbox 조회용 (GPS 없는 행은 제외하는 부분 인덱스)
            runCatching { exec("CREATE INDEX IF NOT EXISTS idx_assets_gps ON assets(gps_lat, gps_lon) WHERE gps_lat IS NOT NULL") }
            // 이전 실행이 비정상 종료됐을 때 RUNNING으로 남은 작업을 되살리고,
            // FAILED도 재시도 기회를 준다 (예: ffmpeg 설치 후 재시작하면 썸네일 재생성)
            val recovered = Jobs.update({ (Jobs.status eq "RUNNING") or (Jobs.status eq "FAILED") }) {
                it[status] = "PENDING"
                it[attempts] = 0
            }
            if (recovered > 0) log.info("중단/실패 작업 {}건을 PENDING으로 복구", recovered)

            // 기존 사진에 FACE·CAPTION 작업 백필 (UNIQUE(asset_id, job_type) 덕에 멱등).
            // priority = yyyymm — 최근 사진 우선 (2TB 백필이 과거→현재 순으로 밀리지 않도록)
            val now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            for (jobType in listOf("FACE", "CAPTION")) {
                exec(
                    """
                    INSERT OR IGNORE INTO jobs (asset_id, job_type, status, priority, attempts, updated_at)
                    SELECT id, '$jobType', 'PENDING', CAST(replace(year_month, '-', '') AS INTEGER), 0, '$now'
                    FROM assets
                    WHERE media_type = 'PHOTO' AND deleted_at IS NULL AND source IS NULL
                    """.trimIndent()
                )
            }

            // 시작 상태 요약 — 서버가 지금 어떤 상태인지 한눈에
            val assetCount = Assets.selectAll().where { Assets.deletedAt.isNull() }.count()
            val faceCount = Faces.selectAll().count()
            val jobSummary = Jobs.select(Jobs.jobType, Jobs.status, Jobs.id.count())
                .groupBy(Jobs.jobType, Jobs.status)
                .joinToString(", ") { "${it[Jobs.jobType]}/${it[Jobs.status]}=${it[Jobs.id.count()]}" }
            log.info("DB 준비 완료 — 사진 {}장, 얼굴 {}개 | 작업: {}", assetCount, faceCount, jobSummary.ifEmpty { "없음" })
        }
    }
}
