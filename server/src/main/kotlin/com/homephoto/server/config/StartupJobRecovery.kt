package com.homephoto.server.config

import com.homephoto.server.db.Jobs
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 스키마 변경과 별개로 매 시작마다 중단 작업 복구와 누락 작업 등록을 수행한다. */
@Component
class StartupJobRecovery {
    private val log = LoggerFactory.getLogger(javaClass)
    fun recover() = transaction {
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

    }
}
