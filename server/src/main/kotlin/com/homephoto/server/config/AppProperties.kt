package com.homephoto.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * 서버 설정. 시작 시 application.yml(+ ./config/application.yml 외부 설정)에서 바인딩된다.
 * `var` 필드는 웹 설정 페이지(SettingsService)가 런타임에 바꿔 즉시 적용한다.
 * storageRoot와 originalStorage는 시작 시 고정되므로 변경하면 재시작이 필요하다.
 */
@ConfigurationProperties(prefix = "homephoto")
data class AppProperties(
    val storageRoot: Path,
    var apiKey: String,
    var ffmpegPath: String = "ffmpeg",
    /** 휴지통 보관 기간 — 지나면 자동 영구 삭제 */
    var trashRetentionDays: Long = 30,
    /** Phase 4 장면 분석 (GB10 Gemma VLM) */
    var caption: CaptionProperties = CaptionProperties(),
    /** 이 시간(ms)을 넘는 API 요청은 WARN 로그로 남긴다 (RequestTimingFilter) */
    var slowRequestMs: Long = 500,
    /**
     * 썸네일 생성 동시 스레드 수. 0 = 자동(코어의 절반, 최대 4).
     * 이미지 디코딩이 CPU 바운드라 올리면 최초 임포트가 그만큼 빨라진다.
     * 시작 시 고정되므로 바꾸면 재시작이 필요하다.
     */
    var thumbnailThreads: Int = 0,
    /**
     * DB(photos.db)를 둘 **폴더**. 비우면 저장소 안의 `db`를 쓴다.
     * HDD에 사진을 두고 DB만 SSD에 두는 구성을 위해 존재한다 — SQLite는 잦은 작은 쓰기라
     * 디스크 응답 시간에 민감하다. 연결 URL이 시작 시 고정되므로 바꾸면 재시작이 필요하다.
     */
    var dbPath: String = "",
    /**
     * 썸네일을 둘 **폴더**. 비우면 저장소 안의 `thumbs`를 쓴다.
     * 그리드 스크롤은 사실상 썸네일 파일 랜덤 읽기라 HDD에서 가장 느린 부분 — 원본의 ~17% 용량이라
     * SSD에 두기 쉽다. 바꾸면 즉시 적용되고, 기존 파일은 ThumbnailService가 백그라운드로 옮긴다.
     */
    var thumbsPath: String = "",
    /** 원본 전용 저장소. root를 생략하면 기존 storageRoot를 사용한다. */
    val originalStorage: OriginalStorageProperties = OriginalStorageProperties(),
    /** 원본 백업과 독립된 파생 이미지 게시. 기본 비활성, 운영 계정 검증은 보류 상태. */
    @Volatile var googlePhotos: GooglePhotosProperties = GooglePhotosProperties(),
) {
    data class OriginalStorageProperties(val root: Path? = null)
    data class GooglePhotosProperties(
        val enabled: Boolean = false,
        val clientFile: String = "",
        val tokenFile: String = "",
        val autoPublishNew: Boolean = false,
        val includeVideos: Boolean = false,
        val maxAttempts: Int = 5,
    )
    data class CaptionProperties(
        /** false면 워커가 돌지 않는다. CAPTION 작업은 계속 큐에 쌓이므로 켜면 그때부터 소화 */
        val enabled: Boolean = false,
        /** OpenAI 호환 서빙 주소 (Ollama: http://GB10주소:11434) */
        val baseUrl: String = "http://localhost:11434",
        val model: String = "gemma3:12b",
        /** 응답 대기 한도 — 모델 콜드 로딩이 느릴 수 있어 넉넉히 */
        val timeoutSeconds: Long = 180,
    )

    val originalStorageRoot: Path get() = originalStorage.root ?: storageRoot
    val originalsDir: Path get() = originalStorageRoot.resolve("originals")
    // thumbsPath가 비어 있으면 저장소 안의 thumbs 폴더 (매번 계산하므로 설정 변경이 즉시 반영된다)
    val thumbsDir: Path get() = if (thumbsPath.isBlank()) storageRoot.resolve("thumbs") else Path.of(thumbsPath)
    // dbPath가 비어 있으면 저장소 안의 db 폴더 (application.yml의 datasource URL과 같은 규칙)
    val dbDir: Path get() = if (dbPath.isBlank()) storageRoot.resolve("db") else Path.of(dbPath)

    // 수신 임시 파일은 홈서버 로컬에 유지한다. 원본과 다른 볼륨이면 Adapter가 복사한다.
    val uploadTmpDir: Path get() = storageRoot.resolve("tmp")
    // 수신 완료한 파일: tmp 정리 대상이 아니며 원본 저장 성공 전까지 보존한다.
    val incomingDir: Path get() = storageRoot.resolve("incoming")
}
