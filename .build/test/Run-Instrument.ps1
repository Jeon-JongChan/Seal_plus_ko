<#
.SYNOPSIS
검사 APK를 빌드·설치하고 지정한 계측 테스트를 실행합니다. 모든 출력은 파일로 저장하며 파이프로 자르지 않습니다.
.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File .build/test/Run-Instrument.ps1 -TestClass com.plugin.Revision16Test -Tag rev16
#>
param([string]$TestClass, [string]$Tag = 'run', [switch]$SkipApp, [switch]$NoBuild)
. "$PSScriptRoot/../Environment.ps1"
Set-Location $ProjectRoot
$ErrorActionPreference = 'Continue'
$log = Join-Path $LogRoot "$Tag"
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$device = "emulator-$($PortableProject.EmulatorPort)"
if (-not $NoBuild) {
    $tasks = @(':app:assembleGenericPhoneCheckAndroidTest')
    if (-not $SkipApp) { $tasks = @(':app:assembleGenericPhoneCheck') + $tasks }
    & ./gradlew.bat '--console=plain' '--max-workers=2' '-Pkotlin.compiler.execution.strategy=in-process' '-I' '.build/test/PhoneCheck.init.gradle' '-PcheckAbi=x86_64' @tasks *> "$log-build.log"
    if ($LASTEXITCODE -ne 0) { Write-Output "빌드 실패: $log-build.log"; exit 1 }
}
if (-not $SkipApp) { & $adb -s $device install -r -t (Get-ChildItem 'app/build/outputs/apk/generic/phoneCheck' -Filter '*x86_64*.apk' | Select-Object -First 1).FullName *> "$log-install.log" }
& $adb -s $device install -r -t 'app/build/outputs/apk/androidTest/generic/phoneCheck/app-generic-phoneCheck-androidTest.apk' *>> "$log-install.log"
& $adb -s $device logcat -c
$args2 = @('-s', $device, 'shell', 'am', 'instrument', '-w')
if ($TestClass) { $args2 += @('-e', 'class', $TestClass) }
$args2 += "$($PortableProject.ApplicationId.Replace('.debug','')).devicecheck.reviewtest/com.plugin.SafeDeviceRunner"
& $adb @args2 *> "$log-instrument.log"
& $adb -s $device logcat -d *> "$log-logcat.log"
Write-Output "완료: $log-instrument.log"
