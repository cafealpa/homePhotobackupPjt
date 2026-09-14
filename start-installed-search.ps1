[CmdletBinding()]
param([switch]$Stop)
$ErrorActionPreference = 'Stop'
$worker = Join-Path $PSScriptRoot 'ml-worker'
$launcher = Join-Path $worker 'start-search.ps1'
if ($Stop) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $launcher -Stop
    if ($LASTEXITCODE -ne 0) { throw 'Search stop failed.' }
    exit
}
$saved = Import-Clixml -LiteralPath (Join-Path $worker 'search-private\credentials.xml')
$names = @('HOMEPHOTO_API_KEY','HOMEPHOTO_SEARCH_TOKEN','HOMEPHOTO_SERVER','HOMEPHOTO_SEARCH_MODEL','HOMEPHOTO_SEARCH_DATA','HOMEPHOTO_SEARCH_PORT')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:HOMEPHOTO_API_KEY = [Net.NetworkCredential]::new('', $saved.ApiKey).Password
    $env:HOMEPHOTO_SEARCH_TOKEN = [Net.NetworkCredential]::new('', $saved.Token).Password
    $env:HOMEPHOTO_SERVER = $saved.ServerUrl
    $env:HOMEPHOTO_SEARCH_MODEL = Join-Path $worker 'search-model'
    $env:HOMEPHOTO_SEARCH_DATA = Join-Path $worker 'search-data'
    $env:HOMEPHOTO_SEARCH_PORT = '18082'
    # Verify Spring auth and enabled catalog before launching expensive model loading.
    $null = Invoke-RestMethod -Uri "$($saved.ServerUrl)/api/v1/internal/search/faces?afterId=0" -Headers @{ 'X-Api-Key'=$env:HOMEPHOTO_API_KEY } -TimeoutSec 15
    & powershell -NoProfile -ExecutionPolicy Bypass -File $launcher
    if ($LASTEXITCODE -ne 0) { throw 'Search launch failed.' }
    Write-Host 'Search started. Model loading takes time; inspect ml-worker/search-data/service.err.log if needed.'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
