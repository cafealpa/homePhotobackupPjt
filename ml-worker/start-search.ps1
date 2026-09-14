param([switch]$Prepare, [switch]$Stop)
$ErrorActionPreference = 'Stop'
$python = Join-Path $PSScriptRoot '.venv-search/Scripts/python.exe'
$entry = Join-Path $PSScriptRoot 'search_service.py'
$state = Join-Path $PSScriptRoot 'search-data'
$pidFile = Join-Path $state 'service.pid'
if ($Prepare) {
    if (!(Test-Path -LiteralPath $python)) {
        python -m venv (Join-Path $PSScriptRoot '.venv-search')
        if ($LASTEXITCODE -ne 0) { throw 'venv creation failed' }
    }
    & $python -m pip install -r (Join-Path $PSScriptRoot 'requirements-search.txt')
    if ($LASTEXITCODE -ne 0) { throw 'dependency installation failed' }
    & $python $entry --download-model
    if ($LASTEXITCODE -ne 0) { throw 'model download failed' }
    exit
}
if (Test-Path -LiteralPath $pidFile) {
    $servicePid = [int](Get-Content -LiteralPath $pidFile)
    $running = Get-CimInstance Win32_Process -Filter "ProcessId=$servicePid"
    if ($running -and $running.CommandLine.Contains($entry)) {
        if ($Stop) { Stop-Process -Id $servicePid; Remove-Item -LiteralPath $pidFile; exit }
        Write-Output 'Photo search service is already running.'
        exit
    }
}
if ($Stop) { exit }
if (!(Test-Path -LiteralPath $python)) { throw 'Run with -Prepare first.' }
if (!$env:HOMEPHOTO_API_KEY -or !$env:HOMEPHOTO_SEARCH_TOKEN) {
    throw 'Set HOMEPHOTO_API_KEY and HOMEPHOTO_SEARCH_TOKEN before starting.'
}
New-Item -ItemType Directory -Path $state -Force | Out-Null
$process = Start-Process -FilePath $python -ArgumentList ('"' + $entry + '"') -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $state 'service.out.log') -RedirectStandardError (Join-Path $state 'service.err.log')
$process.Id | Set-Content -LiteralPath $pidFile
Write-Output 'Photo search process started. Check authenticated /health and service.err.log for readiness.'
