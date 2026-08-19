package com.homephoto.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * 서버 설정. 시작 시 application.yml(+ ./config/application.yml 외부 설정)에서 바인딩된다.
 * `var` 필드는 웹 설정 페이지(SettingsService)가 런타임에 바꿔 즉시 적용한다.
 * storageRoot만 val — DB 연결 문자열이 시작 시 고정되므로 재시작이 필요하다.
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
) {
    data class CaptionProperties(
        /** false면 워커가 돌지 않는다. CAPTION 작업은 계속 큐에 쌓이므로 켜면 그때부터 소화 */
        val enabled: Boolean = false,
        /** OpenAI 호환 서빙 주소 (Ollama: http://GB10주소:11434) */
        val baseUrl: String = "http://localhost:11434",
        val model: String = "gemma3:12b",
        /** 응답 대기 한도 — 모델 콜드 로딩이 느릴 수 있어 넉넉히 */
        val timeoutSeconds: Long = 180,
    )

    val originalsDir: Path get() = storageRoot.resolve("originals")
    val thumbsDir: Path get() = storageRoot.resolve("thumbs")
    val dbDir: Path get() = storageRoot.resolve("db")

    // 업로드 임시 파일용. originals와 같은 볼륨에 둬야 최종 배치가 복사 없는 rename이 된다.
    val uploadTmpDir: Path get() = storageRoot.resolve("tmp")
}
