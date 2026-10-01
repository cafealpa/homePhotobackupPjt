# Google Photos 파생 이미지 게시

2026-09-30 실제 Google Photos 계정 연결과 운영 서버의 소량 게시를 진행했고, 10-01 완료 상태를 확인했다.
실제 UNC 공유 검증은 계속 보류 중이며, 외부 게시의 코드 기본값은 비활성이다.
코드/로컬 테스트, 실제 게시 성공, 개별 메타데이터의 Google Photos UI 인식은 구분해서 기록한다.

## 실제 계정 게시 확인 (2026-09-30~10-01)

- HomePhoto 전용 Google Cloud 프로젝트에 Photos Library API와 Desktop OAuth 클라이언트를 설정했다.
- 사용자가 Google 계정 접근 동의를 완료했고, appendonly scope와 refresh token이 있는 개인 토큰 파일을 발급했다.
- 인증 파일은 Git 제외 폴더에 두고 운영 설정에 파일 경로만 저장했다. 인증정보와 운영 DB는 커밋하지 않는다.
- 사용자가 시험 사진의 실제 게시를 승인한 뒤 직접 게시를 실행하고 추가 소량 사진도 확인했다.
- 운영 설정 화면과 SQLite에는 **COMPLETED 11건, CANCELLED 1건**이 일치했다. 대기·실패·인증 필요·결과 불명 작업은 없었다.
- 완료 11건은 모두 시도 1회이며 mediaItemId/productUrl/완료 시각과 `jpeg1600-exif-v1` snapshot이 저장돼 있었다. 마지막 오류는 없었고 완료 JPEG 임시 파일도 남지 않았다.
- 최종 설정은 **게시 사용 켜짐, 새 백업 자동 게시 꺼짐, 동영상 대표 JPEG 포함 꺼짐, 최대 시도 5회**다. 이번 확인에서 추가 게시나 설정 변경을 수행하지 않았다.
- 실제 서버의 관리 화면과 DB migration 2 적용을 확인했다. 서버 재시작 복구와 실제 UNC 접근은 이번 실계정 확인에 포함하지 않는다.

사용자가 실제 게시 동작을 확인했다. 다만 촬영일·GPS·지도·파일명·방향·체감 품질의 항목별 검증과
[5단계 합성 샘플 A~E](GOOGLE-PHOTOS-METADATA-POC.md)의 Google Photos 인식 검증은 완료로 간주하지 않는다.
첫 시험 사진의 API productUrl은 후속 확인 시 같은 로그인 계정에서 접근 불가 화면을 반환했다.
라이브러리에서 같은 이름의 Android 원본도 보였으므로 이를 파생 JPEG의 표시 검증 근거로 사용하지 않았다.
다른 완료 항목의 productUrl은 사진 화면을 열었으나 상세 메타데이터 검증은 사용자의 선택에 따라 여기서 종료했다.
첫 링크의 접근 불가 원인은 확정하지 않았다. 확인 링크가 열리지 않아도 완료 이력을 초기화하거나 자동 재게시하지 않는다.

최종 설정 증빙은 로컬 `server/build/verification/google-photos-published-settings.png`에 저장했다.
개인 사진·좌표·Google 항목 ID가 담긴 증빙이나 인증 파일은 Git에 포함하지 않는다.

## 중복 표시와 게시용 앨범 정리 (2026-10-01)

