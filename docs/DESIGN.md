# 홈 포토 백업 시스템 — 설계 문서

> 최초 작성: 2026-08-10. 아키텍처 논의 결과의 기준 문서.

## 1. 개요

안드로이드 사진을 집 서버로 백업하고, 서버에서 월별 분류·얼굴 인식·VLM 장면 분석을
수행하며, 안드로이드/웹 뷰어와 전문(full-text) 검색을 제공하는 개인용 시스템.

### 확정된 환경/결정 사항

| 항목 | 결정 |
|---|---|
| 서버 OS | Windows (Docker 없음, 네이티브 실행) |
| 서버 GPU | GTX 1060 (Pascal, CUDA 12.x까지만 지원 — CUDA 13 불가) |
| 서버 스택 | Kotlin + Spring Boot (IntelliJ) |
| 안드로이드 | Kotlin + Jetpack Compose + WorkManager + Coil (Android Studio) |
| DB | SQLite (WAL 모드) + FTS5 `trigram` 토크나이저 (한국어 부분일치 검색) |
| ORM | Exposed (+ sqlite-jdbc). DB 접근은 Kotlin 서버 단일 프로세스로 한정 |
| 얼굴 인식 | Python 워커 + InsightFace (buffalo_l). **CPU(onnxruntime) 우선** — 개발·소규모엔 충분. GPU는 2TB 백필 시 선택 (1060+CUDA12.x 또는 GB10에서 워커 실행) |
| 장면 분석 VLM | 외부 GB10 서버의 Gemma, Ollama 서빙(잠정) → OpenAI 호환 HTTP 호출 |
| 동영상 | 백업·분류·썸네일·재생까지 지원. ML 분석(얼굴/장면)은 초기 범위 제외 |
| 기존 데이터 | 약 2TB / 10년치, 서버와 같은 하드디스크에 존재. 서버측 일괄 임포트로 처리 |
| 개발용 데이터 | 사진 약 1,000장 먼저 임포트하여 개발 |

### 미결 사항 (나중에 결정)

- GB10 서빙 도구 최종 확인 (Ollama 잠정)
- ~~2TB 전체 임포트 시 **복사 vs 이동 vs 제자리 참조**~~ → 2026-08-17 해결(6.9):
  임포트할 때마다 복사/이동을 고른다. 같은 볼륨이면 이동이 rename이라 추가 공간이 0.
  제자리 참조는 채택하지 않았다 — 원본 폴더를 정리하면 라이브러리가 깨진다.
- 얼굴 워커 실행 위치 (백필 시점 결정): Windows CPU / Windows+CUDA(1060) / GB10.
  워커는 internal HTTP API로만 서버와 통신하므로 코드 변경 없이 위치 이동 가능.

## 2. 전체 아키텍처

```
[Android 앱 (Kotlin)]
  ├─ 백업: WorkManager 백그라운드 업로드, 해시 기반 중복 체크
  └─ 뷰어: 타임라인 그리드, 검색
        │ HTTPS (REST, X-Api-Key)
        ▼
[집 서버 — Windows, GTX 1060]
  ├─ Kotlin + Spring Boot API 서버
  │    ├─ 업로드 수신 / 중복 체크 / 타임라인 / 검색 / 썸네일·원본 서빙
  │    ├─ EXIF 추출(metadata-extractor), 월별 분류
  │    ├─ 썸네일 생성 (ffmpeg — HEIC 디코드·동영상 프레임 추출 겸용)
  │    ├─ 일괄 임포트 도구 (기존 2TB, 서버 로컬 디스크에서 직접)
  │    ├─ 장면분석 워커 ──HTTP──▶ [외부 GB10: Gemma VLM (Ollama)]
  │    └─ internal API (job 분배/결과 수신) ◀──HTTP── Python ML 워커
  │                                                   (InsightFace, CPU 우선.
  │                                                    같은 머신 또는 GB10)
  └─ SQLite (WAL + FTS5) + 사진 파일시스템  ← Kotlin 서버만 접근
```

핵심 원칙:
- **업로드와 분석의 분리** — 업로드는 즉시 완료, ML은 DB 큐 기반 비동기 처리
- **원본 불변** — 저장 후 원본 파일은 절대 수정하지 않음
- **외부 의존 격리** — GB10이 꺼져 있어도 백업·뷰어는 정상 동작 (큐에 쌓였다가 재개)
- **상태 머신** — 사진별 처리 단계를 DB에 기록, 실패 재시도·모델 교체 후 재처리 용이

## 3. 저장 구조

```
{storageRoot}/
├── originals/2023/08/20230815_123456_87014cdc.jpg   # 촬영일 기준 월별 디렉토리
│      파일명 = 촬영시각(yyyyMMdd_HHmmss) + 해시 8자리(연사 충돌 방지·무결성 검증)
│      → DB가 유실돼도 파일명만으로 시간을 알 수 있고, 재임포트 시 파일명 우선
│        날짜 판정이 시간을 그대로 복원한다 (2026-08-13 변경, 해시 전체 이름에서 교체)
├── thumbs/{hash}_400.jpg             # 그리드용 (썸네일은 해시 키 유지 — 재생성 가능한 캐시)
├── thumbs/{hash}_1600.jpg            # 뷰어용 (동영상은 대표 프레임)
└── db/photos.db                      # SQLite
```

- 촬영일 우선순위: **파일명의 날짜(사용자 결정: EXIF보다 신뢰)** → EXIF
  `DateTimeOriginal` → 파일 수정시각 → 업로드 시각 (출처를 `taken_at_source`에 기록:
  FILENAME | EXIF | FILE_MTIME | UPLOAD_TIME). 파일명 패턴: `20230815_123456`형,
  `2023-08-15`형(→00:00), 13자리 epoch millis(카카오톡)형 지원
- 썸네일 포맷: JPEG (webp에서 변경 — ImageIO 순수 Java 처리를 위해).
  JPEG/PNG 등은 Thumbnailator, HEIC·동영상은 ffmpeg. **ffmpeg는 server/tools/ffmpeg.exe로
  동봉** (PATH·winget 비의존, git 제외 — 새 환경에선 static 빌드 exe를 복사).
  FAILED 작업은 서버 재시작 시 자동으로 PENDING 리셋되어 재시도됨
- 썸네일 예상 용량: 원본의 5~10% (2TB 기준 100~200GB). 디스크 3TB+ 필요
- 백업: DB 파일 + originals 디렉토리를 통째로 복사하면 완전 백업

## 4. DB 스키마 (SQLite)

