<#
.SYNOPSIS
현재 프로젝트 위치를 기준으로 포터블 도구 경로를 설정합니다. 시스템 환경 변수는 변경하지 않습니다.
#>
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$BuildRoot = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $BuildRoot
$env:JAVA_HOME = Join-Path $BuildRoot 'tools/jdk'
$env:ANDROID_HOME = Join-Path $BuildRoot 'tools/android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $BuildRoot 'gradle-cache'
$env:ANDROID_USER_HOME = Join-Path $BuildRoot 'android-user'
$env:ANDROID_AVD_HOME = Join-Path $BuildRoot 'avd'
$env:TEMP = Join-Path $BuildRoot 'tmp'
$env:TMP = $env:TEMP
$env:PATH = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:ANDROID_HOME/emulator;$env:PATH"
New-Item -ItemType Directory -Force -Path (Join-Path $BuildRoot 'logs'), $env:ANDROID_USER_HOME, $env:ANDROID_AVD_HOME, $env:TEMP | Out-Null

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
