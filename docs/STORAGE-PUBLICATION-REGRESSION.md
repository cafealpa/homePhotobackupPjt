# 원본 저장소 분리와 Google Photos 게시의 회귀 검증 기준

> 과거 검증 기록이다. 2026-10-08부터 Python 얼굴/검색 실행 경로는 제거되었으며 현재 동작과 검증은 [JVM 검색 안내](JVM-VECTOR-SEARCH.md)를 따른다.

## 1. 기준 시점과 1단계 범위

- 확인일: 2026-09-30, Asia/Seoul.
- 기준 코드: `8afb9fae8ac504f61ab1b261a183b432a60a6f3d`.
- 서버 버전: `0.1.6-rc2`.
- 환경: Windows, OpenJDK `21.0.2`, Gradle Wrapper `8.14.5`, Python `3.12.10`.
- 유지할 기술 스택: Kotlin/Spring Boot, Exposed/SQLite, Python 검색·얼굴 워커.

1단계는 현재 저장·조회·삭제·썸네일·메타데이터·작업 큐의 동작을 코드와 기존 테스트에 연결하고,
후속 변경의 검증 기준을 고정하는 작업이다. 운영 코드, DB 스키마, 설정, 저장 파일은 변경하지 않는다.
StorageAdapter와 GooglePhotosPublisher 구현 및 실제 저장소 이전은 후속 단계에서 진행한다.

이 문서는 위 커밋의 기준 기록이다. 이후 코드가 달라지면 해당 단계의 변경과 검증 결과를 별도로 기록한다.
전체 시스템 설명은 [기존 설계 문서](DESIGN.md)를 참고한다.

## 2. 현재 흐름과 변경 경계

아래 경로는 저장소 루트 기준이며, 링크는 현재 구현의 책임 위치를 가리킨다.

