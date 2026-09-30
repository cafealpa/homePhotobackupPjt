# Google Photos 메타데이터 PoC — 5단계 준비

## 현재 상태

4단계 실제 UNC 검증은 사용자 요청(2026-09-30)에 따라 뒤로 미뤘다.
원본 접근·설정 분리는 [3단계](STORAGE-ACCESS.md)에서 완료했다.
이 문서의 5단계 도구는 운영 DB·설정·작업 큐를 로드하지 않는다.
이 5단계 도구의 합성 샘플 A~E는 로컬 준비까지 완료했으며, 이 샘플들의 실제 업로드/UI 검증은 미실행이다.
이후 2026-09-30 실제 계정 검증을 재개해 운영 게시 큐에서 소량 게시를 완료했고 사용자가 동작을 확인했다.
10-01 확인한 운영 설정·DB의 완료 11건과 확인 범위는 [실제 계정 게시 기록](GOOGLE-PHOTOS-PUBLICATION.md#실제-계정-게시-확인-2026-09-3010-01)에 남겼다.
촬영일/GPS/지도/파일명/방향/품질의 항목별 인식은 아직 확정하지 않았다. 메타데이터 필드와 정책은 이 결과를 확보한 뒤 확정한다.

## 도구와 샘플

[GooglePhotosMetadataPoc](../server/src/test/kotlin/com/homephoto/server/publication/GooglePhotosMetadataPoc.kt)는
기존 썸네일과 같은 1600 JPEG/품질 0.85의 합성 이미지를 만들고, EXIF만 주입한 A~E 5장을 생성한다.
실제 1600 JPEG 썸네일을 지정하면 해당 bytes를 복사해 사용한다. 입력 파일에는 쓰지 않는다.
5단계 도입 당시 Apache Commons Imaging `1.0.0-alpha6`는 테스트 클래스패스에만 추가했다.
6단계부터 [별도 Export Rendition](GOOGLE-PHOTOS-PUBLICATION.md)에 같은 작성기를 사용한다.
기존 원본 저장·썸네일 생성 경로에는 연결하지 않았다.

| 샘플 | 촬영 시각 | 시간대 | GPS | API fileName | 확인 목적 |
|---|---|---|---|---|---|
| A | 2018-04-05 12:34:56 | +09:00 | 서울, 고도 38.5m | IMG_20180405_123456.jpg | 날짜·GPS·고도 |
| B | 2020-06-07 08:09:10 | 없음 | 없음 | 촬영시각만_20200607.jpg | 시간대 없는 날짜, 한글 이름 |
| C | 없음 | 없음 | 제주 | GPS_ONLY.jpg | 날짜 없는 GPS 인식 |
| D | 2021-01-01 00:15:00 | +09:00 | 없음 | MIDNIGHT_20210101.jpg | 연도 경계, UTC는 2020-12-31 15:15 |
| E | 2019-02-03 04:05:06 | -03:00 | 남·서반구, 고도 -10m | IMG_20190203_040506.HEIC | 좌표 부호와 JPEG bytes/원본 확장자 이름 |

표의 데이터는 공개 장소에 임의의 시각·고도를 조합한 검증값이며 실제 촬영 기록이 아니다.
E의 이름은 원본 이름 보존 요청을 검증하기 위한 의도적인 조합이다. 허용/표시 결과를 확인한 뒤 확장자 정책을 결정한다.
DateTimeOriginal/DateTimeDigitized와 알려진 OffsetTimeOriginal/OffsetTimeDigitized를 작성한다.
GPS는 위경도와 N/S/E/W Ref, 알려진 고도와 고도 Ref를 작성한다. Orientation은 보정된 썸네일 기준 1이다.
ModifyDate와 GPS 시각은 이번 최소 필드 세트에서 작성하지 않는다. 필요성은 실제 인식 결과로 판단한다.
원본 전체 EXIF를 복사하지 않는다.

## 로컬 준비

```powershell
# server/에서 실행. 첫 실행은 Maven 의존성 다운로드가 필요할 수 있다.
.\gradlew.bat googlePhotosMetadataPoc --offline --no-daemon --console=plain
```

기본 출력은 Git 제외 경로인 `server/build/google-photos-poc/run-.../`이다.
`source-thumbnail.jpg`, `A.jpg`~`E.jpg`, 기대값/체크섬이 있는 `manifest.json`, 실제 관찰용 `UI-RESULTS.md`가 생성된다.
기존 결과가 있는 폴더를 다시 준비하면 거부한다. 이 명령은 Google에 접근하지 않는다.

실제 썸네일의 화질을 확인할 때는 기존 1600 JPEG 경로를 지정하고 새 세트를 준비한다.
이 변수의 파일을 복사한 다섯 이미지가 이후 업로드 대상이므로 폴더의 내용을 먼저 확인한다.

```powershell
$env:HOMEPHOTO_GOOGLE_PHOTOS_POC_THUMBNAIL = 'C:/homePhotoData/thumbs/기존1600썸네일.jpg'
.\gradlew.bat googlePhotosMetadataPoc --offline --no-daemon --console=plain
Remove-Item Env:HOMEPHOTO_GOOGLE_PHOTOS_POC_THUMBNAIL
```

합성 이미지로는 메타데이터/방향을 검증할 수 있지만 실제 사진의 체감 품질·내용 검색은 확정할 수 없다.

## 실제 계정 연결과 소량 업로드

Google Cloud 프로젝트에서 Photos Library API를 활성화하고 OAuth 동의 화면 및 테스트 사용자를 설정한다.
OAuth 클라이언트 종류는 **Desktop 앱**으로 만들고 JSON을 로컬에 내려받는다.
이 PoC는 계정 선택과 동의를 일반 브라우저에서 수행하는 loopback redirect + PKCE(S256)/state 흐름을 사용한다.
홈서버의 기존 MCP OAuth는 이 Google 인증과 별개다.

JSON은 `server/google-photos-private/client.json` 같은 Git 제외 경로에 둔다.
JSON 내용·access token·refresh token을 채팅이나 커밋에 넣지 않는다.
도구는 `photoslibrary.appendonly`만 요청한다. 업로드 응답의 creationTime/파일명/ID/productUrl을 기록하므로
이번 PoC에는 appcreateddata 조회 scope가 필요하지 않다.
서비스 계정은 Google Photos 인증에 사용할 수 없다.

```powershell
# server/에서 실행. pocDir은 prepare가 출력한 폴더로 바꾼다.
$env:HOMEPHOTO_GOOGLE_PHOTOS_CLIENT_JSON = 'C:/homeProjects/family/homePhotobackupPjt/server/google-photos-private/client.json'
.\gradlew.bat googlePhotosMetadataPoc -PpocMode=upload '-PpocDir=build/google-photos-poc/run-실제폴더명' --offline --no-daemon --console=plain
Remove-Item Env:HOMEPHOTO_GOOGLE_PHOTOS_CLIENT_JSON
```

콘솔에 표시된 주소를 일반 브라우저에서 열고 검증 계정으로 동의한다. 동의 대기는 최대 5분이다.
OAuth token은 해당 실행의 메모리에만 사용하고 저장하지 않는다. refresh token도 저장하지 않는다.
[GooglePhotosPocUpload](../server/src/test/kotlin/com/homephoto/server/publication/GooglePhotosPocUpload.kt)는
JPEG MIME의 raw byte 업로드 후 `simpleMediaItem.fileName`을 지정해 한 번의 batchCreate로 생성한다.
description과 creationTime/GPS 직접 설정 필드는 보내지 않는다.
결과는 `api-results.json`이며 uploadToken과 인증정보를 제외한다.
API의 부분 실패도 각 항목의 statusCode/mediaItemId로 기록한다.

첫 업로드 전 `upload-started.txt`를 생성하고 이후 같은 세트의 재실행을 거부한다.
네트워크 단절/실패/결과 파일 저장 실패 시 생성 여부가 불확실할 수 있으므로 자동 재시도하지 않는다.
이 표식은 운영 게시 큐의 멱등성 구현을 대신하지 않는다. 실제 결과를 확인하고 새 세트로 재검증한다.
PoC 항목 정리는 Google Photos UI에서 한다.

## Google Photos UI 관찰과 다음 단계 조건

`api-results.json`의 productUrl로 각 사진을 열고 `UI-RESULTS.md`에 다음을 기록한다.

1. 촬영 시각/날짜가 업로드 시각과 구분되는지, 타임라인과 자정 경계가 맞는지.
2. A/C/E의 GPS가 상세 화면에서 표시되고 지도/위치 검색에 반영되는지. 색인 대기 후 재확인 시각도 기록한다.
3. 한글 이름과 원본 HEIC 확장자 이름이 의도한 대로 표시되는지.
4. 화살표가 위쪽을 향하는지, 실제 썸네일의 체감 품질이 충분한지.

API 응답만으로 GPS·지도·검색 인식을 확인할 수 없다. 업로드 성공은 UI 인식 성공을 의미하지 않는다.
기존 DB에는 촬영 시각/위경도/원본명이 있지만 시간대·고도는 없다.
원본 EXIF 보충 범위, 시간대 없는 날짜, 영상 대표 이미지 게시 및 원본 확장자 이름 정책은 PoC 결과와 함께 확정할 대상이다.
사용자의 9단계까지 진행 요청에 따라 6~9단계 코드는 기본 비활성 상태로 구현을 이어간다.
이 최소 EXIF 세트는 임시 규칙이며 실제 계정 결과 전에는 확정/운영 검증 완료로 간주하지 않는다.
운영 게시 활성화와 소량 게시는 이후 같은 날 수행했다. 합성 샘플의 항목별 UI 검증과 자동 게시 확대는 이번 확인 범위에 포함하지 않았다.

## 로컬 검증 기록

추가한 독립 metadata-extractor 재추출 테스트는 날짜/시간대/위경도/Ref/고도/방향과 입력 보존을 확인한다.
JPEG APP1을 제외한 헤더와 압축 scan bytes를 직접 비교해 EXIF 주입에 의한 재압축이 없음을 검증한다.
로컬 HTTP 서버 테스트는 byte 헤더/본문, 원본 fileName, description 부재, 순서가 바뀐 batch 결과 매칭,
부분 실패, HTTP 500의 자동 재시도 부재와 비밀 응답 미노출을 확인한다.
OAuth Google 서버/실제 계정과 UI의 동작은 이 로컬 테스트에서 검증하지 않는다.

2026-09-30 16:35 KST `test googlePhotosMetadataPoc --offline --no-daemon --console=plain`은
서버 테스트 91건 모두 통과(실패·오류·건너뜀 0건)하고 합성 JPEG 5장과 결과 기록 파일을 생성했다.
추가한 PoC 테스트는 4건이며 기존 서버 87건을 함께 실행했다. 실제 Google 업로드는 실행하지 않았다.

## 확인한 공식 자료 (2026-09-30)

- [Google 업로드 지침](https://developers.google.com/photos/library/guides/upload-media): bytes → upload token → media 생성 순서, 업로드/description 규칙.
- [batchCreate 계약](https://developers.google.com/photos/library/reference/rest/v1/mediaItems/batchCreate): fileName과 결과, 한 번에 최대 50개.
- [Google Photos 인증 scope](https://developers.google.com/photos/overview/authorization): appendonly/appcreateddata 범위와 서비스 계정 미지원.
- [Desktop OAuth](https://developers.google.com/identity/protocols/oauth2/native-app): loopback IP redirect, PKCE와 state.
- [Apache Commons Imaging](https://commons.apache.org/proper/commons-imaging/) 및 [EXIF 작성 예제](https://github.com/apache/commons-imaging/blob/rel/commons-imaging-1.0.0-alpha6/src/test/java/org/apache/commons/imaging/examples/WriteExifMetadataExample.java): JVM의 EXIF 교체 방식.
