# 키즈노트(Kidsnote) 도메인 추가 계획

## Context

E:\kidsnote에 두 아이(규연, 승연)의 키즈노트 백업이 있다: `아이\yyyy\yyyy-MM\yyyy-MM-dd\` 구조로 1,712일치, 알림장 2,176건, 사진 20,285장(~21.8GB). 각 일자 폴더에 `content.json`(알림장 배열)과 `{글ID}_{이미지ID}.jpg` 사진이 있으며 JSON↔사진 매핑은 100% 일치. 영상 115건은 CDN URL 메타데이터만 존재(로컬 파일 없음). 파일 mtime은 백업 시점(2026-03)이라 날짜로 못 쓰고 `date_written`이 정확한 날짜.

이 데이터를 홈 포토 백업 서버(Kotlin + Spring Boot + Exposed + SQLite)에 임포트하고, 새 브라우저 탭에서 열리는 키즈노트 뷰어를 만든다.

**사용자 결정 사항:**
1. 사진은 기존 `assets` 파이프라인(중복제거·썸네일·EXIF)을 재사용하되 **메인 타임라인에서는 제외** — 키즈노트 뷰어 전용
2. FTS5 텍스트 검색은 이번에 제외 (추후 Phase)
3. 영상은 임포트 시 CDN 다운로드 시도, 실패 시 메타데이터만 보관 + 뷰어에 "영상 유실" 표시
4. 뷰어는 `kidsnote.html` 별도 정적 페이지, 사이드바에서 `target="_blank"` 링크로 열기

## 핵심 설계 결정

- **타임라인 제외 방식**: `assets`에 nullable `source` TEXT 컬럼 추가(`'KIDSNOTE'` / NULL=일반). 타임라인 쿼리에 `source IS NULL` 조건만 추가하면 되어 비용이 가장 저렴하고, 나중에 폰 업로드로 같은 사진이 들어오면 NULL로 "승격"시켜 메인에도 표시 가능(뷰어는 링크 테이블로 asset id를 참조하므로 무관).
- **키즈노트 자산은 FACE/CAPTION 잡 생략** (썸네일만). 어린이집 사진 2만장이 인물 뷰를 오염시키고 VLM 연산을 낭비하는 것 방지. `DataInitializer`의 백필 SQL에도 `AND source IS NULL` 가드 필수.
- **`assets.takenAt` = `created_at`을 Asia/Seoul로 변환한 시각(단, KST 날짜가 `date_written`과 일치할 때), 불일치 시 `date_written`T12:00**. `takenAtSource="KIDSNOTE"`. TakenAtResolver를 우회하는 `takenAtOverride` 파라미터 신설 필요(파일명에 날짜 없음 + mtime 무의미).
- **영상 다운로드는 임포트 스레드 인라인** (115건뿐이라 워커 불필요). high_url → low_url 순 시도.
- **뷰어 API는 월 단위 조회, 커서 없음** (월평균 ~25건이라 페이지네이션 불필요).
- **멱등성 키**: 키즈노트 글 `id`(전역 고유) → `kidsnote_posts.post_id` UNIQUE. 재실행 시 존재하는 글은 스킵(LOST 영상만 재시도).

## 1. DB 스키마

### 신규 테이블 — `server/src/main/kotlin/com/homephoto/server/db/Tables.kt`에 추가

```kotlin
object KidsnoteChildren : Table("kidsnote_children") {
    val id = long("id").autoIncrement()
    val folderName = text("folder_name").uniqueIndex()   // "규연" (E:\kidsnote 하위 폴더명)
    val childName = text("child_name")                   // "조규연" (JSON child_name)
    val createdAt = text("created_at")
    override val primaryKey = PrimaryKey(id)
}

object KidsnotePosts : Table("kidsnote_posts") {
    val id = long("id").autoIncrement()
    val postId = long("post_id").uniqueIndex()           // 키즈노트 전역 고유 id — 멱등성 키
    val childId = long("child_id").references(KidsnoteChildren.id)
    val dateWritten = text("date_written")               // "2018-12-14" — 일자 버킷 기준
    val yearMonth = text("year_month")                   // "2018-12" 파생
    val content = text("content")
    val authorName = text("author_name")
    val createdAt = text("created_at")                   // JSON 원본 ISO-8601 UTC
    val videoStatus = text("video_status").nullable()    // NULL(없음) | DOWNLOADED | LOST
    val videoAssetId = long("video_asset_id").references(Assets.id).nullable()
    val videoOriginalName = text("video_original_name").nullable()
    val videoHighUrl = text("video_high_url").nullable() // 재시도용 보존
    val videoLowUrl = text("video_low_url").nullable()
    val importedAt = text("imported_at")
    override val primaryKey = PrimaryKey(id)
    init {
        index(false, childId, yearMonth)
        index(false, childId, dateWritten)
    }
}

