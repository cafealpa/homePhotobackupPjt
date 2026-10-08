[CmdletBinding()]
param([string]$ModelDir = (Join-Path $PSScriptRoot 'deploy/models/siglip2'))
$ErrorActionPreference = 'Stop'
$revision = 'ba1f3b0843f24bc5417d38e19c37b287d719b2f4'
$base = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/$revision"
$files = @(
    @{ Name='text_model.onnx'; Remote='onnx/text_model.onnx'; Hash='baf12d941beabafafb14f7b4adb38dc15be18681b964a84410ec53d9d65e6293' },
    @{ Name='vision_model.onnx'; Remote='onnx/vision_model.onnx'; Hash='c0573e3f4140c3a7c4e9cc5912bd6b26a033b46a6a8e8af26cbea262b163bcad' },
    @{ Name='tokenizer.json'; Remote='tokenizer.json'; Hash='cb9140fae3ac5122c972d37adf83e1248471a38147ad76f8215c8872c6fd8322' }
)
New-Item -ItemType Directory -Path $ModelDir -Force | Out-Null
foreach ($file in $files) {
    $dest = Join-Path $ModelDir $file.Name
    if ((Test-Path -LiteralPath $dest) -and (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLowerInvariant() -eq $file.Hash) {
        Write-Host "Verified existing: $($file.Name)"
        continue
    }
    $partial = "$dest.download"
    Write-Host "Downloading: $($file.Name)"
    Invoke-WebRequest -Uri "$base/$($file.Remote)" -OutFile $partial
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.Hash) {
        throw "Checksum mismatch: $($file.Name). Previous model preserved."
    }
    Move-Item -LiteralPath $partial -Destination $dest -Force
}
@{ model='google/siglip2-base-patch16-224'; artifact='onnx-community/siglip2-base-patch16-224-ONNX';
   revision=$revision; license='Apache-2.0'; dimension=768; precision='float32'; files=$files } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $ModelDir 'model-manifest.json') -Encoding UTF8
Write-Host "Offline JVM search model prepared in $ModelDir."