```sql
PRAGMA journal_mode = WAL;

CREATE TABLE assets (
  id                INTEGER PRIMARY KEY,
  hash              TEXT NOT NULL UNIQUE,      -- SHA-256 hex
  media_type        TEXT NOT NULL,             -- PHOTO | VIDEO
  original_path     TEXT NOT NULL,             -- storageRoot 기준 상대경로
  original_filename TEXT NOT NULL,             -- 업로드 당시 파일명 (검색 대상)
  file_size         INTEGER NOT NULL,
  taken_at          TEXT,                      -- ISO-8601
  taken_at_source   TEXT NOT NULL,             -- EXIF | FILE_MTIME | UPLOAD_TIME
  year_month        TEXT NOT NULL,             -- '2026-08' (파생, 인덱스)
  width             INTEGER,
  height            INTEGER,
  duration_ms       INTEGER,                   -- VIDEO만
  camera_make       TEXT,
  camera_model      TEXT,
  gps_lat           REAL,
  gps_lon           REAL,
  created_at        TEXT NOT NULL,
  deleted_at        TEXT                       -- soft delete
);
CREATE INDEX idx_assets_year_month ON assets(year_month);
CREATE INDEX idx_assets_taken_at   ON assets(taken_at);
-- 지도 bbox 조회용 부분 인덱스 (GPS 없는 행 제외, 2026-08-14)
CREATE INDEX idx_assets_gps ON assets(gps_lat, gps_lon) WHERE gps_lat IS NOT NULL;

-- 수동 앨범 (2026-08-14). cover_asset_id NULL이면 앨범 내 최신 활성 사진이 자동 커버
CREATE TABLE albums (
  id             INTEGER PRIMARY KEY,
  name           TEXT NOT NULL,
  cover_asset_id INTEGER REFERENCES assets(id),
  created_at     TEXT NOT NULL
);
-- 앨범 ↔ 사진 M:N. 사진 원본은 불변 — 연결만 생성/삭제
CREATE TABLE album_assets (
  id       INTEGER PRIMARY KEY,
  album_id INTEGER NOT NULL REFERENCES albums(id),
  asset_id INTEGER NOT NULL REFERENCES assets(id),
  added_at TEXT NOT NULL,
  UNIQUE(album_id, asset_id)          -- 재추가 멱등 (INSERT OR IGNORE)
);

-- 비동기 처리 큐 (Kotlin 썸네일 워커, Python 얼굴 워커, 캡션 워커가 공유)
CREATE TABLE jobs (
  id         INTEGER PRIMARY KEY,
  asset_id   INTEGER NOT NULL REFERENCES assets(id),
  job_type   TEXT NOT NULL,                    -- THUMBNAIL | FACE | CAPTION
  status     TEXT NOT NULL DEFAULT 'PENDING',  -- PENDING | RUNNING | DONE | FAILED
  priority   INTEGER NOT NULL DEFAULT 0,       -- 높을수록 먼저. 최근 사진 우선 백필용
  attempts   INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  updated_at TEXT NOT NULL,
  UNIQUE(asset_id, job_type)
);
CREATE INDEX idx_jobs_pending ON jobs(job_type, status, priority DESC);

-- Phase 3: 얼굴
CREATE TABLE persons (
  id   INTEGER PRIMARY KEY,
  name TEXT                                    -- 사용자가 붙인 이름 (NULL = 미명명)
);
CREATE TABLE faces (
  id         INTEGER PRIMARY KEY,
  asset_id   INTEGER NOT NULL REFERENCES assets(id),
  bbox_x REAL, bbox_y REAL, bbox_w REAL, bbox_h REAL,  -- 0~1 정규화 좌표
  embedding  BLOB NOT NULL,                    -- float32[512]
  cluster_id INTEGER,                          -- 자동 클러스터링 결과
  person_id  INTEGER REFERENCES persons(id)    -- 사용자 확정 라벨
);
CREATE INDEX idx_faces_asset   ON faces(asset_id);
CREATE INDEX idx_faces_cluster ON faces(cluster_id);

-- Phase 4: 장면 분석
CREATE TABLE captions (
  asset_id   INTEGER PRIMARY KEY REFERENCES assets(id),
  caption    TEXT NOT NULL,                    -- 한국어 한두 문장
  tags       TEXT,                             -- 쉼표 구분 키워드
  model      TEXT,                             -- 재처리 판단용 모델명
  created_at TEXT NOT NULL
);

-- Phase 5: 검색 (한국어 부분일치는 trigram 토크나이저)
CREATE VIRTUAL TABLE asset_fts USING fts5(
  caption, tags, filename,
  content='', tokenize='trigram'
);
-- 동기화는 앱 코드에서 captions 쓰기 시점에 수행 (트리거 대신 명시적 관리)
```

SQLite 동시성 규율:
- **DB에 접근하는 프로세스는 Kotlin 서버 하나뿐.** Python 워커는 internal HTTP API
  (`GET /internal/jobs` → 이미지 다운로드 → `POST /internal/jobs/{id}/result`)로만
  통신한다. 덕분에 워커는 어느 머신에서든 실행 가능 (Windows CPU / GB10 등)
- WAL 모드 + `busy_timeout` 설정 (서버 내 다중 커넥션 대비)
- job 처리는 `RUNNING` 마킹 → 처리 → 결과 기록의 짧은 트랜잭션 패턴

## 5. API 스펙 (Phase 1~2 범위)

인증: 전 엔드포인트 `X-Api-Key` 헤더 (단일 사용자, 설정 파일의 고정 토큰).

| 메서드/경로 | 설명 |
|---|---|
| `POST /api/v1/assets/check` | `{hashes: [...]}` → `{missing: [...]}` 서버에 없는 해시만 반환 |
| `POST /api/v1/assets` | multipart 업로드 (`file`, `hash`, `fileMtime`). 중복이면 409 + 기존 asset |
| `GET /api/v1/months` | `[{yearMonth, count}]` 타임라인 골격 |
| `GET /api/v1/assets?yearMonth=&cursor=&limit=` | 월별 목록 (커서 페이지네이션, taken_at DESC) |
| `GET /api/v1/assets/{id}` | 단건 메타데이터 |
| `GET /api/v1/assets/{id}/thumb?size=400\|1600` | 썸네일 (WebP) |
| `GET /api/v1/assets/{id}/file` | 원본. 동영상은 HTTP Range 지원 |
| `POST /api/v1/admin/import` | `{sourcePath, mode}` 서버 로컬 디렉토리 일괄 임포트. mode = SCAN(미리 확인) \| COPY(기본) \| MOVE. 202, 이미 실행 중이면 409 |
| `GET /api/v1/admin/import/status` | 진행 상황 폴링 (6.9) |
| `POST /api/v1/admin/import/stop` | 진행 중인 임포트 중지 |

### Internal API (ML 워커용, Phase 3~4)

| 메서드/경로 | 설명 |
|---|---|
| `POST /internal/jobs/claim` | `{jobType: FACE}` → `{jobId, assetId}` 또는 204. UPDATE…RETURNING으로 원자적 클레임. CAPTION은 서버 내장 워커가 직접 처리(6.6) |
| `POST /internal/jobs/{id}/complete` | FACE 결과 제출: `{faces: [{x,y,w,h, embedding(base64 float32[512])}]}`. 기존 얼굴 삭제 후 재삽입(멱등) |
| `POST /internal/jobs/{id}/fail` | 실패 보고. 3회 누적 시 FAILED (서버 재시작하면 리셋) |
| `GET /internal/faces` | 클러스터링용 전체 임베딩 |
| `POST /internal/faces/clusters` | DBSCAN 결과 반영. 맵에 없는 얼굴은 노이즈(cluster_id NULL) |
| `GET /api/v1/faces/clusters` | (앱용) 클러스터 요약: faceCount, coverAssetId |