| 흐름 | 현재 구현 | 후속 변경에서 지킬 경계 |
|---|---|---|
| 최초 업로드 | [AssetController](../server/src/main/kotlin/com/homephoto/server/api/AssetController.kt)의 `upload`: multipart 수신 → 로컬 임시 파일과 SHA-256 생성 → `ingest` → 임시 파일 정리 | 기존 업로드 API와 해시 검증 유지. 수신 임시 파일은 홈서버 로컬에 둔다 |
| 폴더 가져오기 | [ImportService](../server/src/main/kotlin/com/homephoto/server/service/ImportService.kt): SCAN/COPY/MOVE, 공통 `ingest` 호출 | SCAN은 저장하지 않는다. COPY는 입력을 보존하고 MOVE는 저장 성공 후 입력을 정리한다 |
| 키즈노트 가져오기 | [KidsnoteImportService](../server/src/main/kotlin/com/homephoto/server/service/KidsnoteImportService.kt): 사진/동영상 확보 후 공통 `ingest` 호출 | 날짜 override, KIDSNOTE 출처, ML 작업 생략, 일반 업로드 시 승격 동작 유지 |
| 경로 결정·원본 저장·DB 등록 | [AssetIngestService](../server/src/main/kotlin/com/homephoto/server/service/AssetIngestService.kt)의 `ingest`, `placeOriginal`, `restore` | 경로 키 결정은 기존 정책을 유지하고 실제 저장·존재 확인만 어댑터로 분리한다 |
| 원본 HTTP 읽기 | [AssetController](../server/src/main/kotlin/com/homephoto/server/api/AssetController.kt)의 `/api/v1/assets/{id}/file`: DB 상대경로 → `FileSystemResource` | 다운로드 URL, MIME, 바이트 내용, 동영상 Range 동작 유지 |
| 원본 소비자 | [ThumbnailService](../server/src/main/kotlin/com/homephoto/server/service/ThumbnailService.kt)는 원본 Path, [JVM 얼굴 워커](../server/src/main/kotlin/com/homephoto/server/worker/FaceWorker.kt)는 StorageAdapter 사용 | 원본 접근 경계 변경 시 썸네일과 JVM 얼굴 분석이 함께 동작해야 한다 |
| 삭제·복원·영구 삭제 | [TrashService](../server/src/main/kotlin/com/homephoto/server/service/TrashService.kt), [TrashController](../server/src/main/kotlin/com/homephoto/server/api/TrashController.kt) | 휴지통은 파일을 보존하고 영구 삭제만 원본을 제거한다. DB tombstone 유지 |
| 썸네일 | [ThumbnailService](../server/src/main/kotlin/com/homephoto/server/service/ThumbnailService.kt), [ThumbnailStorage](../server/src/main/kotlin/com/homephoto/server/service/ThumbnailStorage.kt), [ThumbnailWorker](../server/src/main/kotlin/com/homephoto/server/worker/ThumbnailWorker.kt) | 원본 저장소와 별개로 기존 로컬 썸네일 저장·서빙 유지 |
| 메타데이터 | [ExifService](../server/src/main/kotlin/com/homephoto/server/service/ExifService.kt) → [TakenAtResolver](../server/src/main/kotlin/com/homephoto/server/service/TakenAtResolver.kt) → `AssetIngestService`의 DB INSERT | 기존 날짜 선택 정책과 GPS 처리 유지. Google Photos용 메타데이터는 별도 파생 파일에 기록 |
| 작업 큐 | [JobQueueService](../server/src/main/kotlin/com/homephoto/server/service/JobQueueService.kt), [StartupJobRecovery](../server/src/main/kotlin/com/homephoto/server/config/StartupJobRecovery.kt), [CaptionWorker](../server/src/main/kotlin/com/homephoto/server/worker/CaptionWorker.kt) | 원본 저장과 게시 성공을 분리하고 기존 THUMBNAIL/FACE/CAPTION 작업을 보존 |
| 설정·DI·시작 처리 | [AppProperties](../server/src/main/kotlin/com/homephoto/server/config/AppProperties.kt), [DataSourceConfig](../server/src/main/kotlin/com/homephoto/server/config/DataSourceConfig.kt), [DataInitializer](../server/src/main/kotlin/com/homephoto/server/config/DataInitializer.kt), [SettingsService](../server/src/main/kotlin/com/homephoto/server/service/SettingsService.kt) | 기존 설정만으로 로컬 저장이 계속 동작하고 새 설정 저장 시 기존 설정이 유실되지 않아야 한다 |

### 원본과 DB

- 신규 `original_path`는 `originals/YYYY/MM/yyyyMMdd_HHmmss_<SHA-256 앞 8자리>.<확장자>`이다.
  물리 절대경로가 아니라 `storageRoot` 기준 상대경로로 DB에 저장된다.
- 기존 자산의 상대경로는 이미 저장된 값을 사용한다. 새 이름 정책으로 기존 경로를 재계산하지 않는다.
- 원본 바이트는 변환하지 않는다. SHA-256으로 중복을 판단하고 자산/작업 등록은 같은 DB 트랜잭션에서 수행한다.
- 활성 자산의 중복 업로드는 기존 ID를 반환한다. 일반 multipart 신규 저장은 HTTP 201, 중복은 409이다.
  키즈노트 전용 자산의 일반 자산 승격과 삭제 자산 재업로드는 생성 성공으로 처리하는 별도 분기다.
- 클라이언트 해시가 수신 파일 해시와 다르면 400, 미지원 확장자는 415로 응답하는 코드 경로가 있다.
  이 HTTP 계약은 아래 기존 테스트에서 직접 검증하지 않는다.
- 일반 사진은 THUMBNAIL/FACE/CAPTION을 등록한다. 동영상 또는 `skipMlJobs` 입력은 THUMBNAIL만 등록한다.
- 같은 FileStore의 MOVE는 `ATOMIC_MOVE`를 시도한다. 원자적 이동 미지원 또는 다른 FileStore에서는
  임시 대상에 복사한 뒤 최종 파일을 공개한다. MOVE의 복사 경로는 DB 저장 성공 후 입력을 삭제한다.
