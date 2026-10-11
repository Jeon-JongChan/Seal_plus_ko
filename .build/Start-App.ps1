<#
.SYNOPSIS
에뮬레이터 창을 열고 부팅 후 Generic Debug 앱을 설치·실행합니다.
.DESCRIPTION
빌드된 x86_64 APK가 있으면 업데이트 설치합니다. 없으면 이미 설치된 앱을 실행합니다.
#>
param(
    [ValidateSet('auto', 'host', 'software', 'swiftshader')][string]$Gpu = 'auto',
    [string]$DnsServer,
    [ValidateRange(1, 1800)][int]$TimeoutSeconds = 180
)
. "$PSScriptRoot/Environment.ps1"
& "$PSScriptRoot/Emulator.ps1" -ShowWindow -Gpu $Gpu -DnsServer $DnsServer
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$booted = $false
Write-Output '에뮬레이터 부팅을 기다립니다.'
$ErrorActionPreference = 'Continue'
while ((Get-Date) -lt $deadline) {
    # ADB 연결만으로는 앱 실행 준비가 되지 않으므로 Android 부팅 완료를 확인합니다.
    $boot = & $adb -s emulator-5556 shell getprop sys.boot_completed 2>$null
    if ($LASTEXITCODE -eq 0 -and $boot -match '^1$') { $booted = $true; break }
    Start-Sleep -Seconds 2
}
$ErrorActionPreference = 'Stop'
if (-not $booted) { throw "에뮬레이터 부팅 대기 시간 초과 ($TimeoutSeconds 초). .build/logs/emulator.err.log를 확인하세요." }
$apk = Get-ChildItem "$ProjectRoot/app/build/outputs/apk/generic/debug" -Filter '*x86_64*.apk' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($apk) {
    Write-Output "APK 설치: $($apk.Name)"
    $ErrorActionPreference = 'Continue'
    & $adb -s emulator-5556 install -r $apk.FullName 2>&1 | Select-Object -Last 8
    $installExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($installExitCode -ne 0) { throw 'APK 설치에 실패했습니다. 기존 앱 데이터는 유지합니다.' }
}
$ErrorActionPreference = 'Continue'
# 설치된 앱의 런처를 조회하여 플러그인 진입점 변경도 그대로 반영합니다.
$resolved = & $adb -s emulator-5556 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.junkfood.seal.debug 2>&1
$resolveExitCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$component = $resolved | ForEach-Object { "$_".Trim() } |
    Where-Object { $_ -match '^com\.junkfood\.seal\.debug/[A-Za-z0-9_.$]+$' } | Select-Object -Last 1
if ($resolveExitCode -ne 0 -or -not $component) {
    $resolved | Select-Object -Last 10
    throw '설치된 Generic Debug 앱의 런처를 찾지 못했습니다. APK 설치 상태와 AndroidManifest의 런처 설정을 확인하세요.'
}
$ErrorActionPreference = 'Continue'
$result = & $adb -s emulator-5556 shell am start -W -n $component 2>&1
$launchExitCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
$result | Select-Object -Last 10
# am은 앱이 없을 때 종료 코드 0을 반환할 수도 있으므로 오류 메시지도 검사합니다.
if ($launchExitCode -ne 0 -or ($result -join "`n") -match '(?m)^Error|Exception') {
    throw "앱 실행에 실패했습니다 ($component). 위의 ADB 오류 메시지를 확인하세요."
}
Write-Output 'Seal 앱 실행 완료.'
