<#
.SYNOPSIS
현재 프로젝트 위치를 기준으로 포터블 도구 경로를 설정합니다. 시스템 환경 변수는 변경하지 않습니다.
#>
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$BuildRoot = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $BuildRoot
# Seal 폴더를 제외해 복사하면 공용 설정만 사용합니다. 새 앱 설정은 Project.psd1에서 지정합니다.
$PortableProject = Import-PowerShellDataFile -LiteralPath (Join-Path $BuildRoot 'Project.psd1')
$sealSettings = Join-Path $BuildRoot 'Seal/Project.psd1'
if (Test-Path -LiteralPath $sealSettings) {
    $sealValues = Import-PowerShellDataFile -LiteralPath $sealSettings
    foreach ($key in $sealValues.Keys) { $PortableProject[$key] = $sealValues[$key] }
}
$LogRoot = Join-Path $BuildRoot $PortableProject.LogDirectory
$env:JAVA_HOME = Join-Path $BuildRoot 'tools/jdk'
$env:ANDROID_HOME = Join-Path $BuildRoot 'tools/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $BuildRoot 'gradle-cache'
$env:ANDROID_USER_HOME = Join-Path $BuildRoot $PortableProject.AndroidUserDirectory
$env:ANDROID_AVD_HOME = Join-Path $BuildRoot $PortableProject.AvdDirectory
$env:TEMP = Join-Path $BuildRoot $PortableProject.TempDirectory
$env:TMP = $env:TEMP
$env:PATH = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:ANDROID_HOME/emulator;$env:PATH"
New-Item -ItemType Directory -Force -Path $LogRoot, $env:ANDROID_USER_HOME, $env:ANDROID_AVD_HOME, $env:TEMP | Out-Null

# 새 체크아웃에서도 SDK 경로를 기록하고 기존 다른 설정은 유지합니다.
$properties = Join-Path $ProjectRoot 'local.properties'
$sdkValue = -join ($env:ANDROID_HOME.ToCharArray() | ForEach-Object {
    if ([int]$_ -gt 127) { '\u{0:x4}' -f [int]$_ }
    elseif ($_ -eq '\' -or $_ -eq ':') { '\' + $_ }
    else { [string]$_ }
})
$lines = @()
if (Test-Path -LiteralPath $properties) {
    $lines = @(Get-Content -LiteralPath $properties -Encoding UTF8 | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*=' })
}
[IO.File]::WriteAllLines($properties, @($lines) + "sdk.dir=$sdkValue", [Text.UTF8Encoding]::new($false))
