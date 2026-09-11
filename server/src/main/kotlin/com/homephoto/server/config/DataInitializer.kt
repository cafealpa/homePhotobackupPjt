package com.homephoto.server.config

import com.homephoto.server.db.Assets
import com.homephoto.server.db.Jobs
import com.homephoto.server.db.Faces
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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
class DataInitializer(
    private val props: AppProperties,
    private val migrations: DatabaseMigrations,
    private val jobRecovery: StartupJobRecovery,
) : SmartInitializingSingleton {

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

        migrations.migrate()
        jobRecovery.recover()
        transaction {
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