업로드 흐름 (Android):
1. MediaStore에서 신규 파일 스캔 → SHA-256 계산
2. `POST /assets/check`로 서버에 없는 것만 선별
3. WorkManager로 순차 업로드 (Wi-Fi/충전 조건 설정 가능)
4. 서버: 해시 검증 → 임시파일 → EXIF 추출 → 월별 경로로 이동 → assets INSERT +
   THUMBNAIL job 등록 → 응답. (THUMBNAIL 외 ML job은 각 Phase에서 추가)

## 6. 프로젝트 구조

```
homePhotobackupPjt/
├── docs/DESIGN.md        # 이 문서
├── server/               # Kotlin + Spring Boot (IntelliJ로 열기)
├── android/              # Android 앱 (Android Studio로 열기)
└── ml-worker/            # Python InsightFace 워커 (Phase 3에서 생성)
```

## 6.4 사진 소유 기기 추적 (2026-08-13 확정)

- 목적: "이 사진이 누구 폰에서 백업됐나" — EXIF 기기정보는 촬영 기기일 뿐이라 부적합
- 설계(단순안 채택): **소유자 = 첫 업로더**. `devices(id=앱 UUID, name)` +
  `assets.device_id`. 중복 사진의 다중 보유 기기 추적은 하지 않기로 함(사용자 결정)
- 앱: 설치 시 UUID 생성(영구), 설정에 "기기 이름"(기본 Build.MODEL). 업로드 시
  X-Device-Id / X-Device-Name(URL 인코딩 — 한글 헤더 불가) 헤더 자동 첨부.
  이름 변경 시 다음 업로드 때 서버에 반영(upsert)
- 서버 임포트는 가상 기기 `server-import`("서버 임포트")로 기록
- API: GET /devices(기기별 사진 수), PUT /devices/{id}(이름 변경 — 소급 반영),
  POST /admin/assets/backfill-device(device_id 없는 기존 사진 일괄 지정)
- 웹 라이트박스 캡션에 "📱 기기명" 표시. 기존 3,600장은 새 앱으로 1회 업로드 후
  backfill-device 실행으로 소급 지정

## 6.45 삭제(휴지통)·스킵·복원 (2026-08-14 구현)

- **삭제 = 휴지통 이동**: `DELETE /assets/{id}` — `deleted_at`만 기록, **파일은 유지**.
  30일(설정 `homephoto.trash-retention-days`) 후 TrashService가 자동 영구 삭제
  (@Scheduled 6시간 주기). 웹(🗑/Del 키)·앱 뷰어에서 사용, 폰 원본은 건드리지 않음
- **휴지통 API**: `GET /trash`(목록+purgeAt), `POST /trash/{id}/restore`(복원 —
  파일이 있으니 묘비 해제만), `DELETE /trash/{id}`(선택 영구 삭제),
  `POST /trash/empty`(비우기). 웹 사이드바에 휴지통 뷰(D-day 표시, 복원/영구삭제
  hover 버튼, 상단 비우기 버튼)
- **영구 삭제 = purge**: 파일·썸네일 제거 + faces·jobs 정리 + `purged_at` 마킹.
  행은 재백업 스킵용 묘비로 영구 유지. 썸네일·원본 서빙은 휴지통 항목까지 허용
  (purged는 404)
- **재백업 스킵**: `/assets/check` 응답 `{missing, deleted}` — deleted에는
  휴지통+영구삭제 모두 포함. 앱은 `SKIPPED` 처리(업로드 제외)
- **선택적 재업로드**: 앱 백업 탭 "스킵된 사진 관리" → `RESTORE` 상태 → 업로드 시
  서버 ingest.restore()가 deleted_at/purged_at 해제 + 파일 재저장(휴지통이면 생략)
  + 작업 재등록
- 상태: 활성 →(삭제) 휴지통[파일 있음] →(30일/비우기/선택삭제) 묘비[파일 없음]
  / 휴지통 →(복원) 활성 / 묘비 →(스킵 관리 재업로드) 활성

## 6.7 외부 저장소 분리 + 웹 설정 페이지 (2026-08-14 구현)

- **저장소 분리**: 개발 중 `server/data`(프로젝트 내부)에 쌓이던 데이터를
  **`C:/homePhotoData`**로 이전. 프로젝트 삭제/재배포와 사진 데이터가 분리된다.
  이전은 루트의 `migrate-storage.ps1` 1회 실행(서버 중지 → Move-Item → 설정 파일 생성).
  추후 대용량 디스크로 옮길 때도 같은 절차: 서버 끄고 폴더 이동 → 설정에서 경로 변경
- **설정 저장 방식**: `server/config/application.yml` — Spring Boot 표준 외부 설정
  경로라 classpath의 application.yml을 자동으로 덮어쓴다(코드 없이 재시작 반영).
  웹 설정 페이지가 이 파일을 쓴다. **API 키를 잊었을 때도 이 파일에서 확인**
- **웹 설정 페이지**: 사이드바 하단 ⚙️ 설정. 노출 항목: 저장소 경로(재시작 필요 배지),
  API 키, ffmpeg 경로, 휴지통 보관일, 장면 분석(사용/주소/모델/타임아웃).
  API: `GET/PUT /api/v1/admin/settings` (검증 실패는 400 + 한국어 메시지)
- **즉시 적용 vs 재시작**: storage-root만 재시작 필요(DB 연결이 시작 시 고정) —
  AppProperties의 나머지 필드는 `var`라 저장 즉시 반영. API 키 변경 시 기존
  쿠키·헤더가 무효가 되므로 웹 재로그인 + 앱에 새 키 입력 필요(UI에 경고 표시)
- **경로 변경 주의**: 설정에서 storage-root를 바꿔도 파일이 자동 이동되지는 않는다 —
  서버 끄고 폴더를 직접 옮긴 뒤 재시작 (UI 힌트로 안내)
- **서버 재시작 버튼**: `POST /api/v1/admin/restart` — 현재 프로세스 종료를
  Wait-Process로 기다렸다가 재기동하는 PowerShell 감시 프로세스를 띄우고
  스스로 종료(포트 충돌 없음). jar(-jar)·IDE(-cp) 실행 모두 지원, 재기동 후엔 콘솔 없는
  javaw 백그라운드 프로세스가 된다(로그는 logs/homephoto.log). 웹은 헬스체크 폴링 후
  자동 새로고침. 주의: IDE에서 띄운 서버를 웹에서 재시작하면 IDE 콘솔과는 분리됨
