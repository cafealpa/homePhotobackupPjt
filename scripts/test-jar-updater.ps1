$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$artifact = Join-Path $root 'deploy\homephoto-server.jar'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('homephoto-updater-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$target = Join-Path $fixture 'homephoto-server.jar'
$global:homePhotoTestBadHash = $false
$global:homePhotoTestOffline = $false
$global:homePhotoTestExpected = (Get-FileHash $artifact -Algorithm SHA256).Hash
function Invoke-RestMethod { param($Uri, $Headers) return @{ sha = ('a' * 40) } }
function Invoke-WebRequest {
    param([switch]$UseBasicParsing, $Uri, $OutFile)
    if ($global:homePhotoTestOffline) { throw 'Simulated download failure' }
    if ($Uri -notlike ('https://raw.githubusercontent.com/*/' + ('a' * 40) + '/deploy/*')) { throw 'Unpinned URL' }
    if ($OutFile) { Copy-Item -LiteralPath $artifact -Destination $OutFile; return }
    $hash = $global:homePhotoTestExpected
    if ($global:homePhotoTestBadHash) { $hash = '0' * 64 }
    return @{ Content = "$hash  homephoto-server.jar`n" }
}
function Assert-OldJar { if ([IO.File]::ReadAllText($target) -ne 'old jar') { throw 'Existing JAR changed on failure' } }
try {
    [IO.File]::WriteAllText($target, 'old jar')
    foreach ($mode in @('checksum', 'offline', 'locked')) {
        $global:homePhotoTestBadHash = $mode -eq 'checksum'
        $global:homePhotoTestOffline = $mode -eq 'offline'
        $lock = $null
        if ($mode -eq 'locked') { $lock = [IO.File]::Open($target, 'Open', 'Read', 'Read') }
        $failed = $false
        try { & "$root\update-server-jar.ps1" -InstallDir $fixture }
        catch { $failed = $true }
        finally { if ($lock) { $lock.Dispose() } }
        if (-not $failed) { throw "Expected failure: $mode" }
        Assert-OldJar
    }
    $global:homePhotoTestBadHash = $false
    $global:homePhotoTestOffline = $false
    & "$root\update-server-jar.ps1" -InstallDir $fixture
    if ((Get-FileHash $target).Hash -ne $global:homePhotoTestExpected) { throw 'Replacement failed' }
    $backups = @(Get-ChildItem -LiteralPath $fixture -Filter '*.bak')
    if ($backups.Count -ne 1 -or [IO.File]::ReadAllText($backups[0].FullName) -ne 'old jar') { throw 'Backup failed' }
    & "$root\update-server-jar.ps1" -InstallDir $fixture
    if (@(Get-ChildItem -LiteralPath $fixture -Filter '*.bak').Count -ne 1) { throw 'Unnecessary replacement' }
    if (@(Get-ChildItem -LiteralPath $fixture -Filter '.homephoto-update-*').Count -ne 0) { throw 'Staging file leaked' }
    Write-Host 'PASS: checksum/download/lock failures preserve JAR; replacement, backup, no-op and cleanup.'
} finally {
    # Only remove files in the unique test directory created above; no recursive deletion.
    Get-ChildItem -LiteralPath $fixture -File | Remove-Item -Force
    Remove-Item -LiteralPath $fixture
    Remove-Variable homePhotoTestBadHash,homePhotoTestOffline,homePhotoTestExpected -Scope Global
}
