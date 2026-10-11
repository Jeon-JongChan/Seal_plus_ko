<#
.SYNOPSIS
공식 Windows x64 JDK와 Android CLI를 프로젝트 안에 준비하고 선택적으로 에뮬레이터를 설치합니다.
#>
param([switch]$Emulator)
. "$PSScriptRoot/Environment.ps1"
if (-not [Environment]::Is64BitOperatingSystem -or $env:OS -ne 'Windows_NT') { throw 'Windows x64 환경이 필요합니다.' }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
New-Item -ItemType Directory -Force -Path "$BuildRoot/downloads", "$BuildRoot/tools" | Out-Null

<# 다운로드한 ZIP의 SHA-256을 검증하고 안전한 프로젝트 하위 경로에만 압축을 풉니다. #>
function Install-PortableArchive([string]$Url, [string]$Hash, [string]$Destination, [string]$Prefix) {
    $archivePath = Join-Path $BuildRoot ('downloads/' + [IO.Path]::GetFileName(([Uri]$Url).AbsolutePath))
    if (-not (Test-Path -LiteralPath $archivePath) -or (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $Hash) {
        Write-Output ('다운로드: ' + [IO.Path]::GetFileName($archivePath))
        $client = [Net.WebClient]::new()
        try { $client.DownloadFile($Url, $archivePath) } finally { $client.Dispose() }
    }
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $Hash) { throw '다운로드 체크섬이 일치하지 않습니다.' }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($entry in $archive.Entries) {
            if (-not $entry.FullName.StartsWith($Prefix, [StringComparison]::Ordinal)) { throw '예상하지 못한 ZIP 경로입니다.' }
            $relative = $entry.FullName.Substring($Prefix.Length)
            if ($relative -eq '') { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $Destination $relative))
            if (-not $target.StartsWith([IO.Path]::GetFullPath($Destination) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'ZIP 경로가 설치 폴더를 벗어납니다.' }
            if ($entry.FullName.EndsWith('/')) { New-Item -ItemType Directory -Force -Path $target | Out-Null }
            else {
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            }
        }
    } finally { $archive.Dispose() }
}

if (-not (Test-Path "$env:JAVA_HOME/bin/javac.exe")) {
    Install-PortableArchive 'https://cdn.azul.com/zulu/bin/zulu21.52.203-ca-jdk21.0.12.1-win_x64.zip' 'ebfe6ccb94a1a7c8743ed270c6a96dbb22f5abc48b2cc4785dbf067e02dbed22' $env:JAVA_HOME 'zulu21.52.203-ca-jdk21.0.12.1-win_x64/'
}
$sdkManager = "$env:ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager.bat"
if (-not (Test-Path -LiteralPath $sdkManager)) {
    Install-PortableArchive 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a' "$env:ANDROID_HOME/cmdline-tools/latest" 'cmdline-tools/'
}
# 사용자가 요청한 SDK 설치 범위의 라이선스를 명령줄 설치 절차로 처리합니다.
$ErrorActionPreference = 'Continue'
1..100 | ForEach-Object { 'y' } | & $sdkManager "--sdk_root=$env:ANDROID_HOME" --licenses 2>&1 | Select-Object -Last 15
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw 'SDK 라이선스 처리에 실패했습니다.' }
$packages = @('platform-tools', 'platforms;android-35', 'build-tools;34.0.0')
if ($Emulator) { $packages += @('emulator', 'system-images;android-35;google_apis;x86_64') }
$ErrorActionPreference = 'Continue'
& $sdkManager "--sdk_root=$env:ANDROID_HOME" --install @packages 2>&1 | Tee-Object -FilePath "$BuildRoot/logs/setup.log" | Select-Object -Last 30
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK 설치에 실패했습니다.' }
Write-Output '포터블 빌드 환경 준비 완료.'
