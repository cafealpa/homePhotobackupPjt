[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

Push-Location (Join-Path $PSScriptRoot 'server')
try {
    & .\gradlew.bat exportServerJar
    if ($LASTEXITCODE -ne 0) { throw 'Server tests/build failed.' }
} finally { Pop-Location }

$jar = Join-Path $PSScriptRoot 'deploy\homephoto-server.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $reader = [IO.StreamReader]::new($archive.GetEntry('META-INF/MANIFEST.MF').Open())
    try { $manifest = $reader.ReadToEnd().Replace("`r`n ", '').Replace("`n ", '') } finally { $reader.Dispose() }
} finally { $archive.Dispose() }
if ($manifest -notmatch '(?m)^Class-Path: (homephoto-face-runtime-[a-f0-9]{64}\.jar)\r?$') { throw 'Runtime manifest missing.' }
$runtime = Join-Path (Split-Path -Parent $jar) $Matches[1]
foreach ($file in @($jar, $runtime)) {
    if ((Get-Item -LiteralPath $file).Length -ge 100MB) { throw 'JAR exceeds the GitHub Git file size limit.' }
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$file.sha256", "$hash  $([IO.Path]::GetFileName($file))`n", [Text.Encoding]::ASCII)
    Write-Host "Ready: $file"
}
Write-Host 'Commit deploy/ with the source changes, then push when ready.'
