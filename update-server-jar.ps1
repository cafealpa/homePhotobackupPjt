[CmdletBinding()]
param(
    [string]$InstallDir = 'E:\homePhotoServer',
    [string]$Repository = 'cafealpa/homePhotobackupPjt',
    [string]$Ref = 'main'
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid repository.' }
$install = (Resolve-Path -LiteralPath $InstallDir).ProviderPath
$target = Join-Path $install 'homephoto-server.jar'
if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
    throw "Existing server JAR not found: $target. Specify the actual installation directory with -InstallDir."
}

# Resolve once so JAR and checksum always come from the same commit.
$headers = @{ 'User-Agent' = 'HomePhoto-Jar-Updater'; Accept = 'application/vnd.github+json' }
$encodedRef = [Uri]::EscapeDataString($Ref)
$commit = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/commits/$encodedRef" -Headers $headers
$sha = [string]$commit.sha
if ($sha -notmatch '^[a-f0-9]{40}$') { throw 'Invalid GitHub commit response.' }
$base = "https://raw.githubusercontent.com/$Repository/$sha/deploy"
$stage = Join-Path $install ('.homephoto-update-' + [Guid]::NewGuid().ToString('N') + '.jar')
try {
    $checksum = (Invoke-WebRequest -UseBasicParsing -Uri "$base/homephoto-server.jar.sha256").Content
    if ($checksum -is [byte[]]) { $checksum = [Text.Encoding]::ASCII.GetString($checksum) }
    if ([string]$checksum -notmatch '^([a-fA-F0-9]{64})\s+homephoto-server\.jar\s*$') { throw 'Invalid checksum file.' }
    $expected = $Matches[1]
    Write-Host "Downloading JAR from commit $sha ..."
    Invoke-WebRequest -UseBasicParsing -Uri "$base/homephoto-server.jar" -OutFile $stage
    $actual = (Get-FileHash -LiteralPath $stage -Algorithm SHA256).Hash
    if ($actual -ne $expected) { throw 'SHA256 mismatch. Existing JAR was not changed.' }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($stage)
    try {
        if (-not $zip.GetEntry('META-INF/MANIFEST.MF') -or
            -not $zip.GetEntry('BOOT-INF/classes/application.yml')) { throw 'Not a HomePhoto executable JAR.' }
    } finally { $zip.Dispose() }
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $actual) {
        Write-Host 'The installed JAR is already up to date.'
        return
    }
    # Never terminate a process by port. A running JVM normally locks its JAR.
    try {
        $handle = [IO.File]::Open($target, 'Open', 'ReadWrite', 'None')
        $handle.Dispose()
    } catch { throw 'Stop HomePhoto before updating, then run this script again. Existing JAR was not changed.' }
    $backup = Join-Path $install ('homephoto-server.jar.' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.' + [Guid]::NewGuid().ToString('N').Substring(0,8) + '.bak')
    [IO.File]::Replace($stage, $target, $backup)
    Write-Host "Updated: $target"
    Write-Host "Backup: $backup"
    Write-Host 'Start HomePhoto using your existing startup method.'
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Force }
}
