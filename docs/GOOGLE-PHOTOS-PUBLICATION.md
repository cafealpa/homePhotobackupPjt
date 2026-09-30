# Google Photos 파생 이미지 게시

실제 UNC 공유와 Google Photos 계정 검증은 사용자 요청(2026-09-30)으로 보류했다.
9단계까지 코드를 이어가되 외부 게시의 기본값은 비활성이다.
실제 계정의 날짜/GPS/지도/파일명/품질 인식은 [5단계 PoC](GOOGLE-PHOTOS-METADATA-POC.md)에 기록할 미확인 사항이다.
이 문서의 코드/로컬 테스트 완료를 실제 Google Photos 동작 확인과 구분한다.

## 6단계 Export Rendition

[GooglePhotosExport](../server/src/main/kotlin/com/homephoto/server/publication/GooglePhotosExport.kt)는
기존 1600 JPEG 썸네일을 읽고 로컬 `storage-root/tmp/google-photos/`에 별도 파일을 만든다.
원본과 기존 썸네일에는 쓰지 않는다. 성공한 출력의 SHA-256과 메타데이터 snapshot을 반환한다.
생성 도중 실패하면 이번 임시 파일과 원자적 쓰기 파일을 정리한다.
Apache Commons Imaging `1.0.0-alpha6`를 JVM 의존성으로 사용해 JPEG EXIF 영역만 교체한다.
resize/픽셀 재인코딩을 추가하지 않는다. 작성기는 PoC와 동일하다.

[PublicationMetadataProvider](../server/src/main/kotlin/com/homephoto/server/publication/PublicationMetadataProvider.kt)의 규칙:

- 원본명/촬영 시각/위경도는 기존 DB 값을 우선한다. 기존 날짜 선택 정책은 바꾸지 않는다.
- DB에 날짜 또는 좌표가 없으면 원본 EXIF에서 보충한다.
- DB가 보존하지 않는 offset/고도를 위해 원본을 한 번 읽는다. 이를 위한 대규모 DB migration은 하지 않는다.
- 원본 시각이 선택한 시각과 같을 때만 원본 offset을 기록한다. 모르는 시간대를 서버 기본값으로 추정하지 않는다.
- 좌표가 원본 좌표와 같을 때만 원본 고도를 사용한다. 알려지지 않은 GPS 시각/ModifyDate는 만들지 않는다.
- 원본 접근/추출 실패 시 알려진 DB 값으로 준비하고 보충 실패 경고를 snapshot에 남긴다.
- Orientation은 보정된 썸네일 기준 1이다. 기존 썸네일 자체의 방향 문제는 실제 사진/ffmpeg 검증으로 별도 확인한다.

EXIF 최소 세트는 아직 실제 계정 인식 검증 전의 임시 규칙이다.
원본 전체 EXIF/기기별 MakerNote를 복사하지 않는다.
썸네일/메타데이터 version은 `jpeg1600-exif-v1`로 구분한다.

2026-09-30 16:43 KST 서버 테스트 **94건 통과**, 실패·오류·건너뜀 0건.
추가한 Export 테스트 3건은 DB 우선값/원본 보충, 원본 장애 시 알려진 값 사용,
로컬 임시 출력·입력 보존·깨진 JPEG 실패 정리를 검증한다.
기존 PoC의 별도 리더 재추출과 JPEG 압축 바이트 비교도 같은 작성기에 대해 통과했다.
실제 Google 업로드는 실행하지 않았다.

## 7단계 Publisher와 인증

[GooglePhotosPublisher](../server/src/main/kotlin/com/homephoto/server/publication/GooglePhotosPublisher.kt)는
byte 업로드와 media item 생성을 분리한 최소 계약이다. Google Library 구현은 JPEG MIME으로 bytes를 보내고
`simpleMediaItem.fileName`에 원본 이름을 지정한다. description/creationTime/GPS 직접 설정 필드는 보내지 않는다.
원본 확장자를 유지한 이름의 실제 허용/표시는 보류된 PoC 검증 항목이다.

[GooglePhotosTokenProvider](../server/src/main/kotlin/com/homephoto/server/publication/GooglePhotosTokenProvider.kt)는
기본 비활성 상태에서 인증 파일/Google 서버를 읽지 않는다. 활성화 후 별도 JSON을 사용하고
만료 60초 전 refresh token으로 갱신하며, 동시에 여러 요청이 있어도 갱신을 직렬화한다.
새 토큰 저장은 원자적으로 수행하고 refresh token이 응답에 없으면 기존 값을 보존한다.
연결 ID가 다른 credential로 바뀌면 기존 업로드 단계를 계속하지 않는다.
인증정보와 Google 오류 응답 본문은 로그/게시 상태에 포함하지 않는다.