- DB 저장 실패 시 실제로 이동한 입력은 되돌린다. 복사 경로의 입력은 DB 성공 전 삭제하지 않는다.
  다만 현재 구현은 DB 실패 시 이미 복사한 대상 파일을 제거하지 않아 DB 참조 없는 원본이 남을 수 있다.
  이를 정상 보장으로 고정하지 않고 후속 저장소 작업의 실패 처리 검증 항목으로 남긴다.
- 기존 대상 파일이 있으면 전체 SHA-256을 비교한다. 신규 복사 대상의 추가 해시 재검증은 현재 구현에 없다.
  원자적 파일 쓰기 검증과 복사 후 체크섬 검증을 같은 것으로 취급하지 않는다.

### 읽기와 휴지통

- 원본 HTTP API는 `purged_at`이 없고 파일이 존재하면 휴지통 자산의 원본도 제공할 수 있다.
  동영상 Range 처리는 Spring의 Resource 응답에 의존한다. 실제 206/Content-Range 호환성은 별도 검증 대상이다.
- 휴지통 이동은 `deleted_at`만 설정한다. 복원은 영구 삭제되지 않았고 원본이 존재하는지 확인하며,
  원본이 없으면 409가 되는 코드 경로가 있다.
- 영구 삭제는 원본과 400/1600 썸네일을 제거한 뒤 `purged_at`을 기록하고 얼굴·캡션·작업을 제거한다.
  자산 ID와 해시는 tombstone으로 남는다. 재업로드는 기존 ID/경로를 재사용해 복원한다.
- 만료된 휴지통은 시작 1시간 후, 이후 6시간 간격으로 정리한다. 실제 대기 시간을 포함한 스케줄 실행은
  현재 회귀 테스트의 검증 범위에 포함되지 않는다.
- ingest/restore/purge/thumbnail은 SHA-256 기반 `AssetLocks`를 공유한다. 이 잠금은 단일 JVM 범위이다.

### 썸네일과 메타데이터

- 썸네일은 JPEG 두 종류(400/1600)이며 현재 경로는 `thumbs/ab/cd/<hash>_<size>.jpg` 형태이다.
  기존 평면 경로의 이동과 썸네일 루트 변경은 `ThumbnailStorage`가 담당한다.
- JPEG/PNG/GIF/BMP는 Thumbnailator, 다른 포맷과 동영상은 ffmpeg 경로를 사용한다.
  Thumbnailator는 가로·세로 상한과 JPEG 품질 0.85를 지정하고 ffmpeg는 별도 scale 필터를 사용한다.
  실제 해상도·orientation 결과를 두 경로가 동일하다고 가정하지 않는다.
- 이미 존재하는 썸네일은 건너뛰고 새 출력은 임시 파일에서 완성 후 공개한다.
  현재 코드에는 원본 EXIF/GPS를 썸네일에 명시적으로 복원하는 처리가 없다.
- EXIF 추출은 입력 원본에서 저장 전에 실행하며 실패하면 빈 메타데이터를 반환한다.
  촬영 시각, 폭/높이, 카메라 제조사/모델, GPS 위도/경도를 DB에 저장한다. GPS `(0, 0)`은 위치 없음으로 취급한다.
- 날짜 선택은 **파일명 → EXIF → 파일 수정 시각 → 현재 시각** 순서이며 출처를 기록한다.
  키즈노트는 게시 날짜 등에 근거한 override를 사용한다.
- 촬영 시각은 `LocalDateTime` 형태로 저장되며 원본 timezone/offset, GPS 고도, orientation,
  전체 EXIF, MIME은 별도 DB 컬럼으로 보존하지 않는다. `duration_ms` 컬럼은 있지만 ingest 결과는 null이다.
- Google Photos용 파생 이미지의 메타데이터는 DB를 우선 활용하고 필요한 누락 정보를 원본에서 보충한다.
  기존 썸네일을 직접 수정하거나 기존 날짜 선택 정책을 게시 기능 때문에 바꾸지 않는다.

### 작업 큐와 설정

- DB 작업 상태는 PENDING/RUNNING/DONE/FAILED이다. claim은 원자적 UPDATE/RETURNING을 사용하고
  휴지통·영구 삭제 자산을 제외한다. 촬영 연월 기반 priority가 높은 작업부터 선택한다.
