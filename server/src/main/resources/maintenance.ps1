param([Parameter(Mandatory=$true)][string]$PlanPath)
$ErrorActionPreference = 'Stop'
$directory = Split-Path -Parent $PlanPath
$plan = Get-Content -LiteralPath $PlanPath -Raw -Encoding UTF8 | ConvertFrom-Json
$result = Join-Path $directory 'result.json'
$replaced = $false
$launched = $false
$backup = Join-Path $directory 'previous.jar'
function Read-Sha256([string]$path) {
    $stream = [IO.File]::OpenRead($path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
try {
    if (-not (Test-Path -LiteralPath $plan.java -PathType Leaf)) { throw 'Java executable missing' }
    if ($plan.source) {
        if (-not (Test-Path -LiteralPath $plan.target -PathType Leaf)) { throw 'Running JAR missing' }
        if ((Read-Sha256 $plan.source) -ne $plan.sha256) { throw 'Staged JAR checksum mismatch' }
    }
    [IO.File]::WriteAllText((Join-Path $directory 'ready'), 'ready')
    $old = Get-Process -Id $plan.pid -ErrorAction SilentlyContinue
    if ($old) { $old | Wait-Process -Timeout 180 -ErrorAction Stop }
    if ($plan.source) {
        # Only the verified JAR is replaced. Data/config/incoming are never extracted or overwritten.
        $next = $plan.target + '.new-' + [Guid]::NewGuid().ToString('N')
        Copy-Item -LiteralPath $plan.source -Destination $next
        if ((Read-Sha256 $next) -ne $plan.sha256) { throw 'Copied JAR checksum mismatch' }
        [IO.File]::Replace($next, $plan.target, $backup)
        $replaced = $true
    }
    $child = Start-Process -FilePath $plan.java -ArgumentList $plan.arguments -WorkingDirectory $plan.workDir -WindowStyle Hidden -PassThru
    $launched = $true
    @{phase='STARTED'; pid=$child.Id; backup=$backup} | ConvertTo-Json | Set-Content -LiteralPath $result -Encoding UTF8
} catch {
    # Roll back only when Java has not started; a launched version may already have migrated its DB.
    if ($replaced -and -not $launched) { Copy-Item -LiteralPath $backup -Destination $plan.target -Force }
    @{phase='FAILED'; error=$_.Exception.Message; backup=$backup} | ConvertTo-Json | Set-Content -LiteralPath $result -Encoding UTF8
    exit 1
}
