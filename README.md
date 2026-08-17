# 홈 포토 백업 (Home Photo Backup)

안드로이드 사진·동영상을 집에 있는 PC로 백업하고, 웹 브라우저에서 타임라인·앨범·인물별로
찾아볼 수 있는 개인용 사진 서버입니다. 클라우드에 사진을 올리지 않고 내 PC에 보관합니다.

- **백업** — 폰의 사진/동영상을 자동으로 업로드. 해시 기반 중복 검사로 같은 사진은 다시 올리지 않습니다.
- **정리** — 촬영일시(EXIF) 기준으로 월별 폴더에 저장. 원본은 저장 후 절대 수정하지 않습니다.
- **보기** — 웹 뷰어에서 타임라인(일/주/월), 즐겨찾기, 앨범, 지도, 기기별, 휴지통.
- **인물** — 얼굴 인식으로 사람별 묶어보기 (선택 기능, 별도 설치 필요).
- **대용량 임포트** — PC에 이미 쌓여 있는 사진 폴더를 통째로 가져오기. 진행률·중지·이어하기를
  지원하고, 같은 드라이브라면 복사 대신 이동(rename)이라 추가 공간 없이 끝납니다.

## 구성

| 폴더 | 내용 |
|---|---|
| `server/` | Kotlin + Spring Boot API 서버 및 웹 뷰어. SQLite에 메타데이터 저장 |
| `android/` | Kotlin + Jetpack Compose 백업 앱 |
| `ml-worker/` | Python + InsightFace 얼굴 인식 워커 (선택) |
| `docs/` | 설계 문서 |

## 빠른 시작 (사용자)

빌드 없이 바로 쓰려면 **Releases**에서 `homephoto-server-x.y.z.zip`을 받으세요.

1. 압축을 풀고 `start-server.bat` 더블클릭 (Java 21 이상 필요 — https://adoptium.net)
2. 브라우저에서 `http://localhost:8080` 접속, 최초 API 키는 `dev-key-change-me`
3. **설정에서 API 키와 저장소 경로를 바꾸고 서버를 재시작하세요**
4. 폰에 앱을 설치하고 서버 주소(`http://내부IP:8080`)와 API 키를 입력
5. PC에 이미 있는 사진은 설정 맨 아래 **대용량 로컬 임포트**로 가져옵니다

자세한 안내는 zip 안의 `README.txt`에 있습니다.

## 개발

### 서버

```bash
cd server && ./gradlew bootRun
```

IntelliJ에서 실행해도 됩니다. 설정은 `server/src/main/resources/application.yml`이 기본값이고,
`server/config/application.yml`이 있으면 그 값이 우선합니다(웹 설정 페이지가 저장하는 파일).
이 파일에는 API 키가 평문으로 들어있어 git에 올라가지 않습니다.

주요 설정값:

| 키 | 기본값 | 설명 |
|---|---|---|
| `homephoto.storage-root` | `./data` | 사진·썸네일·DB가 쌓이는 위치 |
| `homephoto.api-key` | `dev-key-change-me` | 클라이언트가 `X-Api-Key`로 보내야 하는 값 |
| `homephoto.ffmpeg-path` | `./tools/ffmpeg.exe` | 동영상 썸네일·HEIC 변환용 |

`ffmpeg.exe`는 용량(212MB) 때문에 저장소에 포함하지 않습니다. 새 환경에서는
직접 받아 `server/tools/`에 넣으세요. 없으면 동영상 썸네일만 생성되지 않고 나머지는 정상 동작합니다.

### 안드로이드

Android Studio에서 `android/`를 열고 실행합니다. `local.properties`(SDK 경로)는 각자 환경에 맞게
자동 생성되며 git에 올라가지 않습니다.

### 얼굴 인식 워커 (선택)

`ml-worker/README.md` 참고. 서버와는 internal HTTP API로만 통신하므로 같은 PC든 다른 장비든
어디서 실행해도 됩니다.

## 배포본 만들기

```bash
cd server && ./package-release.bat
```

`gradlew bootJar`로 실행 가능한 jar를 만들고, 실행 스크립트·안내문과 함께
`server/release/homephoto-server-x.y.z.zip`으로 묶습니다. 이 zip을 GitHub Releases에 올리면 됩니다.

`package-release.bat withffmpeg`로 실행하면 `tools/ffmpeg.exe`도 함께 담습니다(용량 +212MB).

버전은 `server/build.gradle.kts`의 `version` 값을 따라갑니다. 릴리즈할 때마다 올려주세요.

### 서버 실행/중지 스크립트

`server/` 폴더와 배포 zip 양쪽에서 동일하게 동작합니다.

| 스크립트 | 동작 |
|---|---|
| `start-server.bat` | Java 확인 → 8080 중복 실행 확인 → jar 실행(새 창). 개발 환경에서 jar가 없으면 자동 빌드 |
| `stop-server.bat` | 8080 포트를 쓰는 프로세스를 종료 |

### 업데이트

새 jar로 교체하고 재시작하면 됩니다. `data`와 `config` 폴더는 그대로 두세요.
DB 스키마 변경은 서버가 시작할 때 자동으로 반영합니다.

## 문서

- [docs/DESIGN.md](docs/DESIGN.md) — 아키텍처, 저장 구조, API, 개발 이력
- [docs/KIDSNOTE.md](docs/KIDSNOTE.md) — 키즈노트 사진 가져오기
