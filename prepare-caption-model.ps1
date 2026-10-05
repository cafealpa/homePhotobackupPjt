[CmdletBinding()]
param([string]$ModelDir = (Join-Path $PSScriptRoot 'deploy/models/caption-e5'))
$ErrorActionPreference = 'Stop'
$revision = '761b726dd34fb83930e26aab4e9ac3899aa1fa78'
$base = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/$revision"
New-Item -ItemType Directory -Path $ModelDir -Force | Out-Null
$files = @(
    @{ Name='model_quantized.onnx'; Remote='onnx/model_quantized.onnx'; Hash='f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193' },
    @{ Name='tokenizer.json'; Remote='tokenizer.json'; Hash='0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39' }
)
foreach ($file in $files) {
    $dest = Join-Path $ModelDir $file.Name
    if ((Test-Path -LiteralPath $dest) -and (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLowerInvariant() -eq $file.Hash) {
        Write-Host "Verified existing: $($file.Name)"
        continue
    }
    $partial = "$dest.download"
    Invoke-WebRequest -Uri "$base/$($file.Remote)" -OutFile $partial
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.Hash) {
        throw "Checksum mismatch for $($file.Name). The previous model was preserved."
    }
    Move-Item -LiteralPath $partial -Destination $dest -Force
}
$manifest = @{ model='intfloat/multilingual-e5-small'; artifact='Xenova/multilingual-e5-small'; revision=$revision;
    license='MIT'; dimension=384; pooling='attention-mask mean + L2'; maxTokens=512;
    queryPrefix='query: '; passagePrefix='passage: '; files=$files }
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $ModelDir 'model-manifest.json') -Encoding UTF8
Write-Host "Prepared offline model in $ModelDir. No server was started or changed."
