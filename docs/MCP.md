# 홈 포토 MCP 통합

2026-09-11: 기존 Spring Boot 프로세스 안에 MCP를 통합했다. 현재 완료 범위는 **단일 소유자 / 로컬 검증**이다. 실제 ChatGPT 계정 연결, OAuth, 외부 HTTPS 운영, Gemini에서의 표시 검증은 아직 완료하지 않았다.

## 실행해 보기

실제 라이브러리나 운영 설정을 읽지 않는 샘플 갤러리:

```powershell
cd server
.\gradlew.bat mcpDemo
```

- 브라우저: `http://localhost:18081/mcp-dev`
- 날짜: `2025-11-03` (샘플 이미지 14장)
- 테스트 토큰: `local-test-token-0123456789-abcdef-not-a-real-secret`
- 중지: 실행 터미널에서 Ctrl+C. 샘플 DB/이미지는 임시 폴더를 사용하며 정상 종료 시 정리한다.
- 테스트 토큰은 샘플 데이터 전용이다. 실제 라이브러리에는 새 임의 토큰을 사용한다.

기존 라이브러리로 로컬 확인하려면, 서버를 실행할 PowerShell에 환경변수를 설정한다.

```powershell
$env:HOMEPHOTO_MCP_ENABLED = 'true'
$env:HOMEPHOTO_MCP_BASE_URL = 'http://localhost:8080'
$env:HOMEPHOTO_MCP_TOKEN = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
# 토큰을 로컬 테스트 화면에 입력할 때만 이 터미널에서 확인한다. 로그/스크린샷/커밋에 남기지 않는다.
$env:HOMEPHOTO_MCP_TOKEN
.\gradlew.bat bootRun
```

이미 서버가 켜져 있으면 먼저 정상 종료하고, 기존 저장소·설정을 사용하는 같은 작업 폴더에서 시작한다. 기능을 끄려면 `HOMEPHOTO_MCP_ENABLED=false`로 재시작한다. MCP 토큰을 바꾸고 재시작하면 기존 미리보기 서명도 무효화된다.

`base-url`은 실행 포트와 일치하는 loopback HTTP origin이어야 한다. 뒤에 `/`나 경로를 붙이지 않는다. 개발 모드에서는 외부 IP, 외부 Host, 전달 프록시 헤더를 차단한다. 이 설정을 터널로 공개하기 위해 검사를 제거하지 않는다. 별도의 OAuth 운영 모드를 먼저 구현해야 한다.

## 구현 구조

```text
Spring Boot (기존 프로세스)
  /mcp          공식 Java MCP SDK / stateless Streamable HTTP
    search_photos -> AssetQueryService -> SQLite
    get_photo     -> 활성 사진 상세
  MCP resource  ui://homephoto/gallery-v1.html
  /mcp-media/id HMAC 미리보기 URL -> 기존 ThumbnailService
  /mcp-dev      로컬 테스트 호스트 (ChatGPT를 모사하는 개발 화면)
```

Java SDK의 Spring Framework 6 호환 계열 `0.18.4`를 사용한다. 별도 TypeScript 서버, Node 런타임, 모델 API 키는 필요하지 않다. `mcpDemo`의 Java AWT 코드는 테스트 이미지 생성 전용이며 배포 jar에 포함되지 않는다.

## 도구 계약

| 도구 | 인자 | 결과 |
|---|---|---|
| `search_photos` | `date` 필수 YYYY-MM-DD, `cursor` 선택, `limit` 1~24 기본 12 | 날짜, 사진 목록, 다음 cursor |
| `get_photo` | `photo_id` 양의 정수 | 사진 메타데이터와 갱신된 미리보기 |

- 연도가 생략되고 대화에도 없으면 모델이 사용자에게 연도를 확인한다. 서버는 불완전하거나 존재하지 않는 날짜를 거부한다.
- 날짜는 기존 라이브러리의 촬영일 기준이다. 파일명 → EXIF → 파일 수정시각 → 업로드 시각 우선순위를 바꾸지 않으며, timezone 없는 저장값을 UTC로 재해석하지 않는다.
- 사진만 조회한다. 동영상, 휴지통, 키즈노트 전용 사진은 제외한다.
- 정렬: 촬영시각 내림차순, ID 내림차순. 다음 cursor는 같은 날짜에만 사용할 수 있다.
- 한 장을 더 조회해서 마지막 페이지의 다음 cursor를 정확히 계산한다.
- `structuredContent`에는 ID, 촬영일, 날짜 출처, 크기만 들어간다. 경로, GPS, 해시, API 키는 포함하지 않는다.
- 서명 URL은 UI 전용 `_meta.previews`에 반환한다. UI가 없는 호스트는 메타데이터로 답할 수 있지만, 사진 표시를 보장하지 않는다.
- 도구 오류는 `isError=true`와 사용자용 설명으로 반환한다. 서버 예외의 내부 내용은 결과에 노출하지 않는다.

