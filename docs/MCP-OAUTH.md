# Home Photo OAuth 릴리즈 후보

2026-09-14 / 0.1.6-rc1. **개발 검증용 후보이며 운영 배포·실제 ChatGPT 연결은 아직 완료하지 않았다.**

## 운영 인수인계 기준

사용자가 전달한 점검 결과: 운영 설치는 `E:\homePhotoServer` 0.1.5 / Java 21, 공개 주소는 `https://cho6253prd.duckdns.org`, Caddy는 같은 장비의 `127.0.0.1:8080`으로 전달한다. 외부 443과 인증서는 검증됐고 API 키 교체 후 웹/앱/워커 인증이 동작한다. 이번 개발에서 운영 설치본을 직접 재검증하거나 변경하지 않았다.

Duck DNS 자동 갱신 작업은 사용자 요청으로 비활성화되어 있다. 배포나 준비 과정에서 재활성화하지 않는다. 이전 `MCP-HOSTING.md`의 도메인·포트 분리 제안은 과거 설계다. 개발용 HTTPS 주소는 아직 없다.

## 구현과 경계

- 기존 Spring Boot 프로세스 안에 Spring Authorization Server / Spring Security를 사용한다. `homephoto.mcp.mode=local`과 `oauth`를 구분하고, 기본 `enabled=false`를 유지한다.
- 단일 소유자 `owner`가 전용 비밀번호로 로그인하고 사진 읽기에 동의한다. 회원가입은 없고 기존 사진 API 키는 OAuth 자격 증명이 아니다.
- 사전 등록 클라이언트 `homephoto-chatgpt`, `client_secret_basic` 또는 `client_secret_post`, authorization-code + PKCE **S256 필수**, scope `photos:read`만 제공한다. DCR/CIMD는 구현하지 않았고 지원한다고 광고하지 않는다. 실제 개인 ChatGPT UI에서 사전 등록 입력 지원 여부를 확인해야 한다.
- issuer는 설정의 HTTPS origin, resource/audience는 정확히 `<origin>/mcp`다. authorization 및 token 요청의 resource와 원래 동의 요청의 resource를 대조한다. callback은 관리 화면에서 복사한 HTTPS URI 하나만 정확히 허용한다.
- 인증 코드 2분 / access token 10분 / refresh token 30일. 갱신 시 refresh token을 교체하고 이전 토큰 재사용을 거부한다. JWT 검증과 함께 저장된 grant의 활성 상태를 확인한다. `/oauth/connections`에서 모든 연결을 해제하면 grant와 동의 기록을 지운다.
- 권한 상태·동의는 사진 SQLite와 분리된 H2 파일에 저장한다. JDBC 구현과 프레임워크 직렬화를 사용한다. RSA 서명 키는 재시작 시 새로 만들지 않고 로컬 파일에서 읽는다. DB와 키 파일은 같은 서버 실행 계정만 접근할 수 있도록 보관한다.
- 미리보기는 별도 키로 사진/크기/만료/grant를 서명한다. 기본 5분, 400/1600 썸네일만 제공하며 삭제·휴지통 상태와 grant 활성 여부를 다시 검사한다. URL을 가진 사람은 만료/철회 전까지 볼 수 있으므로 URL을 비밀로 취급한다. 이미 내려받은 이미지는 회수할 수 없다.
- MCP 작업 스레드에는 SDK `McpTransportContext`로 검증된 grant ID만 전달한다. 모델 입력의 grant ID를 신뢰하지 않는다. 도구 `_meta.securitySchemes`에 읽기 OAuth 권한을 표시하고 HTTP 401 challenge로 계정 연결을 안내한다.
- 공개 경로는 같은 장비의 loopback Caddy 요청만 받는다. `Host`, `X-Forwarded-Host`는 설정 origin과 일치하고 `X-Forwarded-Proto=https`여야 한다. 검증 후 요청 origin을 복원한다. `server.forward-headers-strategy=none`이 필수이며 프록시 헤더를 숨겨 로컬 모드를 우회하지 않는다.
- 로그인 세션은 `__Host-hp_oauth` Secure/HttpOnly/SameSite=Lax 쿠키, 15분 유휴 만료, CSRF 보호를 사용한다. 기존 `hp_auth`와 분리한다. 분당 서버 전체 상한은 로그인 POST 10회, token/MCP 각 120회, 미리보기 600회다. 초과 시 429/Retry-After를 반환한다.
- 기존 `/api/*`의 키/쿠키 인증은 유지한다. 기존 운영 Caddy 라우팅을 이 문서의 예시로 통째로 대체하지 않는다. 공개 모드의 `/mcp-dev`는 차단한다.

