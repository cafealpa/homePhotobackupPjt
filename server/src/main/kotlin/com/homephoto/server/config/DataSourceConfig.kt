package com.homephoto.server.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import javax.sql.DataSource

/**
 * DB 연결. 파일 위치를 코드에서 정하는 이유:
 * `homephoto.db-path`(설정 페이지의 'DB 파일 위치')는 비어 있을 수 있는데, YAML 플레이스홀더의
 * 기본값(`${a:기본}`)은 "정의되지 않음"에만 적용되고 "빈 문자열"에는 적용되지 않아
 * `jdbc:sqlite:/photos.db` 같은 엉뚱한 경로가 나온다. 그래서 여기서 분기한다.
 *
 * 우선순위: spring.datasource.url(환경변수 등으로 직접 지정) > homephoto.db-path > 저장소/db
 */
@Configuration
class DataSourceConfig(private val props: AppProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 직접 지정한 JDBC URL이 있으면 그대로 쓴다 (기존 SPRING_DATASOURCE_URL 구성 호환) */
    @Value("\${spring.datasource.url:}")
    private var explicitUrl: String = ""

    /**
     * DB 위치를 옮겨 놓고 파일은 안 옮긴 경우를 잡아낸다. 이대로 두면 빈 DB가 새로 만들어져
     * "사진 0장"으로 보이는데, 원인이 설정임을 모르면 데이터가 날아간 줄 알기 쉽다 (파일은 그대로다).
     * 연결 전에 확인해야 한다 — 연결하는 순간 SQLite가 빈 파일을 만들어버려 늦는다.
     */
    private fun warnIfDbNotMoved(configuredDir: java.nio.file.Path) {
        if (props.dbPath.isBlank()) return
        val default = props.storageRoot.resolve("db").resolve("photos.db")
        if (Files.exists(configuredDir.resolve("photos.db")) || !Files.exists(default)) return
        log.warn("=".repeat(78))
        log.warn("DB 파일 위치를 {}로 지정했지만 그 폴더에 photos.db가 없습니다.", configuredDir)
        log.warn("기존 DB는 여기 있습니다: {}", default.toAbsolutePath())
        log.warn("서버를 끄고 photos.db·photos.db-wal·photos.db-shm 세 파일을 새 폴더로 옮긴 뒤 다시 시작하세요.")
        log.warn("이대로 두면 빈 DB가 새로 만들어져 사진이 0장으로 보입니다 (원본 파일은 안전합니다).")
        log.warn("=".repeat(78))
    }

    @Bean
    fun dataSource(): DataSource {
        val url = explicitUrl.ifBlank {
            val dir = props.dbDir.toAbsolutePath()
            warnIfDbNotMoved(dir)
            Files.createDirectories(dir) // DataInitializer보다 먼저 만들어져야 한다 (연결이 더 빠르다)
            // journal_mode=WAL: 읽기/쓰기 동시성. busy_timeout: 쓰기 잠금 대기(대량 백필 대비 30초).
            // 주의: transaction_mode=IMMEDIATE는 sqlite-jdbc의 커밋 관리와 충돌("cannot commit")하므로 쓰지 않는다.
            "jdbc:sqlite:${dir.toString().replace('\\', '/')}/photos.db?journal_mode=WAL&busy_timeout=30000"
        }
        log.info("DB 연결: {}", url.substringBefore('?'))
        return HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 4 // SQLite는 쓰기가 단일이라 커넥션을 많이 열 이유가 없다
                poolName = "homephoto-db"
            }
        )
    }
}
