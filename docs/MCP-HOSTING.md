# 집 PC의 Duck DNS / HTTPS 연결 준비

> 2026-09-14 갱신: 이 문서는 9월 11일의 준비 기록이다. 최신 인수인계상 운영 주소는 `https://cho6253prd.duckdns.org`이고 443/인증서 연결이 완료됐다. Duck DNS 자동 갱신은 사용자 요청으로 비활성이다. 현재 OAuth 후보와 개발/운영 경계는 [MCP-OAUTH.md](MCP-OAUTH.md)를 따른다. 아래 포트 분리 제안을 운영 설정으로 바로 적용하지 않는다.

2026-09-11 결정: PC가 켜진 동안에만 검색/보기, 원본·썸네일·DB는 집 PC에 보관한다. 현재 공인 IP가 유지된다는 전제로 포트포워딩을 사용하고 무료 Duck DNS 주소를 연결한다. MCP는 기존 Spring Boot 프로세스 안에 유지한다.

현재 준비한 파일은 **DNS 갱신과 HTTPS 연결 확인 전용**이다. 주소 등록, 인증서 발급, 공유기/방화벽 설정, OAuth 구현 및 실제 ChatGPT 연결은 아직 수행하지 않았다.

## 1. 사용자 계정에서 주소 등록

사용할 주소는 `cho6253.duckdns.org`로 확정했다. 2026-09-11 DNS 조회에서 A 레코드 응답을 확인했으며 AAAA 주소 응답은 없었다. DNS 응답만으로 집 공유기의 현재 WAN IP와 일치하는지는 확인되지 않는다. [Duck DNS](https://www.duckdns.org/)의 사용자 계정에서 해당 주소를 관리하는지 확인하고, 토큰은 로컬에서만 사용하며 대화나 저장소에 올리지 않는다.

먼저 필요한 정보:

- 확정: 운영 집 서버와 개발 PC는 별도 장비이며 같은 공유기에 연결돼 있다.
- 운영 서버와 개발 PC 각각의 LAN IP (공유기 DHCP 예약 권장)
- 공유기 WAN IP가 실제 공인 IPv4인지, ISP가 80/443 인바운드를 허용하는지

## 2. IP 갱신

PowerShell 7에서 사용자 계정으로 등록한 이름을 지정한다. `Domain`에는 `.duckdns.org`를 제외한 이름만 넣는다.

```powershell
# 실행 전에 대상만 확인. DNS 요청이나 토큰 읽기를 수행하지 않는다.
.\server\deploy\Update-DuckDns.ps1 -Domain 'cho6253' -WhatIf

# 실제 갱신: 콘솔에 토큰이 표시되지 않도록 입력한다.
$env:DUCKDNS_TOKEN = Read-Host 'Duck DNS 토큰' -MaskInput
try {
    .\server\deploy\Update-DuckDns.ps1 -Domain 'cho6253'
} finally {
    Remove-Item Env:DUCKDNS_TOKEN -ErrorAction SilentlyContinue
}
```

VPN이 꺼진 집 인터넷 연결에서 실행한다. IPv4는 Duck DNS가 요청 출발지에서 자동 판별한다. 기존 AAAA 레코드가 있다면 올바른 서버 IPv6인지 별도 확인한다. 이 스크립트는 IPv6를 갱신하거나 지우지 않는다.

PC 시작 시 갱신하는 방식으로 운영할 예정이다. 자동 시작/예약 작업은 아직 등록하지 않았으며, 토큰 저장 방법은 실제 등록 후 정한다.

## 3. 공유기와 HTTPS 준비

HTTPS는 Caddy가 받고, 이후 MCP/OAuth/미리보기의 허용된 경로만 Spring Boot로 전달하는 구성을 사용한다. Caddy는 TLS 처리를 위한 프로그램이며 별도의 사진 검색 백엔드는 아니다.

| 외부 TCP | 전달 대상 | 용도 |
|---|---|---|
| 80 | 운영 집 서버의 80 | 인증서 HTTP 검증 / HTTPS 전환 |
| 443 | 운영 집 서버의 443 | 운영 HTTPS |
| 8443 | 운영 집 서버의 8443 | 개발 HTTPS 진입점 |

8080(Spring Boot), 18081(샘플 데모), 2019(Caddy 관리 API)는 포트포워딩하지 않는다. 공유기 WAN 원격 관리가 80/443을 사용한다면 충돌부터 해결한다. Windows 방화벽 허용도 Caddy의 필요한 포트로 한정한다.

Caddy는 공식 배포본을 사용하고 인증서 데이터 폴더를 재시작 후에도 유지한다. DNS와 포트 전달이 준비되면 아래 연결 확인용 설정으로 검증한다.

```powershell
$env:HOMEPHOTO_DOMAIN = 'cho6253.duckdns.org'
caddy adapt --config .\server\deploy\Caddyfile.preflight --adapter caddyfile --validate
caddy run --config .\server\deploy\Caddyfile.preflight --adapter caddyfile
```

`run`은 실제 리스너를 열고 인증서 발급/갱신을 수행한다. 그 전에 등록한 도메인과 공인 IP를 확인한다. 준비용 파일은 `/_homephoto/ready`에 `homephoto-https-ready`만 응답하며 나머지 경로는 404다. 사진 API나 MCP는 연결하지 않는다.

휴대폰 Wi-Fi를 끄고 `https://cho6253.duckdns.org/_homephoto/ready`로 접속해 유효한 인증서와 응답을 확인한다. 집 내부 접속 성공만으로 외부 연결이 됐다고 판단하지 않는다. 테스트 서버에서 발급한 신뢰되지 않는 인증서는 ChatGPT 연결에 사용하지 않는다.

### 운영 / 개발 분리 구성안

같은 도메인에서 운영은 `https://cho6253.duckdns.org`, 개발은 `https://cho6253.duckdns.org:8443`을 사용한다. 집 서버의 Caddy 하나가 두 HTTPS 포트를 받고 인증서를 관리한다. 이후 운영 요청은 집 서버의 Spring Boot로, 개발 요청은 개발 PC의 LAN 주소로 전달한다. 아직 실제 프록시는 연결하지 않았다.

개발 PC에 외부 8443만 전달하는 구성에서는 기본 HTTP-01(80)/TLS-ALPN-01(443) 인증서 검증 요청이 개발 PC에 도착하지 않는다. 별도 DNS 검증이나 인증서 배포 관리를 추가하는 대신, 현재 구성안은 집 서버에서 TLS를 처리한다.

- 외부 개발 테스트에는 집 서버와 개발 PC가 모두 켜져 있어야 한다. localhost 개발은 집 서버 없이 가능하다. 이 의존성이 불편하면 인증서 구성을 다시 선택한다.
- 운영과 개발의 DB, 저장 경로, OAuth issuer/client 설정, 서명 키를 분리한다. 개발은 샘플 라이브러리를 사용한다.
- 개발 서버의 내부 포트는 집 서버에서만 접근하도록 방화벽을 제한한다. 현재 loopback 전용 데모를 그대로 LAN에 공개하지 않는다.
- OAuth URL, resource audience, 미리보기 URL과 CSP에는 개발 주소의 `:8443`까지 포함한다. 같은 호스트의 쿠키는 포트로 격리되지 않으므로 인증 쿠키 이름과 세션 검증 키도 환경별로 분리한다.
- ChatGPT의 실제 개발 연결에서 8443 주소와 OAuth 복귀 동작은 아직 검증하지 않았다.

준비용 Caddyfile은 두 포트 모두 상태 확인 응답만 반환한다. `https://cho6253.duckdns.org:8443/_homephoto/ready`도 휴대폰 외부망에서 확인한다. Caddy 실행/설정 검증과 인증서 발급은 대상 서버에서 수행해야 한다.
## 4. 다음 구현

- Spring Boot에 OAuth authorization-code + PKCE, `photos:read`, 발급/만료/철회와 단일 소유자 로그인을 구현한다.
- OAuth가 검증된 뒤 운영 모드를 추가한다. 현재 로컬 접근 검사만 삭제해서 공개하지 않는다.
- 등록한 HTTPS origin에 맞춰 MCP metadata, callback 허용 목록, 미리보기 URL과 UI CSP를 설정한다.
- 실제 OAuth 경로가 확정되면 Caddy에 명시적 허용 경로만 프록시한다. 기존 `/api/*`, `/mcp-dev`, 정적 관리 화면은 외부에 열지 않는다.
- PC 시작 시 DNS 갱신 → Caddy/서버 기동 → 인증서와 상태 확인 흐름을 만든다. PC가 오래 꺼져 인증서가 만료됐으면 갱신 완료 후 연결을 재개한다.
- PC가 꺼져 있을 때 클라이언트의 연결 실패 표시를 확인한다. 꺼진 서버가 자체 오류 메시지를 보낼 수는 없다.

## 공식 근거

- [Duck DNS IPv4 갱신 API](https://www.duckdns.org/spec.jsp)
- [Caddy 자동 HTTPS와 포트 조건](https://caddyserver.com/docs/automatic-https)
- [Caddy 명령행](https://caddyserver.com/docs/command-line)
- [OpenAI MCP OAuth](https://developers.openai.com/plugins/build/auth)
