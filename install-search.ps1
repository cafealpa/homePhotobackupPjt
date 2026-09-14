[CmdletBinding()]
param(
    [string]$InstallDir = 'E:\homePhotoServer',
    [string]$Repository = 'cafealpa/homePhotobackupPjt',
    [string]$Ref = 'main',
    [string]$PythonExe,
    [string]$ServerUrl = 'http://127.0.0.1:8080',
    [switch]$ResetApiKey
)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if (!(Test-Path -LiteralPath $InstallDir -PathType Container)) {
    throw "Installation folder not found: $InstallDir. Use -InstallDir with your actual HomePhoto folder."
}
$install = (Resolve-Path -LiteralPath $InstallDir).ProviderPath
if (!(Test-Path -LiteralPath (Join-Path $install 'homephoto-server.jar'))) { throw 'HomePhoto JAR not found in installation folder.' }
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid repository.' }
$uri = [Uri]$ServerUrl
if ($uri.Scheme -ne 'http' -or $uri.Host -ne '127.0.0.1' -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $uri.UserInfo) {
    throw 'ServerUrl must be a local HTTP origin, e.g. http://127.0.0.1:8080.'
}
$ServerUrl = $ServerUrl.TrimEnd('/')
if (!$PythonExe) {
    if (Get-Command py -ErrorAction SilentlyContinue) {
        foreach ($version in @('3.14', '3.12')) {
            try { $PythonExe = & py "-$version" -c 'import sys; print(sys.executable)' 2>$null }
            catch { $PythonExe = $null; continue }
            if ($LASTEXITCODE -eq 0 -and $PythonExe) { break }
            $PythonExe = $null
        }
    }
    if (!$PythonExe -and (Get-Command python -ErrorAction SilentlyContinue)) {
        $PythonExe = (Get-Command python).Source
    }
}
if (!$PythonExe) { throw 'Install Python 3.14 (64-bit), then rerun. Command: winget install -e --id Python.Python.3.14' }
$pythonVersion = & $PythonExe -c "import sys,platform,sysconfig; assert sys.version_info[:2] in ((3,12),(3,14)) and platform.machine().lower() in ('amd64','x86_64') and not sysconfig.get_config_var('Py_GIL_DISABLED'), 'Standard CPython 3.12 or 3.14 x64 required'; print(str(sys.version_info.major)+'.'+str(sys.version_info.minor))"
if ($LASTEXITCODE -ne 0) { throw 'Standard CPython 3.12 or 3.14 x64 is required. Specify -PythonExe if needed.' }
Write-Host "Search Python: $pythonVersion ($PythonExe)"