초기 인증은 서버 시작과 별개인 명시적 명령이다. Desktop OAuth JSON과 출력 경로가 준비됐을 때 실행한다.
현재 이 명령의 Google 인증은 실행하지 않았다.

```powershell
# server/에서 실행. 두 파일은 Git 제외 경로/개인 접근 권한의 폴더에 둔다.
$env:HOMEPHOTO_GOOGLE_PHOTOS_CLIENT_JSON = 'C:/homeProjects/family/homePhotobackupPjt/server/google-photos-private/client.json'
$env:HOMEPHOTO_GOOGLE_PHOTOS_TOKENS_JSON = 'C:/homeProjects/family/homePhotobackupPjt/server/google-photos-private/tokens.json'
.\gradlew.bat googlePhotosAuthorize --offline --no-daemon --console=plain
Remove-Item Env:HOMEPHOTO_GOOGLE_PHOTOS_CLIENT_JSON
Remove-Item Env:HOMEPHOTO_GOOGLE_PHOTOS_TOKENS_JSON
```

[GooglePhotosDesktopOAuth](../server/src/main/kotlin/com/homephoto/server/publication/GooglePhotosDesktopOAuth.kt)는
loopback/PKCE/state를 사용하며 appendonly scope와 offline 동의를 요청한다.
동의된 파일에는 connectionId/accessToken/refreshToken/expiresAt/scope만 기록한다.
초기 인증 도구는 기존 파일을 덮어쓰지 않는다. 재인증은 새 파일로 받아 연결을 확인한 후 경로를 전환한다.
토큰 파일은 기존 개인 설정 관리 방식에 맞춘 로컬 평문 JSON이므로 Windows 사용자 접근 권한으로 관리한다.
이미 완료/결과 불명인 게시를 다른 계정에 자동 이전하는 기능은 없다.

```yaml
homephoto:
  google-photos:
    enabled: false
    client-file: 'C:/homeProjects/family/homePhotobackupPjt/server/google-photos-private/client.json'
    token-file: 'C:/homeProjects/family/homePhotobackupPjt/server/google-photos-private/tokens.json'
    auto-publish-new: false
    include-videos: false
    max-attempts: 5
```

Publisher는 HTTP 429의 Retry-After와 최소 30초 지연을 전달한다.
byte 단계의 transport/5xx는 재시도 가능하며 생성 단계의 transport/5xx/잘못된 성공 응답은 결과 불명이다.
401/인증 해지는 인증 확인 상태로 전달하고, 명확한 입력/권한 오류는 자동 반복하지 않는다.
백그라운드 재시도/영속 상태 연결은 8단계에서 구현한다.

2026-09-30 16:52 KST 서버 테스트 **99건 통과**, 실패·오류·건너뜀 0건.
추가한 Publisher/Token 테스트 5건은 byte/파일명 계약, 생성 결과 불명과 rate limit 구분,
동시 token 갱신 1회와 저장 보존, 비활성/연결 변경 차단, invalid_grant 재인증과 파일 보존을 확인했다.
실제 OAuth 동의와 Google 서버 업로드는 미실행이다.

## 8단계 영속 큐와 복구

Migration 2는 `google_photos_publications` 테이블 하나를 추가한다. 기존 assets.original_path와 Jobs는 변경하지 않는다.
자산 ID를 PK로 사용하며 삭제/재업로드 이후에도 완료/결과 불명 이력을 남긴다.
썸네일 version·메타데이터 snapshot·JPEG SHA-256·단계·재시도 시각·연결 ID와 Google 결과 ID를 기록한다.
byte uploadToken은 단계 복구용으로 로컬 DB에만 저장하고 API/로그에 노출하지 않는다. 인증 토큰은 별도 개인 파일에 유지한다.

[GooglePhotosPublicationQueue](../server/src/main/kotlin/com/homephoto/server/publication/GooglePhotosPublicationQueue.kt)의 주요 상태:

| 상태 | 동작/복구 |
|---|---|
| PENDING | 예정 시각이 지나고 기존 THUMBNAIL 작업이 DONE이면 처리. 삭제 자산은 제외 |
| PREPARING_METADATA / UPLOADING / READY_TO_CREATE | 재시작 시 PENDING으로 복구. 영속 snapshot/유효한 uploadToken 재사용 |
| CREATING_MEDIA_ITEM | 네트워크 호출 전에 저장. 중단/응답 불명은 UNKNOWN으로 분리 |
| COMPLETED | mediaItemId 저장. 재등록/메타데이터 수정/재시작으로 자동 재게시하지 않음 |
| FAILED | 명확한 실패/재시도 소진. 사용자가 선택한 항목만 다시 준비 가능 |
| AUTH_REQUIRED | 인증 확인 후 수동 재시도. 새 연결의 경우 기존 uploadToken을 버리고 재바인딩 |
| UNKNOWN | 생성됐을 수 있어 자동 재시도 금지. 기존 항목 ID 연결 또는 미생성 명시 확인 필요 |
| CANCELLED | 생성 전 게시 준비 취소. 원본 보존/삭제는 기존 홈서버 동작을 따름 |

SQLite UPDATE RETURNING과 lease를 사용해 자산 중복 선점 및 같은 서버의 동시 생성 호출을 차단한다.
활성 게시 1건을 소화하는 [전용 워커](../server/src/main/kotlin/com/homephoto/server/worker/GooglePhotosWorker.kt)는
기존 썸네일/캡션 스레드와 분리되어 있다. Google 요청에는 연결/응답 timeout이 있다.
retry는 30초부터 지수 증가(기본 최대 1시간)하며 더 긴 Retry-After는 존중한다. 기본 5회 후 FAILED이다.
기존 StartupJobRecovery는 게시 FAILED/AUTH_REQUIRED/UNKNOWN/COMPLETED를 리셋하지 않는다.

유효한 byte token은 재업로드 없이 이어가고, 만료되면 동일한 보존 JPEG bytes를 다시 올린다.
준비 JPEG가 바뀌면 실패 처리한다. 결과가 불명인 상태에서 임의로 새 JPEG/metadata를 생성해 반복하지 않는다.
완료 파일은 정리하며, 실패/인증 확인/결과 불명 snapshot은 재시도/조사를 위해 로컬에 남긴다.
시작 시 DB가 참조하지 않는 생성 파일만 전용 임시 폴더에서 정리한다.

휴지통/영구 삭제는 생성 전의 게시 준비를 취소하고 게시 파일을 정리한다.
외부 생성 요청을 이미 시작한 경우에는 응답 ID를 이력에 남긴다. 이미 Google에 게시된 항목을 홈서버 삭제와 함께 지우지 않는다.
Google 항목 정리는 Google Photos UI에서 한다. 완료 이력 때문에 같은 자산을 복원해도 다시 게시하지 않는다.
외부 요청과 삭제가 겹칠 때 이미 시작한 요청을 취소/회수하는 보장은 없으며 원본 삭제를 Google 응답 대기에 묶지 않는다.

자동 등록은 enabled + auto-publish-new가 모두 켜진 경우 새 썸네일 완료 후에만 수행한다.
기본은 사진이며 include-videos를 명시하면 영상 대표 JPEG도 대상에 넣는다. 원본 영상을 Google에 업로드하지 않는다.
자동 등록/최근 소량 선택은 일반 자산(source가 없음)에 적용하며, 명시적 자산 선택은 사용자가 고른 대상을 사용한다.
자동 등록 실패는 썸네일/원본 성공을 바꾸지 않는다. 누락 항목은 소량 준비 기능으로 다시 선택할 수 있다.
단일 홈서버 JVM/단일 계정 운영을 전제로 한다. 같은 DB를 여러 서버 프로세스가 공유하는 게시 운영은 범위에 포함하지 않는다.

2026-09-30 17:09 KST 서버 테스트 **111건 통과**, 실패·오류·건너뜀 0건.
신규 게시 큐 테스트 12건은 자산/썸네일 조건, 동시 선점, 완료 멱등성과 파일 정리, backoff/소진,
UNKNOWN 해결, 인증 갱신 연결, 단계별 복구, 삭제/취소 경합, Google 성공 후 DB 실패,
게시 중지 후 token 재사용을 실제 임시 SQLite와 가짜 Publisher로 검증했다.
기존 migration 회귀 테스트는 새 version 2까지 총 두 이력이 남는 기대값으로 갱신했다.
운영 DB migration과 실제 게시/재시작은 수행하지 않았다.