- **실행 사본 규율 (2026-08-14 장애 후 확립)**: jar 서버는 build/libs가 아니라
  **server/run/homephoto-server.jar 사본**으로 실행한다. 실행 중인 jar를 gradle 재빌드가
  덮어쓰면 JVM의 지연 클래스 로딩이 깨져 "아직 안 불린 엔드포인트만 멈추는" 반죽음
  상태가 된다(실제 발생 — health/정적 파일은 응답, API는 무응답). 재시작 감시 프로세스가
  구 프로세스 종료 후 최신 build/libs jar를 run/으로 복사하고 띄우므로,
  **gradle 빌드 → 웹 재시작 버튼 = 새 버전 배포** 플로우가 된다
- **정적 파일 캐시**: index.html의 `?v=` 캐시버스터 — app.js/style.css 수정 시 버전을
  같이 올린다. 썸네일 응답은 `Cache-Control: max-age=30일`(해시 기반이라 사실상 불변)

## 6.8 배포 전략 (2026-08-15 구축)

사용자가 많지 않은 개인용 도구라 **소스는 git, 실행본은 zip + bat** 조합으로 간다.
설치 프로그램·서비스 등록·자동 업데이트는 만들지 않는다.

- **git 저장소**: 프로젝트 루트에서 `git init` (2026-08-15). 루트 `.gitignore`가
  전체를 관장하고 `server/.gitignore`는 server 전용 항목만 둔다.
  제외 대상: 빌드 산출물, `server/data`·`logs`·`run`·`release`,
  **`server/config/application.yml`(API 키 평문)**, `local.properties`, 서명 키,
  `server/tools/`(ffmpeg 212MB), `ml-worker/.venv`
  - **함정**: `.gitignore`에 `data/`처럼 쓰면 안 된다 —
    `android/.../homephotoclient/data`(소스 패키지)까지 제외된다. `/server/data/`로 고정
  - `.gitattributes`: `gradlew`·`*.sh`는 LF 고정(CRLF면 Git Bash에서 실행 불가),
    `*.bat`은 CRLF 고정
- **릴리즈 패키징**: `server/package-release.bat` — `bootJar` 빌드 → `release/{이름}/`에
  jar·start·stop·README·tools 구성 → zip. 버전은 `build.gradle.kts`의 `version`을
  따라가고(`-plain.jar`는 걸러냄), 릴리즈마다 이 값을 올린다.
  `package-release.bat withffmpeg`로 ffmpeg 동봉(+212MB). 결과 zip을 GitHub Releases에 올린다
- **실행/중지 스크립트**: `start-server.bat` / `stop-server.bat` — 개발 폴더와 배포 zip
  양쪽에서 동일하게 동작한다. start는 Java 확인 → 8080 중복 확인 → jar 탐색
  (배포본은 폴더 옆, 개발은 `build/libs`, 없으면 자동 빌드) → 새 창 실행.
  **개발 환경에서는 6.7의 실행 사본 규율에 따라 `run/`에 복사한 뒤 그것을 실행한다.**
  stop은 8080 점유 프로세스를 종료(netstat이 IPv4/IPv6를 따로 뱉으므로 PID 중복 제거)
- **배치 파일 인코딩 (중요)**: 한글이 든 `.bat`은 **CP949로 저장**한다.
  cmd는 UTF-8 배치 파일을 제대로 파싱하지 못해 명령 자체가 깨진다(실제 발생).
  UTF-8 콘솔에서 실행되는 경우를 대비해 파일 상단에 `chcp 949`를 둔다.
  이 파일들은 Edit/Write 도구로 직접 수정하면 UTF-8이 되므로, 수정 후
  `[IO.File]::WriteAllText($p, $t, [Text.Encoding]::GetEncoding(949))`로 되돌려야 한다
- **PATH 함정**: `NoDefaultCurrentDirectoryInExePath`가 설정된 환경에서는 현재 폴더의
  실행 파일을 찾지 못한다. 배치 안에서는 `call ".\gradlew.bat"`처럼 경로를 명시
- **첫 실행 경험**: zip 풀고 `start-server.bat` → `http://localhost:8080` →
  설정에서 **API 키와 저장소 경로 변경** → 재시작. 설정을 저장하면 `config/application.yml`이
  생성된다(6.7). 안내문은 `server/dist/README.txt`(zip에 동봉), 저장소 소개는 루트 `README.md`
- **업데이트**: jar 교체 후 재시작. `data`·`config`는 유지되고 DB 스키마 변경은
  시작 시 자동 반영(DataInitializer의 ALTER). 자동 업데이트 기능은 없다
- **검증 완료 (2026-08-15)**: 배포 zip을 빈 폴더에 풀어 실행 → `data/`(db·originals·
  thumbs·tmp)·`logs/` 자동 생성, 웹 200, 로그인 OK. 중복 실행 감지·중지·`run/` 사본
  복사 경로까지 실행 확인
- **남은 것**: 안드로이드 릴리즈 서명 키(현재 서명 설정 없음) 후 APK를 같은 Release에 첨부,
  배포본 기본 API 키(`dev-key-change-me`)를 첫 실행 시 랜덤 생성하도록 개선

## 6.9 대용량 로컬 임포트 (2026-08-17 구현)

기존 2TB를 서버가 자기 디스크에서 직접 들여오는 도구. 설정 페이지 하단에 UI가 있고,
백엔드는 `ImportService` + `/api/v1/admin/import*` (한 번에 한 작업만).

- **3가지 방식** — 2절 미해결 항목이던 "복사 vs 이동"을 **사용자가 고르게** 해서 매듭지었다
  - `SCAN` 미리 확인: 개수·용량만 세고 아무것도 들여오지 않는다. 몇 시간짜리 작업 전에
    대상이 맞는지, 공간이 되는지 먼저 본다
  - `COPY` 복사(기본): 원본 보존. 사진 용량만큼 디스크를 더 쓴다 (2TB → 합계 4TB)
  - `MOVE` 이동: `AssetIngestService(moveSource = true)`. **같은 볼륨이면 rename 한 번**이라
    2TB도 즉시 끝나고 추가 공간이 0이다. 중복으로 걸러진 파일은 이동되지 않고 원본 자리에
    남는다(ingest가 move 전에 반환) — 원본을 지우는 동작은 어디에도 없다
- **스캔 선행**: `Files.walkFileTree`로 전체를 먼저 훑어 개수·바이트를 확정한다.
  진행률·남은 시간을 보여주려면 분모가 필요하고, `BasicFileAttributes`를 그때 같이 받아 두면
  임포트 루프에서 파일마다 다시 stat하지 않는다. 스캔 제외: `@eaDir`·`#recycle`·
  `$RECYCLE.BIN`·`System Volume Information`·`node_modules`·`.`으로 시작하는 폴더, 0바이트 파일