- fail은 시도 횟수를 증가시키고 3회에 도달하면 FAILED가 된다. 그 전에는 PENDING으로 돌아간다.
  release는 시도 횟수를 소비하지 않는다. 기존 큐 자체에는 지수 backoff가 없다.
- 시작 복구는 RUNNING/FAILED를 PENDING 및 시도 횟수 0으로 되돌리고 필요한 ML 작업을 보충한다.
  Google Photos 작업에도 이 정책을 그대로 적용할지는 게시 중복 방지 설계에서 별도로 결정한다.
- 썸네일 워커는 3초, 캡션 워커는 5초 간격으로 실행된다. 캡션은 1600 썸네일을 사용하며,
  외부 연결 불가 시 작업을 release하고 60초 backoff를 적용한다. FACE는 Python 워커가 HTTP API로 처리한다.
- `AppProperties`를 생성자 주입한다. 기본 원본/임시 경로는 `storageRoot/originals`, `storageRoot/tmp`이며,
  DB와 썸네일 경로는 별도 설정이 가능하다. 원본 저장소 변경과 함께 DB/썸네일/임시 경로를 옮기지 않는다.
- SQLite는 WAL, busy timeout 30초, pool 4를 사용한다. DB 변경은
  [DatabaseMigrations](../server/src/main/kotlin/com/homephoto/server/config/DatabaseMigrations.kt)의 기존 경로에 맞춘다.
- `SettingsService`는 고정 템플릿으로 설정 파일 전체를 다시 쓰므로 새 저장소/게시 설정을 추가할 때
  설정 화면 저장 후에도 값이 유지되는지 반드시 확인한다. 루트/DB 변경은 재시작 경계도 확인한다.
- 현재 MCP OAuth는 서버 접근 인증이다. 향후 Google Photos의 외부 Google OAuth와 별도로 취급하며,
  token/client secret은 저장소에 커밋하지 않는다.

## 3. 실행한 기존 테스트와 결과

2026-09-30 KST 서버 테스트 결과는 15:09경 생성되었다. 테스트 결과 XML을 집계한 수치는 다음과 같다.

| 테스트 묶음 | 실행 수 | 결과 |
|---|---:|---|
| ServerRegressionTest | 14 | 통과 |
| FileSafetyTest | 5 | 통과 |
| BackgroundCpuDiagnosticsTest | 1 | 통과 |
| PhotoMcpAccessTest | 2 | 통과 |
| PhotoMcpIntegrationTest | 15 | 통과 |
| PhotoOAuthIntegrationTest | 10 | 통과 |
| FaceVectorSearchTest | 3 | 통과 |
| PhotoSearchProcessTest | 5 | 통과 |
| **서버 합계** | **55** | **실패 0, 오류 0, 건너뜀 0** |
| Python: test_search_service / test_face_search | **8** | **통과** |

재실행 명령은 다음과 같다. 각 명령은 표기한 작업 디렉터리에서 실행한다.

`server/`:

```powershell
.\gradlew.bat test --offline --no-daemon --rerun-tasks --console=plain
```

구형 Python 검색 검증 이력(현재 검증은 [JVM 검색 안내](JVM-VECTOR-SEARCH.md) 참고):

```powershell
.\.venv-search\Scripts\python.exe -m unittest -v test_search_service test_face_search
```

- 서버: `BUILD SUCCESSFUL`, 강제 재실행으로 컴파일 및 테스트 완료.
  결과는 `server/build/test-results/test/TEST-*.xml`, HTML은 `server/build/reports/tests/test/index.html`에 생성된다.
- Python: 기존 `.venv-search` 사용, 8 tests / OK. 패키지 설치나 실제 모델 다운로드는 수행하지 않았다.
- Gradle Wrapper 자체 배포본이 없으면 `--offline`이어도 wrapper 다운로드가 필요할 수 있다.
  이번 제한된 실행 환경의 첫 시도는 wrapper 단계에서 소켓 권한 오류로 종료됐고,
  정상 사용자 환경에서 같은 명령을 재실행한 결과가 위 성공 기록이다.
