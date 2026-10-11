<#
.SYNOPSIS
코드만 내려받은 Windows x64 프로젝트에 포터블 JDK·Android SDK·Gradle을 설치합니다.
.DESCRIPTION
-Emulator를 지정하면 에뮬레이터와 API 35 이미지도 설치합니다. 재실행 시 기존 도구를 재사용합니다.
SDK 라이선스에 동의하고 설치하며 시스템 환경 변수는 변경하지 않습니다.
#>
param([switch]$Emulator)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/.build/Setup.ps1" -Emulator:$Emulator
. "$PSScriptRoot/.build/Environment.ps1"
Push-Location $ProjectRoot
try {
    # 프로젝트 Wrapper로 고정된 Gradle을 준비하고 실제 실행 가능 여부를 확인합니다.
    $ErrorActionPreference = 'Continue'
    & "$ProjectRoot/gradlew.bat" --version --console=plain 2>&1 |
        Tee-Object -FilePath "$BuildRoot/logs/gradle-setup.log" | Select-Object -Last 30
    $gradleExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($gradleExitCode -ne 0) { throw "Gradle 준비에 실패했습니다 ($gradleExitCode)." }
} finally { Pop-Location }
# 코드만 받은 환경에서도 루트의 한 명령으로 에뮬레이터와 앱을 실행할 수 있게 만듭니다.
$launcher = @'
<#
.SYNOPSIS
포터블 에뮬레이터와 Seal 앱을 실행합니다. Setup-Portable.ps1에서 생성합니다.
#>
param([string]$Gpu, [string]$DnsServer, [int]$TimeoutSeconds)
& "$PSScriptRoot/.build/Start-App.ps1" @PSBoundParameters
'@
[IO.File]::WriteAllText((Join-Path $ProjectRoot 'Start-App.ps1'), $launcher, [Text.UTF8Encoding]::new($true))
Write-Output '환경 구성 완료. APK 빌드: powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1'
Write-Output '에뮬레이터·앱 실행: powershell -NoProfile -ExecutionPolicy Bypass -File Start-App.ps1'
