<#
.SYNOPSIS
포터블 JDK·SDK·Gradle 캐시로 APK를 빌드합니다. -Test를 지정하면 Generic Debug 단위 테스트도 실행합니다.
#>
param([switch]$Offline, [switch]$Test, [Parameter(ValueFromRemainingArguments=$true)][string[]]$Tasks)
. "$PSScriptRoot/Environment.ps1"
if (-not (Test-Path "$env:JAVA_HOME/bin/javac.exe") -or -not (Test-Path "$env:ANDROID_HOME/platforms/android-35/android.jar")) {
    throw '.build/Setup.ps1을 먼저 실행하세요.'
}
if (-not $Tasks) { $Tasks = @(':app:assembleGenericDebug') }
# 테스트 옵션은 직접 지정한 작업에도 적용하며 같은 테스트 작업을 중복 추가하지 않습니다.
if ($Test -and ':app:testGenericDebugUnitTest' -notin $Tasks) { $Tasks = @(':app:testGenericDebugUnitTest') + $Tasks }
$options = @('--console=plain', '--no-configuration-cache', '--no-daemon', '--max-workers=2', '-Pkotlin.compiler.execution.strategy=in-process')
if ($Offline) { $options += '--offline' }
Push-Location $ProjectRoot
try {
    $ErrorActionPreference = 'Continue'
    & "$ProjectRoot/gradlew.bat" @Tasks @options 2>&1 | Tee-Object -FilePath "$BuildRoot/logs/build.log" | Select-Object -Last 30
    $buildExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($buildExitCode -ne 0) { throw "Gradle 실패 ($buildExitCode). .build/logs/build.log를 확인하세요." }
    # 실제 생성된 설치·배포 파일의 절대 경로를 표시합니다.
    Get-ChildItem -LiteralPath "$ProjectRoot/app/build/outputs" -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in '.apk', '.aab' } |
        ForEach-Object { Write-Host "빌드 결과: $($_.FullName)" }
} finally { Pop-Location }