- **중지**: `AtomicBoolean` 플래그를 파일 루프와 스캔 방문자에서 확인 — 처리 중인 파일 하나를
  끝내고 멈춘다. 이어서 하기는 따로 상태를 저장하지 않는다. **해시 중복 판정이 곧 재개**여서
  같은 폴더로 다시 시작하면 남은 것만 들어온다(서버가 죽어도 동일)
- **시작 전 차단**: 없는 폴더 / 빈 경로 / 저장소 안쪽 지정(자기 자신을 다시 넣는 꼴) /
  알 수 없는 mode → 400 + 한국어 메시지. 복사 모드는 `usableSpace`와 비교해 여유가
  모자라면 시작조차 하지 않는다(여유 5GB 마진). 저장소가 원본 폴더 하위에 있으면 그 서브트리만 건너뛴다
- **진행 표시**: 상태를 서버가 들고 있고 화면은 1.5초 폴링만 한다 → 새로고침하거나 다른 화면에
  갔다 와도, 창을 닫아도 작업은 계속된다. 남은 시간은 개수가 아니라 **바이트 기준**으로 추정한다
  (파일 크기 편차가 커서 개수 기준은 크게 틀린다). 스캔 단계는 분모가 없어 퍼센트 대신
  훑는 애니메이션(`.indeterminate`)을 쓴다
- **단일 스레드**: 병렬화하지 않았다. SQLite 쓰기가 직렬화되는 데다 썸네일 워커와 이미 경합하고
  있어(6.7의 DB 잠금 주석), 임포트를 병렬로 돌리면 워커 쪽 `SQLITE_BUSY`만 늘어난다
- **임포트 결과**: 가상 기기 `server-import`("서버 임포트") 소유로 기록(6.4).
  썸네일·FACE·CAPTION 작업이 정상 등록되므로 임포트가 끝난 뒤 백그라운드에서 이어진다
- **검증 (2026-08-17)**: 격리 서버(8085 + 별도 storage-root)에서 1,500장 실측 — 스캔 제외 규칙,
  COPY 후 원본 보존, 재실행 시 전량 중복 판정, MOVE 후 원본 폴더에 중복 1개만 잔존,
  중지(854/1500) → 재시작 시 신규 646 + 중복 854 = 1500 정확히 이어짐, ETA·진행바·버튼
  상태·화면 이탈 후 복귀까지 브라우저에서 확인

## 6.6 장면 분석 — Phase 4 (2026-08-14 구현)

- **구조**: Python 워커가 아니라 **서버 내장 CaptionWorker(Kotlin)**가 CAPTION 작업을
  DB에서 직접 클레임 → 1600px 썸네일을 GB10의 Ollama **OpenAI 호환 API**
  (`/v1/chat/completions`, base64 image_url)로 전송 → 한국어 캡션+태그 JSON 수신 →
  `captions` 테이블에 저장(자산당 1행, 삭제 후 삽입으로 멱등). 이에 따라 internal
  `/jobs/claim`은 FACE 전용으로 제한 (외부 워커가 CAPTION을 잡으면 complete가 얼굴
  결과로 오처리하므로)
- **원본 대신 1600px 썸네일 전송**: 전송량 절감 + HEIC 등도 JPEG로 통일. 썸네일이
  아직 없으면 캡션 워커가 먼저 생성(멱등)
- **외부 의존 격리**: VLM 연결 실패(꺼짐/타임아웃)는 작업 실패로 치지 않는다 —
  attempts 소모 없이 PENDING으로 되돌리고 워커가 60초 백오프 후 재개. 일반 오류만
  3회 후 FAILED (재시작 시 리셋되는 기존 규칙 공유)
- **설정** (`homephoto.caption.*`): `enabled`(기본 false — GB10 주소 확정 후 켠다),
  `base-url`(Ollama 주소), `model`(기본 gemma3:12b), `timeout-seconds`(기본 180 —
  콜드 로딩 대비). enabled=false여도 CAPTION 작업은 계속 큐에 쌓인다
- **작업 등록**: 업로드/복원/시작 시 백필 모두 PHOTO에 FACE와 함께 CAPTION 등록,
  priority=yyyymm(최근 우선). 영구 삭제 시 captions도 정리
- **프롬프트**: JSON 강제(`{"caption": 한국어 한두 문장, "tags": [명사 3~8개]}`),
  temperature 0.2. 모델이 형식을 어기면 전체 텍스트를 캡션으로 저장(태그 없음)
- **조회**: `GET /api/v1/assets/{id}/caption` → `{caption, tags[], model}` (분석 전이면
  caption=null). 웹 라이트박스 정보 패널(ⓘ)에 "장면"/"태그" 행으로 표시
- 남은 것: GB10 주소 설정 후 실사진 엔드투엔드 확인, 모델 교체 시 재처리(requeue)
  어드민 API, 태그 활용 검색은 Phase 5에서

## 6.5 웹 뷰어 계획 (2026-08-12 확정)

- 스택: **순수 HTML/JS/CSS** (빌드 도구 없음), Spring Boot `resources/static/`으로 서빙.
  기존 REST API 전부 재사용 — 서버 코드 변경은 인증 확장뿐
- 인증: 브라우저 `<img>`는 헤더를 못 붙이므로 **쿠키 인증 추가** — 첫 접속 시 키 입력
  → HttpOnly 쿠키 발급, ApiKeyFilter는 헤더/쿠키 둘 다 허용 (앱은 기존 헤더 유지)
- 단계: W1 로그인+타임라인 그리드+무한스크롤 → W2 라이트박스(키보드 ←/→/ESC) →
  W3 동영상 재생(HTML5 video + 기존 Range 지원) → W4 인물 뷰 → W5 검색(Phase 5와 함께)
- **W1~W4 구현 완료 (2026-08-13, 브라우저 스모크 테스트 통과).** 파일: static/index.html,
  app.js, style.css + AuthController(쿠키 발급) + ApiKeyFilter 확장(헤더 or 쿠키)
- **Immich 스타일 레이아웃 (2026-08-13)**: 왼쪽 사이드바(사진/인물/즐겨찾기 + 지도·앨범은
  "준비 중" 자리만), 오른쪽 연도 스크럽바(클릭 시 해당 연도로 점프 — 커서를
  "{연도+1}-01-01~0"으로 합성해 그 연도부터 로드). **즐겨찾기 기능 추가**: assets.favorite
  컬럼(마이그레이션: ALTER TABLE try-catch), POST /assets/{id}/favorite, 목록 favorite=true
  필터, 라이트박스 ☆ 토글(단축키 f). 앱에는 즐겨찾기 UI 미반영(추후)
