$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('homephoto-install-test-' + [Guid]::NewGuid().ToString('N'))
$private = Join-Path $fixture 'ml-worker\search-private'
New-Item -ItemType Directory -Path $private -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $fixture 'config') | Out-Null
[IO.File]::WriteAllText((Join-Path $fixture 'homephoto-server.jar'), 'fixture')
[IO.File]::WriteAllText((Join-Path $fixture 'config\application.yml'), 'homephoto: {}')
[IO.File]::WriteAllText((Join-Path $fixture 'config\application.properties'), "custom.setting=preserved`n")
[pscustomobject]@{
    ApiKey=(ConvertTo-SecureString 'test-api' -AsPlainText -Force)
    Token=(ConvertTo-SecureString ('a' * 64) -AsPlainText -Force)
} | Export-Clixml -LiteralPath (Join-Path $private 'credentials.xml')
$global:homePhotoFailModel = $false
function Invoke-TestPython {
    if ($args[0] -eq '-m' -and $args[1] -eq 'venv') {
        New-Item -ItemType Directory -Path (Join-Path $args[2] 'Scripts') -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $args[2] 'Scripts\python.exe'), 'fixture')
    }
    $global:LASTEXITCODE = 0
    if ($args -contains '--download-model' -and $global:homePhotoFailModel) { $global:LASTEXITCODE = 1 }
}
function Invoke-RestMethod { param($Uri,$Headers) return @{sha=('b' * 40)} }
function Invoke-WebRequest {
    param([switch]$UseBasicParsing,$Uri,$OutFile)
    if ($Uri -notlike ('*/' + ('b' * 40) + '/*')) { throw 'Unpinned download' }
    [IO.File]::WriteAllText($OutFile, '# mock downloaded file')
}
try {
    foreach ($file in @('install-search.ps1','start-installed-search.ps1')) {
        $errors = $null; $tokens = $null
        $null = [Management.Automation.Language.Parser]::ParseFile((Join-Path $root $file), [ref]$tokens, [ref]$errors)
        if ($errors.Count) { throw "$file syntax errors: $errors" }
    }
    # Mock only external Python/network operations; exercise filesystem/config/DPAPI logic.
    $source = [IO.File]::ReadAllText((Join-Path $root 'install-search.ps1'))
    $source = $source.Replace('& $PythonExe ', 'Invoke-TestPython ').Replace('& $python ', 'Invoke-TestPython ')
    $installer = [scriptblock]::Create($source)
    & $installer -InstallDir $fixture -PythonExe 'mock'
    & $installer -InstallDir $fixture -PythonExe 'mock'
    $config = [IO.File]::ReadAllText((Join-Path $fixture 'config\application.properties'))
    if ([regex]::Matches($config,'# BEGIN HOMEPHOTO SEARCH').Count -ne 1 -or !$config.Contains('custom.setting=preserved')) { throw 'Config preservation/idempotence failed' }
    if ([IO.File]::ReadAllText((Join-Path $fixture 'config\application.yml')) -ne 'homephoto: {}') { throw 'YAML modified' }
    $saved = Import-Clixml (Join-Path $private 'credentials.xml')
    if ([Net.NetworkCredential]::new('', $saved.ApiKey).Password -ne 'test-api') { throw 'Credential reuse failed' }
    $global:homePhotoFailModel = $true
    $failed = $false
    try { & $installer -InstallDir $fixture -PythonExe 'mock' } catch { $failed = $true }
    if (!$failed -or [IO.File]::ReadAllText((Join-Path $fixture 'config\application.properties')) -ne $config) { throw 'Model failure changed configuration' }
    Write-Host 'PASS: syntax, pinned downloads, repeated install, config preservation, DPAPI reuse, model failure.'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or !(Split-Path $resolved -Leaf).StartsWith('homephoto-install-test-')) { throw 'Unsafe cleanup path' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
    Remove-Variable homePhotoFailModel -Scope Global
}
