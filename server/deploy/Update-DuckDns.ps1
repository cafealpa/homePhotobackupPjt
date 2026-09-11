#requires -Version 7.0
[CmdletBinding(SupportsShouldProcess)]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$')]
    [string]$Domain
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 이 스크립트는 이미 사용자 계정에 등록된 이름만 갱신한다. 주소를 새로 등록하지 않는다.
if (-not $PSCmdlet.ShouldProcess("$Domain.duckdns.org", '현재 인터넷 연결의 공인 IPv4로 DNS 갱신')) {
    return
}

$duckToken = $env:DUCKDNS_TOKEN
if ([string]::IsNullOrWhiteSpace($duckToken)) {
    throw 'DUCKDNS_TOKEN 환경변수를 로컬에서 설정해 주세요. 토큰을 대화나 명령행 인자로 보내지 마세요.'
}

# 공급자의 HTTPS API 규격: ip를 비우면 요청 출발지 IPv4를 자동 판별한다.
# URI와 공급자 예외 원문에는 토큰이 포함될 수 있으므로 출력하지 않는다.
$requestUri = 'https://www.duckdns.org/update?domains=' + [Uri]::EscapeDataString($Domain) +
    '&token=' + [Uri]::EscapeDataString($duckToken) + '&ip='
try {
    $result = Invoke-RestMethod -Uri $requestUri -Method Get -TimeoutSec 20 -MaximumRedirection 0
} catch {
    throw 'Duck DNS 갱신 요청에 실패했어요. 인터넷 연결과 서비스 상태를 확인해 주세요.'
} finally {
    $requestUri = $null
    $duckToken = $null
}
if ([string]$result -notmatch '^\s*OK\s*$') {
    throw 'Duck DNS가 갱신을 거절했어요. 등록한 주소와 로컬 토큰을 확인해 주세요.'
}
Write-Output "$Domain.duckdns.org IPv4 갱신 완료"