- **연도 스크럽바 = 스크롤 이동, 양방향 무한 스크롤 (2026-08-19)**: 연도 클릭이 필터가 아니라
  그 연도로의 스크롤 이동. 해당 연도 헤더가 그리드에 있으면 부드럽게 스크롤, 없으면 "{연도+1}-01-01~0"
  커서로 다시 로드하되 그보다 최신 사진은 위로 스크롤할 때 `GET /assets?after=<cursor>`(최신 방향,
  응답은 최신순 유지 + prevCursor)로 이어 받는다(prependItems: 셀 인덱스 재부여 + scrollTop 보정,
  `#content{overflow-anchor:none}`). 스크럽바는 별도 열이 아니라 그리드 위에 뜨는 반투명 오버레이
  (`#layout` 기준 absolute). 타임라인 정렬용 `idx_assets_taken_at(taken_at, id)` 인덱스 추가.
- **대용량 그리드 성능 (2026-08-19)**: 썸네일 경로를 `thumbs/ab/cd/{hash}_{size}.jpg`로 2단계 샤딩
  (`ThumbnailService.thumbPath`가 유일한 경로 산출처; 시작 시 백그라운드로 옛 평면 파일 이동 + 요청 시 지연
  이동). `RequestTimingFilter`: 500ms(`homephoto.slow-request-ms`) 초과 API 요청을 WARN "느린 요청"으로 기록.
  그리드 셀 `content-visibility:auto`, 썸네일 `decoding=async` + 앞 40셀 `fetchpriority=high`.
  `server/defender-exclude.ps1`: 저장소 폴더를 Defender 실시간 검사에서 제외(관리자 권한, 릴리스에 동봉).
- **대시보드 (2026-08-19)**: `/dashboard.html` — 사이드바(개요·촬영 추이·저장소/작업)를 갖춘 독립 페이지.
  메인 사이드바 "설정" 바로 위 메뉴에서 새 탭으로 연다. 인증은 메인과 같은 hp_auth 쿠키 공유.
  API는 `GET /api/v1/stats/summary`(총 장수·용량·즐겨찾기·인물·기기·작업 큐·디스크 여유)와
  `GET /api/v1/stats/timeseries?unit=year|month|day&limit=N`(taken_at 앞 4/7/10자로 그룹핑).
  그래프는 외부 라이브러리 없이 인라인 SVG로 그린다(dashboard.js: drawLineChart, 호버 툴팁 포함).
  일자별은 기본 최근 730점으로 제한. 메뉴·카드는 앞으로 추가할 수 있게 패널 단위로 분리해 뒀다.
- **썸네일 워커 병렬화 (2026-08-19)**: 이미지 디코딩이 CPU 바운드(장당 0.7~1.3초)라 단일 스레드가
  최초 임포트의 병목이었다. `homephoto.thumbnail-threads`(0=자동: 코어 절반, 최대 4)만큼 스레드를 띄운다.
  DB 클레임만 `claimLock`으로 직렬화하고 생성은 락 밖에서 병렬 — 클레임은 "UPDATE로 PENDING 한 건을
  RUNNING으로(쓰기 우선 규율 유지) → RUNNING 중 inFlight에 없는 행을 집기"로, 여러 스레드가 같은
  행을 잡지 않는다. 실측(사진 60장): 1스레드 52초 → 4스레드 12초, 작업당 attempts=1(중복 처리 없음).
- **DB 파일 위치 분리 (2026-08-19)**: `homephoto.db-path`(설정 페이지 "DB 파일 위치") — 사진은 HDD,
  DB만 SSD에 두는 구성용. 빈 값이면 기존대로 저장소 안의 `db`. 연결은 `DataSourceConfig`가 코드로
  만든다 — YAML 플레이스홀더 기본값은 "미정의"에만 적용되고 빈 문자열에는 안 먹어서
  `jdbc:sqlite:/photos.db`가 나왔다. 우선순위: spring.datasource.url > db-path > 저장소/db.
  위치만 바꾸고 파일을 안 옮기면 빈 DB가 생겨 "사진 0장"이 되므로, 연결 **전에** 확인해 경고를 띄운다
  (연결 후엔 SQLite가 이미 빈 파일을 만들어 늦다).
- **서버 경로 찾기 창 (2026-08-19)**: 설정의 저장소 경로·DB 파일 위치·ffmpeg 경로와 임포트의 "가져올 폴더"
  옆 폴더 아이콘 → `GET /api/v1/admin/browse?path=&files=`로 **서버** 파일 시스템을 훑는 모달.
  `<input type="file">`은 쓸 수 없다 — 브라우저가 절대경로를 숨기고(`C:akepath\...`,
  webkitRelativePath는 선택 폴더 기준 상대경로) 무엇보다 그건 **클라이언트** 경로라 폰·다른 PC에서
  열면 서버에 의미가 없다. File System Access API도 핸들 이름만 주고 http:// LAN 접속에선 막힌다.
  경로 없음/권한 없음은 404·빈 목록+안내로 돌려줘 창이 죽지 않게 한다. 폴더 모드에서도 파일을
  회색으로 보여준다(선택은 불가) — 사진만 든 폴더가 "비어 있음"으로 보이면 임포트 폴더를 제대로
  찾아왔는지 알 수 없다. 항목은 2000개에서 자르고 잘렸음을 표시한다(수만 장 폴더 대비).
- **라이트박스 개선 (2026-08-13)**: ⓘ 버튼/단축키 `i`로 EXIF 정보 패널 토글(파일명·촬영일시+판정소스·
  카메라·해상도·크기·재생시간·백업기기·GPS→OSM 링크). AssetDto에 cameraMake/cameraModel/gpsLat/gpsLon
  추가. 앞뒤 ±2장 프리로드 캐시(디코딩된 img 재사용)로 키보드 탐색 시 깜빡임 제거.
  GPS (0,0)은 위치 없음으로 취급(추출·표시 양쪽 가드).
  마우스: 휠 아래/위 = 다음/이전(120ms 쿨다운 — 트랙패드 관성 방지, 정보 패널 위에선
  패널 스크롤 우선), 가운데(휠) 클릭 = 정보 패널 토글 (2026-08-14).
  닫을 때 마지막 본 사진으로 그리드 스크롤(가운데 정렬) + 1.6초 파랑 테두리 강조 —
  뷰어에서 수백 장 넘겨도 목록이 따라감 (셀 data-index 기반) (2026-08-14).
  그리드 크기 5단계 셀렉트(아주작게 100px ~ 아주크게 320px) — --cell-min CSS 변수,
  localStorage 저장, 인물 그리드에는 미적용 (2026-08-14).
  기간 그룹핑 셀렉트(일별/주별/월별) — 그리드 크기와 완전 분리(기존 xs/s 자동 일자
  그룹핑 제거). 주차는 월 내 주차(1~7일=1주차), 여정 버튼은 일/월 헤더에만.
  localStorage 저장, 모드 변경 시 재조회 없이 헤더만 재렌더 (2026-08-14).
  다중 선택 확장 (2026-08-14): 썸네일 0.5초 길게 누르기(터치·마우스 공용, 10px 이동 시
  취소)로 선택 모드 진입 + 그 사진 즉시 선택. 선택 바에 '삭제' 추가 — 휴지통 이동,
  실패 건수 안내. 선택 모드에선 좌상단 체크박스가 즐겨찾기 배지 대신 표시
