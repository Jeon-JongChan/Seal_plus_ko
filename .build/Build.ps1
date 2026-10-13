<#
.SYNOPSIS
포터블 도구로 앱 APK만 빌드합니다. -Test는 단위 검사, -TestApk는 검사 APK 생성을 명시적으로 추가합니다.
#>
param([switch]$Offline, [switch]$Test, [switch]$TestApk, [Parameter(ValueFromRemainingArguments=$true)][string[]]$Tasks)
. "$PSScriptRoot/Environment.ps1"
if (-not (Test-Path "$env:JAVA_HOME/bin/javac.exe") -or -not (Test-Path "$env:ANDROID_HOME/platforms/android-$($PortableProject.ApiLevel)/android.jar")) {
    throw '.build/Setup.ps1을 먼저 실행하세요.'
}
if (-not $Tasks) { $Tasks = @($PortableProject.BuildTask) }
# 테스트 옵션은 직접 지정한 작업에도 적용하며 같은 테스트 작업을 중복 추가하지 않습니다.
if ($Test -and $PortableProject.TestTask -notin $Tasks) { $Tasks = @($PortableProject.TestTask) + $Tasks }
# 검사 APK는 전용 옵션이나 사용자가 직접 지정한 Gradle 작업으로만 생성합니다.
if ($TestApk -and $PortableProject.TestApkTask -notin $Tasks) { $Tasks += $PortableProject.TestApkTask }
# 반복 빌드는 데몬과 프로젝트의 구성 캐시 설정을 재사용합니다.
$options = @('--console=plain', '--max-workers=2', '-Pkotlin.compiler.execution.strategy=in-process')
if ($Offline) { $options += '--offline' }
Push-Location $ProjectRoot
try {
    $ErrorActionPreference = 'Continue'
    $buildStartedUtc = [DateTime]::UtcNow
    & "$ProjectRoot/gradlew.bat" @Tasks @options 2>&1 | Tee-Object -FilePath "$LogRoot/build.log" | Select-Object -Last 30
    $buildExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($buildExitCode -ne 0) { throw "Gradle 실패 ($buildExitCode). $LogRoot/build.log를 확인하세요." }
    # 이전 검사 APK가 남아 있어도 이번 빌드 결과처럼 표시하지 않습니다.
    $outputs = @(Get-ChildItem -LiteralPath "$ProjectRoot/app/build/outputs" -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in '.apk', '.aab' -and $_.LastWriteTimeUtc -ge $buildStartedUtc })
    $outputs | ForEach-Object { Write-Host "빌드 결과: $($_.FullName)" }
    if (-not $outputs) { Write-Host '새로 생성된 APK가 없습니다. 변경 없는 작업의 기존 결과는 해당 출력 폴더에서 확인하세요.' }
} finally { Pop-Location }
