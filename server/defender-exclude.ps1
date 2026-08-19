# 저장소 폴더를 Windows Defender 실시간 검사에서 제외한다.
# 썸네일·원본 파일이 수십만 개 쌓이면 파일을 열 때마다 검사가 붙어 그리드 로딩이 눈에 띄게 느려진다.
# 관리자 권한 PowerShell에서 실행:  powershell -ExecutionPolicy Bypass -File .\defender-exclude.ps1
# (제외 해제: Remove-MpPreference -ExclusionPath <경로>)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# 운영 저장소 경로: config/application.yml 의 storage-root (없으면 기본 ./data)
$root = "$here\data"
$cfg = Join-Path $here "config\application.yml"
if (Test-Path $cfg) {
    $line = Get-Content $cfg -Encoding UTF8 | Where-Object { $_ -match "^\s*storage-root:\s*(.+)$" } | Select-Object -First 1
    if ($line -and $line -match "^\s*storage-root:\s*['""]?([^'""]+)['""]?\s*$") { $root = $Matches[1].Trim() }
}
$root = [System.IO.Path]::GetFullPath($root)
if (-not (Test-Path $root)) { Write-Host "저장소 폴더가 없습니다: $root"; exit 1 }

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Write-Host "관리자 권한 PowerShell에서 실행해야 합니다."; exit 1 }

Add-MpPreference -ExclusionPath $root
Write-Host "Defender 실시간 검사 제외 추가: $root"
Write-Host "현재 제외 목록:"
(Get-MpPreference).ExclusionPath | ForEach-Object { Write-Host "  $_" }