- **기기별 뷰 (2026-08-14)**: 사이드바 '기기별' — 백업 기기 목록(최신 사진 표지·장수·
  이름 변경 ✏️) → 클릭 시 그 기기 사진만 필터된 타임라인(뒤로가기 지원).
  서버: GET /assets에 deviceId 파라미터 추가. 기기 목록/이름변경은 기존 /devices API 재사용
- **인물 관리 (2026-08-14)**: 웹 인물 탭에서 이름 수정(✏️)·클러스터 합치기(🔀)·삭제(🗑️).
  합치기는 원본 얼굴 전체를 대상 클러스터로 이동 + person(이름) 승계(대상 우선).
  삭제는 faces.hidden=1 숨김 처리(사진·얼굴 데이터 보존, 재클러스터링에도 유지).
  API: POST /faces/clusters/{id}/merge {into}, DELETE /faces/clusters/{id}.
  한계: 이름 없는 합치기는 재클러스터링(DBSCAN 재실행) 시 초기화됨 — person 연결만 영구
- **사진별 인물 정정 (2026-08-14)**: 인물 상세 뷰에서 사진 호버 → 👤(다른 인물로 이동,
  인물 선택 모달) / ✕(이 인물에서 제외 = 얼굴 숨김). 해당 사진의 그 클러스터 얼굴만
  이동·숨김 처리 — 단체사진의 다른 인물 얼굴은 영향 없음. person 연결은 대상 클러스터
  것으로 교체(없으면 해제). API: POST /faces/reassign {assetId, fromCluster, toCluster|null}
- 데스크톱 차별점: 화면 폭 맞춤 그리드, 키보드 탐색, 동영상 재생(웹이 앱보다 먼저), 월 점프
- **모바일 반응형 (2026-08-14)**: `@media (max-width: 768px)` — 사이드바가 하단 탭바로
  전환(사진/인물/즐겨찾기/휴지통/설정, 아이콘+라벨 세로 배치, safe-area 패딩), 브랜드·
  "준비 중" 메뉴·연도 스크럽바·그리드 크기 셀렉트 숨김, 셀 90px 고정. 라이트박스
  좌우 스와이프로 이전/다음(세로 제스처와 구분: |dx|>60 && |dx|>1.5|dy|).
  높이는 100dvh(모바일 크롬 주소창 잘림 방지, vh 폴백)
- **지도 뷰 (2026-08-14)**: 삼성 갤러리 스타일 — 줌 레벨별 사진 핀(대표 썸네일+개수 배지),
  핀 클릭 시 하단 썸네일 패널 슬라이드업, 썸네일 클릭 시 기존 라이트박스.
  - 지도: **Leaflet 1.9.4 로컬 번들**(`static/vendor/leaflet/` — CDN 의존 배제) + OSM 타일.
    한국 중심 기본, 위치·줌 localStorage 저장, 최초 진입은 전체 클러스터로 fitBounds.
    moveend 300ms 디바운스 + AbortController로 이전 요청 취소
  - 클러스터링: **서버 그리드 스냅** — `GET /api/v1/map/clusters?zoom&minLat&maxLat&minLon&maxLon`
    (MapController). 셀 크기 `360°×88px/(256×2^zoom)`(화면상 핀 간격 일정),
    `CAST((gps_lat+90)/cell AS INT)` GROUP BY(+시프트로 floor 대체 — SQLite math 확장 미의존).
    응답: 평균 좌표(핀 위치)·count·coverAssetId(MAX(id))·멤버 실좌표 min/max.
    수십만 장까지 뷰포트당 수십 셀만 전송. 키즈노트(`source IS NULL`)·(0,0)·휴지통 제외
  - 클러스터 상세: **기존 `GET /assets`에 bbox 4파라미터 확장** — 클러스터의 멤버 min/max를
    그대로 넘겨 커서 페이징·AssetDto 전부 재사용. 패널 목록도 `state.items`에 담아
    라이트박스(탐색·즐겨찾기·삭제·프리로드)가 무수정으로 동작
  - 부수 수정: 필수 파라미터 누락·타입 오류가 catch-all 500으로 삼켜지던 것을 400으로
    (ApiExceptionHandler에 MissingServletRequestParameter/MethodArgumentTypeMismatch 핸들러)