- 테스트는 임시 DB/파일 및 테스트용 인증 정보를 사용한다. 운영 라이브러리, 배포 JAR,
  실제 UNC 공유, Google 계정, Android 기기를 대상으로 한 검증은 수행하지 않았다.
- 빌드 산출물은 기준 커밋에 포함하지 않는다. 위 요약은 해당 코드에서 실행한 결과를 보존하기 위한 기록이다.

## 4. 기존 자동 검증이 확인하는 범위

| 계약 | 현재 근거 | 직접 확인한 내용과 한계 |
|---|---|---|
| 중복 저장 | [ServerRegressionTest](../server/src/test/kotlin/com/homephoto/server/ServerRegressionTest.kt) | 동시 ingest 8건에서 자산 1개, 작업 1세트. multipart HTTP 중복 응답은 미검증 |
| MOVE 안전성 | ServerRegressionTest | 성공 시 입력 제거/바이트 보존, DB INSERT 실패 시 이동 입력 복구. 다른 FileStore/UNC 복사 분기는 미검증 |
| 휴지통 경합 | ServerRegressionTest | 오래된 목록의 purge 차단, 동시 restore/purge, 영구 삭제 후 동일 ID 재업로드 복원 |
| 큐 일관성 | ServerRegressionTest | 동시 claim 중복 없음, release 예산 보존, 3회 실패 종료, 삭제 자산 제외, 늦은 결과 거부, 완료 저장 rollback/재완료 거부 |
| DB 호환성 | ServerRegressionTest | 레거시 스키마 자산 보존, 반복 migration, 실패한 migration의 성공 기록 방지 |
| 목록 조회 | ServerRegressionTest | 같은 촬영 시각의 양방향 페이지 순서, 휴지통/키즈노트 가시성 |
| 썸네일 | ServerRegressionTest | 생성 PNG 입력으로 두 크기 출력의 디코드 가능 여부와 동시 생성 완료. 정확한 픽셀 크기·orientation·EXIF·ffmpeg 실행은 미검증 |
| 파일 공개·프로세스 | [FileSafetyTest](../server/src/test/kotlin/com/homephoto/server/FileSafetyTest.kt) | 실패/빈 출력의 최종 파일 공개 차단, 임시 파일 정리, timeout/출력 drain/종료 오류. subprocess는 Java fixture이며 실제 ffmpeg가 아님 |
| 검색·얼굴 인덱스 | 서버 검색 테스트와 `LocalVectorIndexTest`, `PhotoSearchProcessTest`, `FaceVectorSearchTest` | HTTP stub, 모의 프로세스, synthetic vector/모의 encoder를 통한 계약 확인. 실제 모델·외부 VLM·운영 인덱스 품질은 미검증 |
| MCP/OAuth/진단 | 해당 서버 테스트 묶음 | 접근·HTTP·인증·진단 계약. 전체 55건 통과가 원본 파일 HTTP/운영 기동/실제 워커 실행을 증명하지는 않음 |

## 5. 후속 단계의 검증 항목

아래 항목은 현재 미검증 또는 추가해야 할 계약이며, 이번 1단계의 검증 완료 결과에 포함하지 않는다.
기존 테스트가 확인한 계약도 어댑터 도입 후 같은 테스트 또는 동등한 동작 테스트로 유지한다.

