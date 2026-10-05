[CmdletBinding()]
param([switch]$WithFfmpeg)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot '../build-jar.ps1')
$deploy = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../deploy'))
$jar = Join-Path $deploy 'homephoto-server.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $reader = [IO.StreamReader]::new($zip.GetEntry('META-INF/MANIFEST.MF').Open())
    try { $manifest = $reader.ReadToEnd().Replace("`r`n ", '').Replace("`n ", '') } finally { $reader.Dispose() }
    $buildInfo = $zip.GetEntry('BOOT-INF/classes/META-INF/build-info.properties')
    if (-not $buildInfo) { $buildInfo = $zip.GetEntry('META-INF/build-info.properties') }
    $reader = [IO.StreamReader]::new($buildInfo.Open())
    try { $info = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
if ($info -notmatch '(?m)^build.version=([0-9A-Za-z.-]+)\r?$') { throw 'Build version missing' }
$version = $Matches[1]
if ($manifest -notmatch '(?m)^Class-Path: (homephoto-face-runtime-[a-f0-9]{64}\.jar)\r?$') { throw 'Runtime missing' }
$runtime = $Matches[1]
$release = Join-Path $PSScriptRoot 'release'
$stage = Join-Path $release ('stage-' + [Guid]::NewGuid().ToString('N') + '/homephoto-server-' + $version)
New-Item -ItemType Directory -Path "$stage/tools" -Force | Out-Null
foreach ($name in @('homephoto-server.jar', $runtime)) {
    Copy-Item -LiteralPath (Join-Path $deploy $name) -Destination $stage
    Copy-Item -LiteralPath (Join-Path $deploy "$name.sha256") -Destination $stage
}
foreach ($name in @('start-server.bat', 'stop-server.bat', 'defender-exclude.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $stage
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'dist/README.txt') -Destination $stage
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'dist/tools-README.txt') -Destination "$stage/tools/README.txt"
$notes = Join-Path $PSScriptRoot "../docs/RELEASE-$version.md"
if (Test-Path -LiteralPath $notes) { Copy-Item -LiteralPath $notes -Destination "$stage/RELEASE-NOTES.md" }
if ($WithFfmpeg) { Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'tools/ffmpeg.exe') -Destination "$stage/tools/ffmpeg.exe" }
$output = Join-Path $release "homephoto-server-$version.zip"
Compress-Archive -LiteralPath $stage -DestinationPath $output -Force
$lines = foreach ($file in @($output, $jar, (Join-Path $deploy $runtime))) {
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($file))"
}
[IO.File]::WriteAllLines((Join-Path $release "SHA256SUMS-$version.txt"), $lines, [Text.Encoding]::ASCII)
Write-Host "Release: $output"