object KidsnotePostImages : Table("kidsnote_post_images") {
    val id = long("id").autoIncrement()
    val postId = long("post_id")                         // KidsnotePosts.postId (자연키)
    val assetId = long("asset_id").references(Assets.id)
    val filename = text("filename")                      // "266621988_579040456.jpg"
    val seq = integer("seq")                             // JSON 배열 순서
    override val primaryKey = PrimaryKey(id)
    init {
        uniqueIndex(postId, filename)                    // 재임포트 멱등성 (insertIgnore)
        index(false, assetId)
    }
}
```

- `Assets`에 `val source = text("source").nullable()` 추가.
- 다운로드 성공한 영상 = 일반 `assets` 행(mediaType VIDEO, source='KIDSNOTE') + `video_asset_id` 참조. 썸네일 잡은 등록(ffmpeg 포스터 프레임), ML 잡은 생략.
- `author_name`은 원문 저장; 교사/가족 구분은 뷰어에서 `(엄마|아빠)$` 접미사 휴리스틱으로 파생.

### 마이그레이션 — `config/DataInitializer.kt`

- `SchemaUtils.create(...)`에 신규 3개 테이블 추가
- `runCatching { exec("ALTER TABLE assets ADD COLUMN source TEXT") }` 추가
- **FACE/CAPTION 백필 SQL(67행 부근) WHERE에 `AND source IS NULL` 추가** — 없으면 재시작마다 키즈노트 사진 전체에 ML 잡 재등록됨

### 타임라인 제외 지점 — `api/AssetController.kt` 두 곳만

1. `months()` (106행): `.where { Assets.deletedAt.isNull() and Assets.source.isNull() }`
2. `list()` (125행): 베이스 where에 `and Assets.source.isNull()` — 타임라인·월필터·즐겨찾기·인물 뷰 모두 이 베이스를 공유하므로 한 곳으로 충분

`get()`/`thumb()`/`file()`은 필터하지 않음(뷰어가 id로 접근). `check()`는 승격 경로 수정(아래).

## 2. 인제스트 확장 — `service/AssetIngestService.kt`

`ingest()`에 옵션 파라미터 3개 추가:

```kotlin
takenAtOverride: TakenAtResolver.Resolved? = null,  // 키즈노트: Resolved(dt, "KIDSNOTE")
sourceTag: String? = null,                          // 신규 INSERT 시 assets.source에 기록
skipMlJobs: Boolean = false,                        // FACE/CAPTION 미등록 (THUMBNAIL은 항상)
```

- 71행: `val resolved = takenAtOverride ?: takenAtResolver.resolve(...)`
- INSERT 블록: `it[Assets.source] = sourceTag`
- `if (mediaType == "PHOTO")` → `if (mediaType == "PHOTO" && !skipMlJobs)`

### 중복(해시 일치) 시 의미론 — 핵심

- **키즈노트 임포트가 메인 라이브러리 기존 사진과 충돌**: 기존 행 유지(`source` 건드리지 않음), 링크 테이블만 연결 → 메인 타임라인에도 남고 뷰어에도 표시. 정상.
- **폰 업로드가 키즈노트 전용 자산과 충돌** (`existingRow.source != null`, 호출자 `sourceTag == null`): **승격** — `source = NULL`, deviceId 갱신, FACE/CAPTION 잡 `insertIgnore`, `created=true` 반환(폰이 백업 완료로 인식). 뷰어는 영향 없음.
- **`AssetController.check()`**: `source != null && deletedAt == null`인 행은 **missing**으로 분류 → 폰이 업로드하고 위 승격 경로가 발동. 이게 없으면 사진이 영원히 타임라인에서 숨겨짐.

## 3. 임포트 — 신규 `service/KidsnoteImportService.kt`

`ImportService.kt` 패턴 복제: `AtomicBoolean running` + 데몬 스레드 + `@Volatile status`.

- JSON 모델: `KidsnotePostJson`(8필드, `@JsonProperty` 스네이크케이스 매핑), `KidsnoteVideoJson`.
- 상수: `KIDSNOTE_DEVICE_ID = "kidsnote"`, 디바이스명 `"키즈노트"`, `SOURCE_TAG = "KIDSNOTE"`.
- 순회: 아이 폴더(정렬) → `content.json` 전체 수집(오래된 날짜부터, totalDays 파악용 사전 스캔).
- 아이별: `kidsnote_children`을 `folderName`으로 upsert.

**글 단위 처리 순서 (크래시 안전 멱등성):**
1. `postId` 존재 시 스킵 — 단 `videoStatus == 'LOST'`면 영상 다운로드만 재시도
2. takenAt 계산 (위 정책)
3. 이미지별 `ingest(..., moveSource=false, takenAtOverride, sourceTag, skipMlJobs=true)` 호출(복사, 원본 보존). 디스크에 없는 파일은 failed 카운트 후 계속
4. 영상: `java.net.http.HttpClient`(connect 10s / request 300s), `props.uploadTmpDir`에 임시 저장 후 `moveSource=true` 인제스트. high→low 순. 실패 시 `videoStatus='LOST'`, URL 보존. 영상 실패로 글 실패시키지 않음
5. 마지막에 단일 `transaction { }`: `kidsnote_posts` INSERT + `kidsnote_post_images` insertIgnore. **글 행을 마지막에 쓰므로** 중간 크래시 시 고아 자산만 남고(해시 중복제거로 재사용됨) 재실행이 깨끗하게 복구

**진행 상태 DTO:**
```kotlin
data class KidsnoteImportStatusDto(
    val running: Boolean, val totalDays: Int, val processedDays: Int,
    val posts: Int, val newPosts: Int, val images: Int, val newImages: Int,
    val videosDownloaded: Int, val videosLost: Int, val failed: Int,
    val currentFolder: String?, val lastError: String?,
)
```

**엔드포인트 — `api/AdminController.kt`에 추가:**
- `POST /api/v1/admin/kidsnote/import` body `{"sourcePath": "E:\\kidsnote"}` → 202 / 409(실행 중)
- `GET /api/v1/admin/kidsnote/import/status`

소스 경로는 기존 임포트처럼 요청 파라미터로만 받음(SettingsService/YAML 변경 없음).

## 4. 뷰어 API — 신규 `api/KidsnoteController.kt`

`@RestController @RequestMapping("/api/v1/kidsnote")`, 컨트롤러 로컬 DTO, `transaction { }` 직접 사용. ApiKeyFilter가 자동 보호.

```
GET /api/v1/kidsnote/children
  → [ChildDto(id, folderName, childName, firstDate, lastDate, postCount, imageCount)]

