package com.homephoto.server.config

import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 스키마 변경과 별개로 매 시작마다 중단 작업 복구와 누락 작업 등록을 수행한다. */
@Component
class StartupJobRecovery {
    private val log = LoggerFactory.getLogger(javaClass)
    fun recover() = transaction {
        // 제출 직후 중단되어 원격 ID가 없는 작업은 중복 과금 방지를 위해 명시적 재시도만 허용한다.
        exec("""UPDATE jobs SET status='FAILED', last_error='Gemini 배치 접수 여부 불명확. Google AI Studio에서 확인 후 재시도하세요.'
            WHERE id IN (SELECT i.job_id FROM caption_batch_items i JOIN caption_batches b ON b.id=i.batch_id WHERE b.remote_name IS NULL)""")
        exec("DELETE FROM caption_batch_items WHERE batch_id IN (SELECT id FROM caption_batches WHERE remote_name IS NULL)")
        exec("DELETE FROM caption_batches WHERE remote_name IS NULL")
        // 중단된 실행만 복구한다. 확정된 실패·오류·시도 횟수는 운영자가 확인하고 재시도할 때까지 보존한다.
        exec("""UPDATE jobs SET status='PENDING' WHERE status='RUNNING'
            AND id NOT IN (SELECT job_id FROM caption_batch_items)""")
        val recovered = exec("SELECT changes()") { rs -> rs.next(); rs.getInt(1) } ?: 0
        if (recovered > 0) log.info("중단 작업 {}건을 PENDING으로 복구", recovered)

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