1600px 게시용 JPEG는 기존 원본과 다른 Google Photos 항목으로 생성된다.
현재 Library API는 앱이 생성한 항목만 읽을 수 있으므로, 휴대폰 등에서 먼저 올린 원본과 자동 비교해
게시를 건너뛰는 기능을 제공하지 않는다. [Google Library API 범위](https://developers.google.com/photos/library/reference/rest)를 참고한다.

홈서버가 게시한 이미지를 **HomePhoto · 1600px 게시용** 전용 앨범에 모아 정리할 수 있게 했다.
새 게시에는 `batchCreate.albumId`를 지정하고, 기존 완료 항목은 저장된 mediaItemId만 앨범에 연결한다.
앨범에 연결하는 작업은 사진을 새로 업로드하지 않으며 원본·썸네일·완료 이력을 수정하지 않는다.
앨범 분류만으로 기본 사진 목록의 중복 표시가 사라지는 것은 아니다.
삭제하려면 앨범에서 원하는 게시용 사진을 선택해 **휴지통으로 이동**한다.
**앨범에서 삭제**는 앨범의 연결만 제거하며 라이브러리 사진을 지우지 않는다.
홈서버는 Google Photos 사진을 자동 삭제하지 않는다.

실제 계정에서는 기존 게시 사진 **10장**을 전용 앨범에 연결했다.
첫 시험 사진인 홈서버 자산 **#2**는 Google의 연결 요청이 실패했으며 원인은 확정하지 않았다.
이 항목은 앞선 productUrl 확인에서도 접근할 수 없었다. 다시 게시하거나 완료 이력을 초기화하지 않았다.
정리 전후 운영 DB의 게시 기록 12건은 동일했고, 추가 사진 업로드나 Google Photos 삭제는 실행하지 않았다.
실제 앨범 제목과 사진 10장이 보이는 것을 브라우저에서 확인했다.
제목 증빙은 Git 제외 파일 `server/build/verification/google-photos-cleanup-album.png`에 보관한다.

설정 목록에서는 진행 중·실패·인증 확인·결과 확인 필요 작업을 바로 표시하고,
완료·취소 목록은 **완료·취소 이력** 안에 기본으로 접어 둔다. 펼침 상태는 목록 갱신 중 유지한다.
완료 기록은 같은 자산을 다시 게시하지 않도록 보관한다.
Google Photos에서 게시용 사진을 지워도 완료 기록을 지우거나 자동 재게시하지 않는다.
표시 범위는 기존과 같이 최근 변경 100건이며 상태별 건수는 전체 기록 기준이다.

[GooglePhotosPublicationAlbum](../server/src/main/kotlin/com/homephoto/server/publication/GooglePhotosPublicationAlbum.kt)은
계정 연결별 앨범 ID와 생성 상태를 토큰 파일 옆 `<토큰 파일명>.albums.json`에 저장한다.
예를 들어 `tokens.json`이면 `tokens.json.albums.json`과 동시 실행용 `.lock` 파일을 사용한다.
인증 파일을 이동할 때 앨범 상태 파일도 함께 옮긴다. 상태 파일이 사라지면 새 앨범이 만들어질 수 있다.
DB migration이나 추가 OAuth scope는 필요하지 않으며 기존 appendonly 권한을 사용한다.
앨범 생성 전 intent를 원자적으로 저장하고 파일 잠금으로 CLI와 서버의 동시 생성을 차단한다.
응답 유실·프로세스 중단으로 생성 여부가 불명확하면 앨범을 자동 반복 생성하거나 사진을 업로드하지 않는다.
설정 화면에서 앱이 생성한 기존 앨범 ID를 연결하거나, 미생성을 직접 확인한 뒤 다시 준비한다.
게시용 사진을 정리할 때는 전용 앨범 자체는 유지한다. 앨범을 삭제하면 저장된 앨범 ID로 새 게시가 실패할 수 있다.

**기존 게시 사진을 전용 앨범에 모으기**는 게시 사용이 꺼져 있어도 명시적으로 실행할 수 있다.
자동 게시와 상태 조회는 비활성 상태에서 인증 파일이나 Google 서버를 읽지 않는다.
실행 결과에는 연결 성공 수, 실패한 홈서버 자산 ID, 중단 오류 코드를 표시한다.
이미 삭제된 Google 항목이 섞여 batch가 거절되면 유효한 항목은 개별 연결한다.
다른 계정 연결의 완료 기록과 대기·결과 불명 기록은 정리 대상으로 사용하지 않는다.

서버를 시작하지 않고 기존 기록만 정리하려면 아래 명령을 사용할 수 있다.
[GooglePhotosOrganize](../server/src/main/kotlin/com/homephoto/server/publication/GooglePhotosOrganize.kt)는
기존 SQLite DB를 `mode=ro`로 열고 앨범 생성·기존 ID 연결만 수행한다.
일부 항목 실패·중단은 종료 코드 2로 보고하므로 Gradle은 실패로 표시할 수 있다. 출력 JSON의 결과를 확인한다.

```powershell
# server/에서 실행. 현재 라이브러리의 기존 DB와 개인 인증 파일을 지정한다.
$env:HOMEPHOTO_GOOGLE_PHOTOS_DATABASE = 'C:/homeProjects/family/homePhotobackupPjt/data/db/photos.db'
$env:HOMEPHOTO_GOOGLE_PHOTOS_CLIENT_JSON = 'C:/homeProjects/family/homePhotobackupPjt/server/google-photos-private/client.json'
$env:HOMEPHOTO_GOOGLE_PHOTOS_TOKENS_JSON = 'C:/homeProjects/family/homePhotobackupPjt/server/google-photos-private/tokens.json'
.\gradlew.bat googlePhotosOrganize --offline --no-daemon --console=plain
Remove-Item Env:HOMEPHOTO_GOOGLE_PHOTOS_DATABASE
Remove-Item Env:HOMEPHOTO_GOOGLE_PHOTOS_CLIENT_JSON
Remove-Item Env:HOMEPHOTO_GOOGLE_PHOTOS_TOKENS_JSON
```

서버 전체 테스트 **122건**과 이후 추가한 CLI 읽기 전용 DB 테스트 **1건**이 통과했다.
앨범 HTTP 계약, 계정별 재사용, 생성 불명 복구, 파일 잠금, 기존 ID 정리·부분 실패,
사진 재업로드와 큐 변경 없음, 인증/관리 헤더 및 잘못된 URL 거절을 확인했다.
`node --check`와 로컬 데모 브라우저에서 완료 이력 기본 접힘·펼침·갱신 후 상태 유지를 확인했다.
데모 증빙은 `server/build/verification/google-photos-history-collapsed.png`에 저장한다.
이번 작업은 소스와 실계정 앨범 정리까지 수행했으며 배포 JAR 생성·교체는 포함하지 않는다.
새 설정 화면과 향후 게시의 앨범 지정은 변경된 소스로 빌드한 서버를 실행해야 적용된다.

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
자동 게시·상태 조회는 기본 비활성 상태에서 인증 파일/Google 서버를 읽지 않는다.
사용자가 실행한 앨범 정리·복구는 비활성 상태에서도 기존 인증을 사용할 수 있다. 별도 JSON을 사용하고
만료 60초 전 refresh token으로 갱신하며, 동시에 여러 요청이 있어도 갱신을 직렬화한다.
새 토큰 저장은 원자적으로 수행하고 refresh token이 응답에 없으면 기존 값을 보존한다.
연결 ID가 다른 credential로 바뀌면 기존 업로드 단계를 계속하지 않는다.
인증정보와 Google 오류 응답 본문은 로그/게시 상태에 포함하지 않는다.

초기 인증은 서버 시작과 별개인 명시적 명령이다. Desktop OAuth JSON과 출력 경로가 준비됐을 때 실행한다.
초기 구현 시에는 Google 인증을 실행하지 않았으며, 위의 실계정 확인에서 이 명령으로 토큰을 발급했다.

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
백그라운드 재시도/영속 상태 연결은 아래 8단계에서 구현했다.

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

## 9단계 관리 화면과 소량 사용 절차

웹의 설정 → Google Photos 카드에서 게시 사용, Desktop OAuth JSON/개인 토큰 파일의 전체 경로,
새 백업 자동 게시, 영상 대표 JPEG 포함, 재시도 횟수(1~10)를 저장한다.
Google Photos 설정은 원자적 YAML 저장 성공 후 즉시 반영된다. 이전 클라이언트가 필드를 생략하면 기존 설정을 보존한다.
인증 파일 내용은 설정/API 응답에 넣지 않으며 경로만 저장한다. 인증 파일의 존재 표시는 실제 OAuth 연결 검증을 뜻하지 않는다.

`enabled=false` 상태에서도 사진 선택 → Google Photos 게시 준비, 최근 5장 게시 준비와 전체 미게시 사진 일괄 게시를 사용할 수 있다.
최근 준비는 아직 등록하지 않은 일반 백업 사진을 고른다. 이 작업은 원본이나 기존 썸네일을 이동/수정하지 않는다.
일괄 게시는 삭제되지 않은 일반 백업 중 게시 이력이 없는 자산 전체를 대기열에 등록한다.
동영상 대표 JPEG는 저장된 포함 설정을 따르며, 키즈노트 전용·휴지통·영구 삭제 자산은 제외한다.
완료·진행·실패·인증 필요·결과 불명·취소를 포함한 기존 이력은 유지한다. 실패 재시도와 취소 재등록은 기존 개별 기능을 사용한다.
사진 ID 전체를 브라우저에 가져오지 않고 DB의 INSERT SELECT로 일괄 등록하므로 선택 게시의 1000장 제한을 적용하지 않는다.
다시 눌러도 신규 미등록 항목만 추가하며, 등록된 건수는 화면에 표시한다. 실제 게시 순서와 썸네일 준비 조건은 기존 워커가 관리한다.
활성화를 켜고 저장하면 기존 대기 작업도 처리하므로, 사용 전 전체 대기 건수를 확인한다.
새 백업 자동 게시의 기본값은 꺼짐이며, 기존 라이브러리 전체를 자동 등록하는 기능은 없다.

관리 목록은 최근 변경 100건과 전체 상태별 건수를 표시한다. 시도 횟수·다음 시도 시각·오류 코드,
작성된 metadata snapshot·Google 항목 ID·완료 시각·Google 확인 링크를 볼 수 있다.
완료·취소 이력은 기본으로 접혀 있고 진행·실패·결과 확인이 필요한 항목은 바로 표시한다.
설정 화면에 머무는 동안 5초 간격으로 갱신하며, 결과 확인 입력창을 펼쳐 둔 동안에는 입력을 유지한다.
미리보기는 기존 1600 썸네일로 별도 JPEG를 만들고 `no-store` 응답 후 임시 파일을 정리한다.
Google에 요청하거나 큐에 등록하지 않는다. 아직 썸네일이 준비되지 않은 사진은 준비 필요 메시지를 반환한다.

실패/인증 필요는 수동 재시도할 수 있고, 생성 전 단계는 게시 취소할 수 있다.
취소한 작업은 사진 선택으로 다시 준비할 수 있다. 이미 완료한 이력은 재등록해도 새 게시가 생기지 않는다.
UNKNOWN에는 일반 재시도 버튼을 두지 않는다. 실제 Google 항목 ID를 연결하거나,
Google Photos에서 미생성을 확인했다는 체크 후에만 다시 대기로 바꾼다. 연결한 URL은 `https://photos.google.com/`만 허용한다.
Google 항목 ID를 새로 조회/검증하는 외부 요청은 이 관리 기능에 포함하지 않는다.

[GooglePhotosController](../server/src/main/kotlin/com/homephoto/server/api/GooglePhotosController.kt)는
기존 API 키/쿠키 인증을 사용하고, POST에는 `X-HomePhoto-Action: google-photos` 헤더가 필요하다.
관리 응답에는 OAuth 토큰, byte uploadToken, credential 연결 ID와 임시 파일 경로를 포함하지 않는다.

2026-10-01 일괄 게시 검증: 게시 큐·저장소 HTTP 회귀 테스트 **31건 통과**, 실패·오류·건너뜀 0건.
빈 라이브러리, 1005장 일괄 등록과 반복 실행, 기존 모든 상태 보존, 삭제/키즈노트 제외,
영상 포함 설정과 비활성 상태에서의 준비, 인증/작업 헤더 조건을 확인했다.
임시 사진 9장인 데모의 브라우저에서는 신규 6장 등록과 두 번째 실행의 추가 등록 없음,
버튼 표시·재활성화와 변경된 JS 로드를 확인했다. 운영 DB 일괄 등록과 실제 Google 업로드는 실행하지 않았다.

| API | 용도 |
|---|---|
| GET /api/v1/admin/google-photos | 설정 상태·전체 상태별 수·최근 게시 이력 |
| POST .../enqueue `{assetIds:[...]}` | 선택한 자산 준비 (1~1000개) |
| POST .../recent `{limit:5}` | 미등록 일반 백업 소량 준비 (1~100개) |
| POST .../all | 미등록 일반 백업 전체 준비, `{enqueued:등록건수}` 반환 |
| GET .../{id}/preview | 업로드 없는 JPEG 확인 |
| POST .../{id}/retry / cancel | 재시도 또는 생성 전 취소 |
| POST .../{id}/resolve | 기존 mediaItemId 연결 또는 confirmedNotCreated 명시 |
| POST .../album/organize | 현재 연결의 기존 완료 항목을 게시용 앨범에 모으기 |
| POST .../album/resolve | 생성 불명인 기존 앨범 ID 연결 또는 confirmedNotCreated 명시 |

새 환경에서 실제 사용을 시작할 때는 다음 순서로 진행한다. 현재 환경의 실행 결과와 남은 검증 범위는 문서 첫 부분에 기록했다.

1. 게시/자동 게시를 끈 상태에서 로컬 JPEG 미리보기를 확인한다.
2. [5단계 PoC](GOOGLE-PHOTOS-METADATA-POC.md)의 3~5장으로 실제 계정 날짜·위치·지도·파일명·방향·품질을 확인한다.
3. Desktop OAuth 도구로 개인 토큰을 발급하고 인증 파일 경로를 저장한다. 자동 게시와 영상 포함은 계속 꺼 둔다.
4. 최근 5장을 준비하고 전체 대기 건수를 확인한다. 이전에 준비한 항목이 더 있으면 생성 전 취소하여 시험 대상을 제한한다.
5. 게시 사용을 켜고 저장한다. 상태/Google 링크를 확인하고 인증/실패/결과 불명은 위의 수동 절차로 처리한다.
6. 소량 결과와 재시작 복구를 실제 환경에서 확인한 뒤 필요한 범위로 선택 게시 또는 새 백업 자동 게시를 넓힌다.

중지는 게시 사용을 끄고 저장한다. 이미 외부 항목 생성 요청을 시작한 건은 응답과 이력을 남길 수 있다.
운영 중단 시 게시 테이블이나 인증 파일을 지워 상태를 초기화하지 않는다. 기존 백업/썸네일 기능과 완료 이력을 유지한다.
원본 저장소 전환은 [UNC 검증](STORAGE-ACCESS.md)의 실제 서버 계정·경로 확인 후 별도로 진행한다.

### 로컬 관리 화면 검증

```powershell
# server/에서 실행. 임시 사진/DB만 사용하며 기본 게시/자동 게시가 꺼져 있다.
.\gradlew.bat googlePhotosAdminDemo --offline --no-daemon --console=plain
# http://localhost:18082 에서 코드에 명시된 테스트 키로 접속. 종료는 Ctrl+C.
```

[GooglePhotosAdminDemo](../server/src/test/kotlin/com/homephoto/server/publication/GooglePhotosAdminDemo.kt)는
생성 사진 9장, 별도 원본/로컬 폴더, 새 SQLite DB와 실제 게시 DI/스케줄러를 사용한다.
운영 외부 설정을 로드하지 않고 인증 파일 경로를 비워 둔다. 게시 비활성 상태에서 대기 큐를 확인한다.
서버 정보/벡터 검색/일괄 임포트 공통 상태 조회는 검증용 응답만 제공하고 실행 컨트롤러는 포함하지 않는다.
JVM 정상 종료 시 이 데모가 만든 임시 디렉터리만 정리한다. 강제 종료 시 임시 폴더가 남을 수 있다.
데모는 상주 서버 태스크라 확인 후 프로세스를 종료하면 Gradle이 비정상 종료로 표시할 수 있다.
이번 검증에서는 서버 확인 후 종료했고, 생성한 임시 디렉터리 두 곳도 정리했다.

2026-09-30 브라우저에서 최근 5장 준비, 선택 2장 중 신규 1장/완료 중복 1장 구분,
1600 JPEG 미리보기, 실패 재시도, 게시 취소, UNKNOWN의 미생성 체크 조건/기존 항목 연결,
설정 저장 및 새로고침 후 재시도 값 유지, 게시 비활성의 큐 대기를 확인했다.
버튼 스타일/로드된 캐시 버전도 확인했다. Google OAuth/실계정 업로드 및 운영 서버 UI는 검증하지 않았다.
검증 화면은 로컬 `server/build/verification/google-photos-admin.jpg`에 저장하며 Git에는 포함하지 않는다.

2026-09-30 17:36 KST 서버 테스트 **116건 통과**, 실패·오류·건너뜀 0건.
추가된 설정 테스트 2건과 실제 HTTP 테스트 3건은 즉시 적용/구버전 보존/실패 시 불변,
인증/POST 헤더/중복 준비/취소, 미리보기 EXIF/원본 보존/임시 파일 정리,
UNKNOWN 확인/Google URL 검증/완료 이력 보존을 확인했다.
기존 CPU 진단 테스트는 다른 Spring 테스트의 WARN 로그 설정에 영향을 받지 않도록
테스트 중 INFO 수준을 설정하고 종료 후 복원하게 했다. 운영 CPU 진단 구현은 변경하지 않았다.
`node --check`, `git diff --check`도 통과했다. 이 시점에는 실제 UNC/Google 계정 검증, 운영 migration/배포를 보류했다.
이후 같은 날 수행한 실제 계정 게시와 운영 migration 확인 결과는 문서 첫 부분에 별도로 기록했다.
