[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

Push-Location (Join-Path $PSScriptRoot 'server')
try {
    & .\gradlew.bat exportServerJar
    if ($LASTEXITCODE -ne 0) { throw 'Server tests/build failed.' }
} finally { Pop-Location }

$jar = Join-Path $PSScriptRoot 'deploy\homephoto-server.jar'
if ((Get-Item -LiteralPath $jar).Length -ge 100MB) {
    throw 'JAR exceeds the GitHub Git file size limit. Do not commit this artifact.'
}
$hash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$jar.sha256", "$hash  homephoto-server.jar`n", [Text.Encoding]::ASCII)
Write-Host "Ready: $jar"
Write-Host 'Commit deploy/ with the source changes, then push when ready.'
