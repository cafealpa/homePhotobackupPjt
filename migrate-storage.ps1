# 사진 저장소 이전 스크립트 (1회용): server\data → C:\homePhotoData
# 같은 C: 드라이브라 이동은 rename 한 번 — 10GB여도 즉시 끝난다.
$ErrorActionPreference = "Stop"
$source = "C:\homeProjects\homePhotobackupPjt\server\data"
$target = "C:\homePhotoData"
$configFile = "C:\homeProjects\homePhotobackupPjt\server\config\application.yml"

# 1. 8080 서버가 떠 있으면 중지 (SQLite 파일 잠금 해제)
$conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if ($conn) {
    Write-Host "8080 서버(PID $($conn.OwningProcess)) 중지 중..."
    Stop-Process -Id $conn.OwningProcess -Force
    Start-Sleep -Seconds 3
}

# 2. 검증
if (-not (Test-Path $source)) { throw "이동할 데이터가 없습니다: $source (이미 이전을 마쳤다면 정상)" }
if (Test-Path $target) { throw "대상이 이미 존재합니다: $target — 내용을 확인하고 비운 뒤 다시 실행하세요" }

# 3. 이동
Write-Host "이동: $source → $target"
Move-Item $source $target

# 4. 외부 설정 파일 생성 — 서버가 새 위치를 보도록 (웹 설정 페이지도 이 파일에 저장한다)
New-Item -ItemType Directory -Force (Split-Path $configFile) | Out-Null
$yaml = @'
# 웹 설정 페이지에서 저장된 값 — classpath의 application.yml을 덮어쓴다 (Spring 외부 설정).
# 직접 편집해도 되며, 서버 재시작 시 반영된다. API 키를 잊었다면 여기서 확인.
homephoto:
  storage-root: 'C:/homePhotoData'
  api-key: 'dev-key-change-me'
  ffmpeg-path: './tools/ffmpeg.exe'
  trash-retention-days: 30
  caption:
    enabled: false
    base-url: 'http://localhost:11434'
    model: 'gemma3:12b'
    timeout-seconds: 180
'@
[System.IO.File]::WriteAllText($configFile, $yaml)

Write-Host ""
Write-Host "완료. 서버를 다시 시작하세요 (IntelliJ 실행 또는 java -jar build\libs\homephoto-server-0.1.0.jar)."
Write-Host "시작 로그에 '저장소 초기화: C:\homePhotoData' 가 보이면 성공입니다."