- **수동 앨범 (2026-08-14)**: albums + album_assets(M:N, UNIQUE로 멱등) — AlbumController.
  - API: GET/POST `/albums`, POST `/albums/{id}/name`, DELETE `/albums/{id}`(연결만 삭제),
    POST `/albums/{id}/assets` {assetIds}→{added,requested}(insertIgnore 멱등, 실재·활성 자산만),
    POST `/albums/{id}/assets/remove`(DELETE+body는 클라이언트 호환성 문제로 POST).
    상세는 **기존 `GET /assets`에 `albumId` 필터**(inSubQuery) — 커서 페이징·라이트박스 재사용,
    기본 WHERE(deleted_at IS NULL) 덕에 휴지통 사진은 앨범에서 자동 제외되고 복원 시 복귀.
    목록 count·자동 커버(takenAt 최신)도 동일 필터로 일치. 정렬은 takenAt DESC 재사용
    (added_at 정렬은 커서 스킴·월 헤더 전면 수정이라 기각)
  - 웹: 사이드바 앨범 탭(목록: 새 앨범 카드 + 커버/이름/장수 + hover 이름변경·삭제 —
    인물 뷰 패턴), 상세는 person 뷰처럼 그리드+백버튼+hover "앨범에서 제거".
    **다중 선택 모드**: 타임라인/즐겨찾기 topbar "선택" → 셀 체크 토글(라이트박스 억제) →
    하단 고정 바 "N장 선택·앨범에 추가·취소(ESC)" → 앨범 선택 모달(person-picker 패턴,
    최상단 "새 앨범 만들기"). 라이트박스에도 단건 추가 버튼(#lb-album, 폴더 아이콘).
    ESC 우선순위: album-picker → person-picker → 선택 모드 → merge → 라이트박스
- **일자별 그룹핑 (2026-08-14)**: 그리드 크기 xs/s(아주 작게·작게)에서는 월 헤더 대신
  일자 헤더("2026년 8월 12일 (수)")로 그룹핑. `state.lastGroup`이 월/일 키를 겸용,
  크기 변경으로 월↔일 경계를 넘으면 rerenderGrid()로 헤더 재구성. 정렬·커서는 불변
- **하루 여정 뷰어 (2026-08-14)**: 일자 헤더 옆 타임라인 버튼 → 라이트박스식 전체 화면
  오버레이(z-index 45). 상단은 Leaflet 지도에 그날 GPS 사진의 **시간순 번호 마커**,
  하단은 시간 오름차순 썸네일 스트립(촬영 시각 라벨 + 지도 번호 배지). 마커 클릭 →
  스트립 해당 셀로 스크롤·강조.
  **마커·배지는 촬영자(백업 기기 device_id)별 색 구분** — 기기마다 색(DAY_ROUTE_COLORS
  6색 순환)과 독립 순번을 배정하고 좌상단 범례(색 → 기기명 · N곳)로 구분.
  device_id NULL은 "기타 기기" 그룹. 지점 간 직선(polyline)은 실제 이동 경로가 아니라
  오해를 줘서 그렸다가 제거함(같은 날 저녁 사용자 피드백) — 색·번호로만 동선 표현.
  - 서버: `GET /assets?day=YYYY-MM-DD` 필터 추가 (taken_at ISO 텍스트 prefix LIKE)
  - 라이트박스 재사용 트릭: 열 때 state.items/cursor/reachedEnd를 스냅샷하고 그날
    사진(오름차순)으로 바꿔치기 + reachedEnd=true(경계 loadMore 무력화) → 스트립 클릭이
    기존 라이트박스를 그대로 사용, 닫으면 스냅샷 복원. focusGridCell은 뷰어 열림 시
    #day-strip 범위로 한정. ESC: 라이트박스 먼저, 그다음 뷰어
- **주/월별 여정 뷰어 (2026-08-14)**: 주·월 헤더에도 타임라인 버튼 — 같은 여정 뷰어를
  `yearMonth` 필터로 열고 커서로 전 페이지 수집(상한 DAY_VIEWER_MAX=2000, 초과 시
  "최근 N장만 표시"). 주 모드(`YYYY-MM-Wn` 키)는 그 달을 받아온 뒤 월내 주차 범위
  ((n-1)*7+1 ~ n*7일 — groupKeyFor의 주차 계산과 동일)로 클라이언트 필터.
  주/월 모드 스트립 라벨은 "12일 09:05"처럼 일자 포함(dayViewerMultiDay)
- **설정: 서버 접속 주소 표시 (2026-08-14)**: `GET /api/v1/admin/server-info` —
  실행 중 웹서버 포트(WebServerApplicationContext) + site-local IPv4 나열. 설정 페이지
  최상단 "서버 정보" 섹션에 `http://IP:포트` 칩으로 표시(user-select:all로 복사 용이).
  폰 앱 서버 주소 설정용 안내
- **브라우저 뒤로 가기 (2026-08-14)**: 가드 히스토리 항목 1개를 pushState로 유지 —
  popstate 시 `handleBack()`이 ESC와 같은 우선순위로 한 겹 닫고(모달 → 라이트박스 →
  여정 뷰어 → 선택/합치기 모드 → 지도 패널 → person/album은 상위 목록으로 → 그 외
  뷰는 타임라인으로) 가드를 재푸시. 닫을 게 없는 타임라인 최상위에서만 history.back()으로
  실제 이탈. 모바일에서 뒤로 가기가 곧장 앱 이탈이 되던 문제의 해결책

## 7. 로드맵

- **Phase 1 — 백업 코어**: 업로드 API + 해시 중복제거 + EXIF 월별 저장 + 썸네일 +
  일괄 임포트 도구 (개발용 1,000장 임포트) — **서버측 구현 완료 (2026-08-10).**
  업로드/중복/날짜판정/썸네일워커/임포트 엔드투엔드 테스트 통과. 남은 것: ffmpeg
  설치(HEIC·동영상 썸네일), 개발용 1,000장 실제 임포트
- **Phase 2 — 뷰어**: 웹 타임라인 + 안드로이드 자동 백업(WorkManager) + 앱 뷰어

### Android 클라이언트 세부 계획 (2026-08-11 확정)

- 프로젝트: `android/` (패키지 `com.chochocho.homephotoclient`, minSdk 31, AGP 9.x).
  위저드 "No Activity" 템플릿에서 Kotlin+Compose로 전환하여 사용
- 스택: Compose + Retrofit/OkHttp + Room(업로드 상태 추적) + WorkManager + Coil +
  DataStore(설정). DI 프레임워크는 도입하지 않고 수동 주입 (필요시 추후)
- 백업 대상 기본값: **DCIM(카메라)만** — 설정에서 전체 사진으로 변경 가능하게
- 로컬 Room DB에 (파일 → 해시, 업로드 상태) 기록. 재설치 시 /assets/check로 재동기화
- 평문 HTTP: 집 LAN(http://사설IP)이므로 network security config로 cleartext 허용(개발용)
- 단계: C1 설정+연결테스트 → C2 수동 백업(스캔·해시·업로드) → C3 WorkManager 자동화
  → C4 타임라인 뷰어 → C5 검색·인물 탭 (각 단계 완료 시 동작 가능 상태 유지)
- **위치 EXIF 유실 버그 수정 (2026-08-14)**: ACCESS_MEDIA_LOCATION 권한 없이 MediaStore
  스트림을 읽으면 Android가 GPS를 0으로 지운 사본을 준다 → 서버의 기존 1,552장 전부
  GPS(0,0)으로 저장돼 있었음. 매니페스트+런타임 권한 추가, 해시·업로드 시
  MediaStore.setRequireOriginal()로 원본 요구. 주의: 원본은 바이트가 달라 해시도 달라짐
  — 기존 업로드분은 재백업 시 새 자산으로 중복 등록됨(정리 방안 미정)
- **Phase 3 — 얼굴**: Python InsightFace 워커 + 클러스터링 + 인물 뷰/이름 붙이기
  — **서버(internal API·faces/persons 테이블·FACE 작업 백필)와 ml-worker/(InsightFace
  CPU + DBSCAN) 구현 완료 (2026-08-12).** 남은 것: 앱 인물 탭 UI, 이름 붙이기 API
- **Phase 4 — 장면 분석**: GB10 Gemma 캡션/태그 워커 (최근 사진 우선 백필)
  — **서버측 구현 완료 (2026-08-14, 상세 6.6).** captions 테이블 + CaptionWorker(내장,
  Ollama OpenAI 호환 호출) + 작업 등록/백필 + 캡션 조회 API + 웹 정보 패널 표시.
  남은 것: GB10 주소 설정(`homephoto.caption.*`) 후 실환경 검증
- **Phase 5 — 검색**: FTS5 통합 검색 + 앱 검색 UI (필요시 시맨틱 검색 확장)
- **지도**: 웹 지도 뷰 구현 완료 (2026-08-14, 상세 6.5). 남은 것: 앱 지도 탭
  (서버 API는 클라이언트 중립이라 그대로 재사용), 키즈노트 포함 토글(서버 WHERE
  `source IS NULL` 한 줄 파라미터화)
- **앨범**: 웹 수동 앨범 구현 완료 (2026-08-14, 상세 6.5). 남은 것: 앱 앨범 탭,
  커버 직접 지정 UI(cover_asset_id 컬럼은 확보됨), 스마트 앨범(검색 Phase 5 이후),
  영구삭제 시 album_assets 고아 행 정리(현재는 필터가 가려 줘 무해)
- **이후**: 2TB 전체 백필, 동영상 ML(키프레임), 웹 뷰어 고도화