GET /api/v1/kidsnote/children/{id}/months
  → [KnMonthDto(yearMonth, postCount, imageCount)] yearMonth ASC

GET /api/v1/kidsnote/children/{id}/posts?yearMonth=2023-01
  → [PostDto(postId, dateWritten, content, authorName, createdAt,
             images: [PostImageDto(assetId, filename, width, height)],
             video: PostVideoDto(status, assetId, originalFileName)?)]
  dateWritten ASC, postId ASC; 이미지는 seq 순.
  구현: 월 글 1쿼리 + 링크 테이블⋈Assets 1쿼리 후 메모리 그룹핑

GET /api/v1/kidsnote/posts/{postId} → PostDto (라이트박스 딥링크용)
```

사진/영상 바이트는 기존 엔드포인트 재사용: `/api/v1/assets/{id}/thumb?size=400|1600`, `/api/v1/assets/{id}/file`(Range 지원 → 영상 재생 가능).

## 5. 뷰어 UI — 신규 `static/kidsnote.html` / `kidsnote.js` / `kidsnote.css`

**kidsnote.html** (한국어 UI, 다크 테마, `?v=` 캐시버스터):
```
<header>  "키즈노트" · #child-tabs(규연/승연) · #year-select · #month-chips
<main id="feed">   날짜별 카드 스트림
<div id="lightbox"> (index.html 라이트박스 축소판)
```

**kidsnote.js** (바닐라 JS, app.js 관용구 유지):
- `api()` 래퍼는 app.js에서 복사하되 401 시 `location.replace("/")` — 로그인은 메인 페이지가 담당(쿠키 path=/)
- 부팅: children → 탭 렌더 → 첫 아이 선택 → months → 연도 드롭다운 + 월 칩(글 수 뱃지) → 최신 월 로드
- `loadMonth()`: 글을 `dateWritten`으로 그룹핑, 날짜 헤딩("2023년 1월 12일 (목)"), 글마다 작성자 뱃지(가족/선생님), 본문은 `textContent` + `white-space: pre-wrap`(XSS 안전, 줄바꿈 보존), 사진 그리드 `<img loading="lazy" src=".../thumb?size=400">`
- 영상: DOWNLOADED → `<video controls preload="none" src=".../file">`; LOST → "영상 유실 — 원본이 CDN에서 만료되었습니다" + 원본 파일명 표시
- 라이트박스: app.js `openLightbox`/`renderLightbox`/`moveLightbox` 축소 포팅 — 로드된 월의 이미지 평탄 리스트 탐색, 1600 썸네일, ESC/화살표. 읽기 전용(즐겨찾기·삭제 없음)
- 피드 상/하단에 이전/다음 월 버튼

**kidsnote.css**: style.css의 다크 테마 상수(#111 배경, #181818 패널, #262626 보더, #eee 텍스트, #4a7dff 액센트, 동일 폰트 스택) 재사용한 독립 스타일시트. style.css는 #layout 그리드 전제라 링크하지 않음.

## 6. 사이드바 — `static/index.html`

휴지통 항목 다음, 지도(준비 중) 앞에:

```html
<a href="/kidsnote.html" target="_blank" class="nav-item"><svg class="icon"><use href="#i-folder"/></svg> 키즈노트</a>
```

`data-view` 없는 일반 링크 → SPA 클릭 핸들러(`querySelectorAll(".nav-item[data-view]")`)에 안 걸려 새 탭으로 자연스럽게 열림. `?v=` 캐시버스터 2곳 증가.

## 7. 구현 순서 및 검증

**Step 0 — 계획 문서화**: 이 계획을 `docs/KIDSNOTE.md`로 프로젝트에 저장 (사용자 요청).

**Step 1 — 스키마 + 타임라인 제외**
수정: `db/Tables.kt`, `config/DataInitializer.kt`, `api/AssetController.kt`
검증: 기존 DB로 부팅(ALTER 적용 확인), 메인 타임라인 불변, 재시작 2회 후 FACE/CAPTION 잡 수 증가 없음

**Step 2 — 인제스트 확장**
수정: `service/AssetIngestService.kt`
검증: 일반 폰 업로드 정상(201/409), takenAtOverride가 파일명/EXIF보다 우선함 확인

**Step 3 — 임포터**
생성: `service/KidsnoteImportService.kt` / 수정: `api/AdminController.kt`, `api/Dtos.kt`
검증:
- 스모크: 일자 폴더 1개 복사본으로 임포트 → 테이블 행·originals 배치 확인
- 전체 실행: posts=2176, images=20285(교차 중복 제외), videosDownloaded+videosLost=115
- 멱등성: 즉시 재실행 → newPosts=0, newImages=0, 수 초 내 완료
- 타임라인 제외: `/api/v1/months` 수치 임포트 전과 동일, `/api/v1/assets`에 키즈노트 없음, thumb는 서빙됨
- 승격: 키즈노트와 동일한 JPG를 업로드 → 201, source NULL, 타임라인 표시 + 뷰어에도 유지

**Step 4 — 뷰어 API**
생성: `api/KidsnoteController.kt`
검증: curl + X-Api-Key로 children 2행·날짜 범위, 월 합계=글 수, 특정 월(2018-12 규연=1글 2장) 디스크와 대조

**Step 5 — 뷰어 UI + 사이드바**
생성: `static/kidsnote.html|js|css` / 수정: `static/index.html`
검증: 비로그인 시크릿 창에서 `/kidsnote.html` → `/` 리다이렉트; 로그인 후 탭·월 내비·날짜 카드·pre-wrap 본문·lazy 썸네일 렌더; DOWNLOADED 영상 재생; LOST 표시; 라이트박스 키보드 조작; 사이드바 링크가 SPA 뷰 탈취 없이 새 탭 열기

### 핵심 파일
- `server/src/main/kotlin/com/homephoto/server/db/Tables.kt`
- `server/src/main/kotlin/com/homephoto/server/config/DataInitializer.kt`
- `server/src/main/kotlin/com/homephoto/server/service/AssetIngestService.kt`
- `server/src/main/kotlin/com/homephoto/server/api/AssetController.kt`
- `server/src/main/kotlin/com/homephoto/server/service/ImportService.kt` (임포터 템플릿)
- 신규: `KidsnoteImportService.kt`, `KidsnoteController.kt`, `static/kidsnote.{html,js,css}`