MCP POST는 `Authorization: Bearer <MCP 토큰>`과 `Accept: application/json, text/event-stream`을 사용한다. 기존 `X-Api-Key`/`hp_auth`는 MCP 인증에 사용하지 않는다. 초기화 이후 클라이언트는 협상된 `MCP-Protocol-Version`을 전송한다. stateless 서버에는 세션 ID가 없다.

## 갤러리와 미리보기

MCP Apps JSON-RPC bridge로 도구 결과를 받아 12장씩 표시한다. 더 보기, 카드 확대, 미리보기 새로고침, 빈 결과/오류 안내를 제공한다. 외부 JS/CDN 없이 정적 HTML을 jar에 포함한다.

미리보기는 원본 대신 기존 400/1600 JPEG 썸네일만 사용한다. URL은 사진 ID·크기·만료시각에 HMAC 서명을 붙이며 기본 5분간 유효하다. 요청 시 휴지통·삭제 여부를 다시 조회한다. 썸네일이 아직 없으면 404를 반환하고, 공개 이미지 응답에 `no-store`를 적용한다. 유효기간 내 URL을 가진 사람은 해당 미리보기를 볼 수 있으므로 URL은 로그나 외부 문서에 남기지 않는다.

`/mcp-dev`는 인증된 MCP 호출과 MCP UI 리소스 로딩을 실제로 수행하는 로컬 호스트다. 토큰은 입력 필드 메모리에만 두며 localStorage/쿠키에 저장하지 않는다. **이 화면 성공은 ChatGPT 실연결 성공을 의미하지 않는다.**

## 검증

```powershell
cd server
.\gradlew.bat test
```

테스트는 별도의 임시 SQLite와 생성한 JPEG를 사용한다. 운영 서버의 애플리케이션 스캔·DB 마이그레이션·워커는 실행하지 않는다.

- MCP initialize, tools/list, resources/read
- 날짜 경계, 동일 시각 ID 정렬, 다음 페이지, 정확히 마지막 페이지, 빈 날짜
- 동영상/휴지통/키즈노트 제외, 잘못된 날짜/limit/cursor 거부
- 전용 토큰 필수, 허용되지 않은 Origin/원격 클라이언트/프록시 거부, 기본 비활성화
- 미리보기 JPEG 반환, 서명 변조/정상 서명의 만료/발급 후 삭제/썸네일 미생성

## 다음 결정과 작업

확정: 서버 프로세스 통합, 내 계정 하나에서 전체 일반 사진 라이브러리 조회.

1. **외부 연결 방식**: 집 PC의 가동 시간, 기존 도메인 유무, 네트워크 조건을 확인하고 HTTPS 터널 또는 리버스 프록시를 선택한다. MCP 호출뿐 아니라 갤러리의 이미지 요청 경로도 검증해야 한다.
2. **계정 연결**: 운영 주소에 맞춰 OAuth authorization-code + PKCE, protected-resource metadata, `photos:read`, 토큰 발급·만료·철회를 구현한다. 기존 사진 API 키를 OAuth 토큰으로 전달하지 않는다. 단일 소유자 권한을 부여할 로그인 수단을 정한다.
3. **공개 범위**: MCP·OAuth·미리보기 경로만 외부 진입점에 연결한다. 기존 업로드/설정/관리자 API는 노출하지 않는다. 정확한 UI CSP 도메인을 설정한다.
4. **ChatGPT 실연결**: 사용 계정의 개발 연결 권한 확인 → 도구 호출 → 실제 썸네일 표시 → 확대/더 보기/만료 후 새로고침 검증.
5. **Gemini**: 공식 앱의 지역·계정 지원 조건을 확인한다. 커스텀 MCP 사용 가능성과 갤러리 UI 지원 여부는 각각 검증한다. API를 쓰는 자체 화면은 별도 선택지다.

사진 서버가 꺼져 있으면 검색·미리보기도 사용할 수 없다. 운영 모드에서는 장애 안내, 요청 크기 제한·호출 제한, 접근 기록의 민감 URL 제거를 함께 적용한다. 비용이 발생하는 외부 인프라 배포는 운영 구성을 정한 다음 진행한다.

## 참고 문서

- [OpenAI MCP 서버](https://developers.openai.com/plugins/build/mcp-server)
- [OpenAI MCP Apps UI](https://developers.openai.com/plugins/build/chatgpt-ui)
- [OpenAI OAuth 인증](https://developers.openai.com/plugins/build/auth)
- [OpenAI 연결과 테스트](https://developers.openai.com/plugins/deploy/connect-chatgpt)
- [Java MCP SDK](https://java.sdk.modelcontextprotocol.io/)
- [Gemini 커스텀 연결 지원 조건](https://support.google.com/gemini/answer/17209137)
