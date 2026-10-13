<# .SYNOPSIS 고정 버전의 RIFE 소스와 ncnn Android Vulkan SDK만 로컬 빌드 캐시에 준비합니다. #>
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$commit = 'a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7'
$upstream = Join-Path $PSScriptRoot 'upstream'
if (-not (Test-Path -LiteralPath "$upstream/.git")) {
    & git clone --quiet --filter=blob:none --no-checkout https://github.com/nihui/rife-ncnn-vulkan.git $upstream
    if ($LASTEXITCODE) { throw 'RIFE 원본 준비 실패' }
    & git -C $upstream sparse-checkout set src LICENSE README.md --skip-checks
    if ($LASTEXITCODE) { throw 'RIFE 소스 선택 실패' }
    & git -C $upstream checkout --quiet $commit
    if ($LASTEXITCODE) { throw 'RIFE 고정 버전 준비 실패' }
}
$actual = & git -C $upstream rev-parse HEAD
if ($actual -ne $commit) { throw 'RIFE 소스 버전이 다릅니다. 기존 캐시를 덮어쓰지 않습니다.' }
$archive = Join-Path $PSScriptRoot 'ncnn-20260526-android-vulkan.zip'
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/Tencent/ncnn/releases/download/20260526/ncnn-20260526-android-vulkan.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne '26909C92EED35AFED4A966B5E9E503FCB0A529691EA3F910EC2C94A4FFF52804') {
    throw 'ncnn SDK 체크섬이 다릅니다.'
}
$sdk = Join-Path $PSScriptRoot 'ncnn'
if (-not (Test-Path -LiteralPath "$sdk/ncnn-20260526-android-vulkan/arm64-v8a/lib/cmake/ncnn/ncnnConfig.cmake")) {
    Expand-Archive -LiteralPath $archive -DestinationPath $sdk
}
Write-Host 'RIFE 고정 소스 및 ncnn Vulkan SDK 검증 완료'