## 개발용 설정 생성

운영 사진 DB·저장소를 사용하지 않는 **별도 개발 환경**에서 진행한다. 먼저 개발용 HTTPS origin을 준비하고 ChatGPT 관리 화면에 표시되는 정확한 redirect URI를 확보한다. 임의 callback을 운영 설정으로 넣지 않는다.

검증할 JAR을 둔 로컬 터미널에서 다음을 실행한다. 아래 경로와 origin/callback은 예시이며 실제 값으로 바꾼다.

```powershell
java -jar .\homephoto-server.jar --initialize-photo-oauth `
  --directory=C:/HomePhotoDev/oauth-private `
  --base-url=https://YOUR-DEV-HOST `
  --redirect-uri=https://chatgpt.com/connector/oauth/YOUR-CALLBACK-ID
```

이 명령은 Spring 서버나 사진 DB를 시작하지 않는다. 12자 이상 소유자 비밀번호를 숨김 입력으로 두 번 받고, **존재하지 않는 새 디렉터리**에만 파일을 만든다. 부모 디렉터리는 미리 있어야 한다. 생성 전에 디렉터리를 현재 사용자 전용 ACL로 제한한다.

- `application-oauth.properties`: bcrypt 비밀번호/클라이언트 secret 해시, 환경별 설정. 처음에는 MCP 비활성.
- `signing-key.json`: 영속 RSA 개인 키.
- `chatgpt-client-secret.txt`: ChatGPT 사전 등록 입력용 클라이언트 secret. 로컬에서만 열어 입력하고 대화/로그에 복사하지 않는다.
- `state/`: 공개 모드를 처음 시작할 때 OAuth DB 생성.

기존 설정에 덮어쓰지 말고 실행 프로세스에서 추가 설정을 import한다. 개발 사진 저장소/API 키도 별도로 지정한다.

```powershell
$env:SPRING_CONFIG_IMPORT = 'file:C:/HomePhotoDev/oauth-private/application-oauth.properties'
$env:HOMEPHOTO_MCP_ENABLED = 'true'
# 개발 전용 저장소와 API 키를 설정한 실행 환경에서 시작한다.
java -jar .\homephoto-server.jar
```

Spring의 기존 `SPRING_CONFIG_IMPORT`가 있으면 유지하고 새 파일 위치를 추가한다. `spring.config.location`으로 기존 설정 전체를 대체하지 않는다. 키/DB를 생성한 사용자 계정과 실제 서버 실행 계정이 같아야 한다. 운영은 개발과 다른 비밀번호·클라이언트 secret·RSA 키·preview 키·state 디렉터리를 사용한다.

저장소가 있는 개발 환경에서는 위 `SPRING_CONFIG_IMPORT`를 설정한 뒤 `server` 폴더에서 `./gradlew.bat mcpOAuthDemo`를 실행하면 된다. 운영 애플리케이션/워커를 시작하지 않고 임시 DB에 2025-11-03 샘플 이미지 14장을 생성한다. 서버 포트는 loopback 18081이며 Caddy 예시의 `HOMEPHOTO_UPSTREAM=127.0.0.1:18081`을 지정한다. 공개 모드에서는 `/mcp-dev` 대신 실제 OAuth/MCP 클라이언트로 확인한다. 기존 `mcpDemo`는 계속 로컬 토큰 모드로 실행한다.

## 프록시와 실제 ChatGPT 검증

`server/deploy/Caddyfile.oauth.example`는 **새 개발 사이트용 예시**다. 같은 장비에서 Caddy와 개발 서버를 실행하는 구성을 전제로 한다. 운영 Caddy에서 다른 PC의 개발 서버로 LAN 프록시하는 이전 제안은 이 릴리즈의 loopback 신뢰 모델과 맞지 않는다. 개발 HTTPS 제공 방식은 별도 결정해야 한다.

일반 API를 새로 외부 공개하지 않고 MCP/OAuth/미리보기만 전달한다. 운영은 이미 제공하던 기존 앱 경로를 유지하면서 OAuth 경로의 요청 크기 제한 등을 병합한다. 운영 Caddy의 인증서·데이터 디렉터리는 유지한다. 80이 막혀도 443 TLS-ALPN 검증으로 인증서를 발급할 수 있지만 개발용 주소/포트는 실제 외부망에서 확인해야 한다.

1. 외부에서 `/.well-known/oauth-protected-resource`와 `/.well-known/oauth-authorization-server` 확인. 인증 정보 없이 접근 가능한 discovery에 비밀은 없다.
2. 무인증 `/mcp` → 401 + `WWW-Authenticate`의 `resource_metadata`. 잘못된 토큰은 401, 부족한 scope는 403.
3. ChatGPT에서 MCP URL `<origin>/mcp`, 사전 등록 client ID/secret을 입력. UI의 exact redirect URI와 설정을 일치시킨다. issuer 식별 지원 광고에 따라 callback 형태가 달라질 수 있으므로 추측하지 않는다.
4. 소유자 로그인·동의 → initialize/tools/list → `2025-11-03` 샘플 검색 → 갤러리/확대/더 보기. 실제 ChatGPT UI와 `_meta` 호환을 반드시 확인한다.
5. 미리보기 만료 후 새로고침, 연결 해제 후 기존 access token과 미리보기 차단, 다시 동의 및 서버 재시작 후 연결 유지 확인.
6. 기존 웹 로그인/조회, 앱 및 워커 API 인증을 회귀 확인한다.

공개 서버의 OAuth 요청 본문, Authorization/Cookie, URL query를 로그에 남기지 않는다. 예시는 access log를 켜지 않는다. 운영에 기존 access log가 있다면 민감 경로의 query 제거 또는 해당 경로 로그 제외를 병합한다. Spring Security DEBUG/TRACE도 운영에서 사용하지 않는다.

## 배포와 롤백

개발 HTTPS 및 실제 ChatGPT 연결 검증이 완료되기 전에는 운영에 공개 OAuth를 활성화하지 않는다. 이번 산출물은 `0.1.6-rc1` 후보이며 자동 배포하지 않는다.

1. 현재 운영 JAR(0.1.5), 실행 설정/환경변수, Caddyfile을 별도 보관한다. 사진 DB의 기존 백업 정책도 유지한다.
2. 서버를 정상 종료한 뒤 새 JAR만 교체한다. 운영 사진/썸네일/DB/워커와 기존 API 키를 덮어쓰지 않는다. 이번 변경은 사진 스키마를 변경하지 않는다.
3. 운영 origin과 callback으로 별도 OAuth private 디렉터리를 생성한다. 개발 자격 증명을 복사하지 않는다. 서버 시작 시 private 설정 import와 MCP 활성화를 적용한다.
4. 운영 OAuth와 기존 웹/앱/워커를 확인한다. Duck DNS 예약 작업은 계속 비활성으로 둔다.
5. 장애 시 정상 종료 → 기존 JAR과 기존 실행 설정 복원 → 새 OAuth import/활성화 환경변수 제거 → 필요하면 Caddy 변경 복원 → 재시작한다. OAuth private 폴더는 외부에 공개하지 않고 보관한다. 롤백한 0.1.5는 MCP가 비활성/로컬 전용이다.

## 자동 검증

`server/gradlew.bat test bootJar --console=plain`은 임시 사진 SQLite/JPEG와 별도 H2/테스트 키를 사용한다. 인증 코드, S256, redirect/resource 검증, JWT 서명/issuer/audience/만료/scope, 갱신 토큰 재사용 거부, 철회, OAuth 사진 검색/미리보기, 실제 loopback HTTP 및 Secure 쿠키, 기존 웹/API 인증 분리를 검사한다. 이 자동 검증은 실제 ChatGPT·운영 HTTPS 검증을 대신하지 않는다.

이번 검증 결과: 전체 37개 테스트 통과, JAR 빌드 성공. JAR의 설정 생성 명령을 테스트 비밀번호로 실행해 기본 비활성과 현재 사용자 전용 디렉터리/키 ACL을 확인했다. 생성한 설정으로 `mcpOAuthDemo`도 기동해 discovery의 S256과 MCP 401 challenge를 확인하고 종료했다. Caddy 예시의 실행 검증 및 실제 ChatGPT 계정 연결은 개발 HTTPS 준비 후 수행해야 한다.

## 근거

- [OpenAI 인증 및 클라이언트 등록](https://developers.openai.com/plugins/build/auth)
- [OpenAI 연결 검증](https://developers.openai.com/plugins/deploy/connect-chatgpt)
- [Spring Authorization Server](https://docs.spring.io/spring-authorization-server/reference/getting-started.html)
- [Caddy reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)