$worker = Join-Path $install 'ml-worker'
$private = Join-Path $worker 'search-private'
$credentialFile = Join-Path $private 'credentials.xml'
$stateFile = Join-Path $worker 'search-data\service.pid'
if (Test-Path -LiteralPath $stateFile) {
    $servicePid = [int](Get-Content -LiteralPath $stateFile)
    $running = Get-CimInstance Win32_Process -Filter "ProcessId=$servicePid"
    if ($running -and $running.CommandLine -like ('*' + (Join-Path $worker 'search_service.py') + '*')) {
        throw 'Stop the search service with start-installed-search.ps1 -Stop before reinstalling.'
    }
}
New-Item -ItemType Directory -Path $private -Force | Out-Null
# DPAPI credentials can only be opened by this Windows user on this PC.
if ((Test-Path -LiteralPath $credentialFile) -and !$ResetApiKey) {
    $saved = Import-Clixml -LiteralPath $credentialFile
    $apiKey = $saved.ApiKey
    $token = $saved.Token
} else {
    $apiKey = Read-Host 'Enter the existing HomePhoto API key (hidden)' -AsSecureString
    if ($apiKey.Length -eq 0) { throw 'API key is required.' }
    if (Test-Path -LiteralPath $credentialFile) {
        $token = (Import-Clixml -LiteralPath $credentialFile).Token
    } else {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $token = ConvertTo-SecureString ([BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()) -AsPlainText -Force
    }
}

$commit = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/commits/$([Uri]::EscapeDataString($Ref))" -Headers @{ 'User-Agent' = 'HomePhoto-Search-Installer' }
$sha = [string]$commit.sha
if ($sha -notmatch '^[a-f0-9]{40}$') { throw 'Invalid GitHub commit.' }
$stage = Join-Path $worker ('.install-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
try {
    foreach ($file in @('search_service.py','face_search.py','requirements-search.txt','start-search.ps1')) {
        Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/$Repository/$sha/ml-worker/$file" -OutFile (Join-Path $stage $file)
    }
    Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/$Repository/$sha/start-installed-search.ps1" -OutFile (Join-Path $stage 'start-installed-search.ps1')
    $python = Join-Path $worker '.venv-search\Scripts\python.exe'
    if (Test-Path -LiteralPath $python) {
        & $python -c "import sys; sys.exit(0 if str(sys.version_info.major)+'.'+str(sys.version_info.minor)==sys.argv[1] else 1)" $pythonVersion
        if ($LASTEXITCODE -ne 0) {
            $venv = [IO.Path]::GetFullPath((Join-Path $worker '.venv-search'))
            $oldVenv = [IO.Path]::GetFullPath((Join-Path $worker ('.venv-search-backup-' + [Guid]::NewGuid().ToString('N'))))
            if ((Split-Path $venv -Parent) -ne $worker -or (Split-Path $oldVenv -Parent) -ne $worker) { throw 'Invalid venv backup path.' }
            Move-Item -LiteralPath $venv -Destination $oldVenv
            Write-Host "Previous Python environment preserved at $oldVenv"
        }
    }
    if (!(Test-Path -LiteralPath $python)) {
        & $PythonExe -m venv (Join-Path $worker '.venv-search')
        if ($LASTEXITCODE -ne 0) { throw 'Virtual environment creation failed.' }
    }
    & $python -m pip install --only-binary=:all: -r (Join-Path $stage 'requirements-search.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed. Rerun after resolving the error.' }
    $previousModel = $env:HOMEPHOTO_SEARCH_MODEL
    try {
        $env:HOMEPHOTO_SEARCH_MODEL = Join-Path $worker 'search-model'
        & $python (Join-Path $stage 'search_service.py') --download-model
        if ($LASTEXITCODE -ne 0) { throw 'Model download failed. Server configuration was not changed.' }
    } finally { $env:HOMEPHOTO_SEARCH_MODEL = $previousModel }
    # Preserve previous code before replacing it. Models and indexes stay in place.
    $backup = Join-Path $worker ('install-backup-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $backup | Out-Null
    foreach ($file in @('search_service.py','face_search.py','requirements-search.txt','start-search.ps1')) {
        $dest = Join-Path $worker $file
        if (Test-Path -LiteralPath $dest) { Copy-Item -LiteralPath $dest -Destination $backup }
        Copy-Item -LiteralPath (Join-Path $stage $file) -Destination $dest -Force
    }
    Copy-Item -LiteralPath (Join-Path $stage 'start-installed-search.ps1') -Destination $install -Force
    [pscustomobject]@{ ApiKey=$apiKey; Token=$token; ServerUrl=$ServerUrl } | Export-Clixml -LiteralPath $credentialFile

    # Spring loads application.properties alongside application.yml; keep YAML/OAuth intact.
    $configDir = Join-Path $install 'config'
    New-Item -ItemType Directory -Path $configDir -Force | Out-Null
    $config = Join-Path $configDir 'application.properties'
    $content = ''
    if (Test-Path -LiteralPath $config) {
        Copy-Item -LiteralPath $config -Destination (Join-Path $backup 'application.properties')
        $content = [IO.File]::ReadAllText($config)
    }
    $content = [regex]::Replace($content, '(?ms)^# BEGIN HOMEPHOTO SEARCH\r?\n.*?^# END HOMEPHOTO SEARCH\r?\n?', '')
    $plainToken = [Net.NetworkCredential]::new('', $token).Password
    $block = "`r`n# BEGIN HOMEPHOTO SEARCH`r`nhomephoto.search.enabled=true`r`nhomephoto.search.base-url=http://127.0.0.1:18082`r`nhomephoto.search.token=$plainToken`r`n# END HOMEPHOTO SEARCH`r`n"
    [IO.File]::WriteAllText($config, $content + $block, [Text.UTF8Encoding]::new($false))
    $plainToken = $null
    [IO.File]::WriteAllText((Join-Path $private 'installed-commit.txt'), $sha)
    Write-Host 'Installation complete. Restart HomePhoto to load the search settings.'
    Write-Host "Then run: powershell -NoProfile -ExecutionPolicy Bypass -File `"$install\start-installed-search.ps1`""
    Write-Host 'Use the same Windows account. Startup is manual; no scheduled task was installed.'
} finally {
    Get-ChildItem -LiteralPath $stage -File | Remove-Item -Force
    Remove-Item -LiteralPath $stage
}