| 대상 단계 | 확인할 시나리오 | 통과 기준 |
|---|---|---|
| StorageAdapter·로컬 구현 | 기존 설정/기존 DB/레거시 original_path로 시작 | 경로 값과 원본 바이트 보존, 새 저장소 설정이 없어도 기존 로컬 동작 유지 |
| 원본 API 전환 | 실제 multipart 신규/중복/해시 오류/미지원 확장자, 다운로드/없는 원본/휴지통/영구 삭제 | 기존 상태 코드와 DTO 유지, 다운로드 SHA-256 일치, 정해진 가시성 유지 |
| 동영상 읽기 | `Range: bytes=...`, 전체 다운로드, 웹/Android 재생 및 탐색 | 206/Content-Range/Content-Length와 요청 구간 바이트 일치, 기존 클라이언트 재생 가능 |
| 저장 실패·복사 | 다른 FileStore MOVE, 쓰기 실패/중단, 기존 대상 해시 불일치, 복사 후 DB 실패 | 저장 완료 전 입력 삭제 금지, 실패 파일을 정상 원본으로 공개하지 않음, DB 참조 없는 대상의 정리/복구 정책 명확화 |
| 메타데이터 | 파일명 날짜와 EXIF 충돌, GPS 유무/(0,0), offset 포함·누락, 읽기 실패, 사진/동영상 | 기존 촬영 날짜 우선순위 및 DB 저장 값 유지, 없는 정보를 임의로 만들어내지 않음 |
| 가져오기 | SCAN/COPY/MOVE, 중단 후 재개, 키즈노트 사진/동영상·일반 자산 승격 | 입력 보존/정리와 중복 정책 유지, 출처·날짜·ML 작업 등록 분기 유지 |
| 썸네일 원본 접근 | JPEG/PNG/HEIC/동영상, 회전 사진, 생성 도중 실패, 경로 이전 | 기존 썸네일 서빙 유지, 출력 디코드·화면 방향·실제 ffmpeg 성공 확인, 기존 썸네일 직접 수정 없음 |
| 설정·시작·복구 | 설정 화면 저장 후 재시작, 오래된 설정, 큐 RUNNING/FAILED 복구, 썸네일 루트 이동 | 새 설정과 기존 설정 모두 보존, 원본 루트와 로컬 DB/썸네일/임시 경로 분리 유지 |
| UNC 저장소 | 실제 서버 실행 계정으로 공유 접근, 다른 볼륨 업로드/읽기/삭제, 연결 중단·권한 거부 | 원본 무결성과 입력 보존, 오류 후 재시도/복구, DB/썸네일/임시 파일의 홈서버 로컬 유지 |
| Google Photos PoC | 실제 계정에 촬영 시각+GPS+파일명 / 시각만 / GPS만의 3~5장 게시 | Google Photos UI에서 날짜·위치·파일명·품질을 확인하고 지원되는 EXIF field set 확정. 지도/검색 결과도 관찰 기록 |
| 게시 큐·장애 분리 | 기본 비활성화, 인증 만료/API 실패, 재시작/재시도, media item 생성 후 응답 유실 | 원본 백업 성공과 독립된 게시 상태, 완료 항목 재게시 방지, 결과 불명 시 맹목적 재생성 방지 |

UNC 검증은 접근 가능한 실제 공유 경로와 서버 실행 계정이 준비된 시점에 진행한다.
Google Photos PoC는 전체 게시 큐 구현 전에 진행하고, 당시 공식 API 정책을 확인한다.
Google Photos가 embedded metadata를 어떻게 표시하는지는 로컬 EXIF 파싱 성공만으로 판정하지 않는다.
게시용 임시 JPEG는 기존 1600 썸네일을 바탕으로 만들되 픽셀 재인코딩/추가 resize 없이 메타데이터를 기록할 방법을 검증한다.
실제 파일 포맷 또는 도구 제약으로 재인코딩이 필요하면 품질 영향을 PoC에 기록한다.

## 6. 1단계 완료 기록

- [x] 원본 입력·경로·DB·읽기·삭제·썸네일·메타데이터·큐·설정 책임 위치 연결.
- [x] 기존 동작과 알려진 실패 처리 한계 기록.
- [x] 현재 서버 55건 및 Python 8건의 통과 결과 확보.
- [x] 자동 테스트의 실제 범위와 미검증 경계 구분.
- [x] StorageAdapter/UNC/Google Photos 후속 검증 기준 기록.

다음 단계는 위 기준을 유지하면서 최소 StorageAdapter 계약과 로컬 구현을 추가하는 작업이다.
이번 단계에는 운영 로직 변경, 외부 저장소 연결, Google OAuth/게시 구현을 포함하지 않는다.
