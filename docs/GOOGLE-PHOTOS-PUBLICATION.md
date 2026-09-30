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
