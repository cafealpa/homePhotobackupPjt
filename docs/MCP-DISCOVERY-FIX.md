# MCP 탐색 호환성 수정 — 0.1.6-rc2

## 재현한 문제와 수정

rc1의 Java MCP SDK 0.18.4에 인증된 `server/discover` JSON-RPC 요청을 보내면 HTTP 500이 반환된다. 공식 SDK 소스의 `WebMvcStatelessServerTransport.handlePost`가 미지원 메서드 예외를 일반 내부 오류로 변환하기 때문이다. 해당 요청은 2026-07-28 이후 프로토콜을 사용하는 클라이언트의 탐색 단계에서 나타날 수 있다.

rc2는 인증·프록시 검증 이후의 `/mcp` 라우터에 호환성 처리를 추가했다. `server/discover`에는 요청 ID를 보존한 HTTP 404 / JSON-RPC `-32601 Method not found`를 반환한다. 이 응답은 새 프로토콜 성공 응답이 아니라, 기존 `initialize` 방식으로 전환할 수 있도록 하는 미지원 신호다. 2026-07-28의 전체 프로토콜을 구현하거나 지원 버전으로 광고하지 않는다.

일반 초기화·도구·사진 검색 요청은 body를 복원해서 기존 SDK에 전달한다. 탐색 알림(id 없음)은 202/빈 본문, 잘못된 JSON은 400/-32700, 잘못된 요청은 400/-32600으로 처리한다. MCP 요청 본문은 64 KiB로 제한한다. 로그인·토큰 검증·미리보기 서명은 변경하지 않았다.

## 검증과 남은 확인

- 수정 전 탐색 요청의 HTTP 500을 회귀 테스트로 재현했다.
- 수정 후 버전 헤더 없음/2025-06-18/2026-07-28과 문자열 request ID를 포함한 탐색에 404/-32601을 확인했다.
- OAuth 없이 탐색하면 401, 잘못된 토큰은 401, 허용되지 않은 Origin/프록시는 403으로 기존 인증 경계를 유지한다.
- 인증된 탐색 거부 → 기존 initialize → tools/list에서 사진 도구 2개 반환을 확인했다.
- 전체 41개 테스트와 bootJar 빌드가 통과했다. 사진 검색·미리보기·토큰 갱신/철회·기존 웹/워커 인증 회귀가 포함된다.

운영에서 액션 목록이 비는 현상 전체가 해결됐는지는 rc2 적용 후 실제 ChatGPT 새로고침으로 확인해야 한다. 이번 작업에서는 운영 서버를 재시작하거나 ChatGPT 연결 설정을 변경하지 않았다. 새 프로토콜만 지원하고 기존 방식으로 전환하지 않는 클라이언트는 이 수정만으로 지원되지 않는다.

## rc1에서 교체

1. 현재 JAR을 백업하고 서버를 정상 종료한다.
2. rc2 ZIP의 `homephoto-server.jar`만 기존 설치의 JAR과 교체한다. 기존 실행 스크립트, OAuth properties/private key/state, API 키, 사진 DB, 썸네일, 워커, Caddyfile은 유지한다.
3. 기존 실행 설정으로 시작한다. **이 수정 때문에 소유자 비밀번호·OAuth client secret을 재발급하거나 초기화 명령을 다시 실행할 필요는 없다.** Duck DNS 예약 작업도 변경하지 않는다.
4. ChatGPT에서 도구 목록을 새로고침하고 날짜 검색·미리보기를 확인한다. 실패하면 비밀 값 없이 HTTP 상태, JSON-RPC method/id/error.code를 확인한다.
5. 문제가 있으면 정상 종료 후 백업 JAR로 복원한다. rc2는 사진/OAuth DB 스키마를 바꾸지 않았다.

이 ZIP은 JAR 교체용이며 실행 스크립트·Java·ffmpeg·워커는 포함하지 않는다.

## 공식 근거

- [MCP 버전 호환성 및 기존 초기화 방식 전환](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning#backward-compatibility-with-initialization-based-versions)
- [MCP TypeScript SDK 버전 협상](https://ts.sdk.modelcontextprotocol.io/v2/protocol-versions)
- [사용 중인 공식 Java SDK 소스](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/sdk/mcp-spring-webmvc/0.18.4/mcp-spring-webmvc-0.18.4-sources.jar)
