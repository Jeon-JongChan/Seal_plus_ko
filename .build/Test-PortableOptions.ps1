<#
.SYNOPSIS
다운로드 없이 새 경로의 설치 진입점·환경 구성과 에뮬레이터 옵션 분기를 검사합니다.
#>
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/Environment.ps1"
$testRoot = Join-Path $env:TEMP ('portable check-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$testRoot/.build" -Force | Out-Null
Copy-Item "$PSScriptRoot/Environment.ps1" "$testRoot/.build/Environment.ps1"
Copy-Item "$PSScriptRoot/Project.psd1" "$testRoot/.build/Project.psd1"
Copy-Item "$PSScriptRoot/Setup-Portable.ps1" "$testRoot/.build/Setup-Portable.ps1"
# 실제 네트워크 설치와 Gradle만 대체하고 진입점·환경 설정은 원본을 실행합니다.
Set-Content "$testRoot/.build/Setup.ps1" 'param([switch]$Emulator); [IO.File]::WriteAllText("$PSScriptRoot/emulator-option.txt", [string]$Emulator)' -Encoding UTF8
Set-Content "$testRoot/gradlew.bat" '@exit /b 0' -Encoding ASCII
$originalLocation = Get-Location
& "$testRoot/.build/Setup-Portable.ps1" | Out-Null
if ((Get-Content "$testRoot/.build/emulator-option.txt") -ne 'False') { throw '기본 설치 옵션 전달 실패' }
if (-not (Test-Path "$testRoot/local.properties")) { throw '새 체크아웃 SDK 설정 생성 실패' }
if (-not (Test-Path "$testRoot/Start-App.ps1")) { throw '앱 실행 진입점 생성 실패' }
# 전용 폴더 없이 복사한 환경에서 일반 앱 기본값과 공용 경로만 사용해야 합니다.
. "$testRoot/.build/Environment.ps1"
$baseSettings = Import-PowerShellDataFile -LiteralPath "$PSScriptRoot/Project.psd1"
if ($PortableProject.BuildTask -ne $baseSettings.BuildTask -or $PortableProject.AvdName -ne $baseSettings.AvdName -or [IO.Path]::GetFullPath($env:ANDROID_AVD_HOME) -ne [IO.Path]::GetFullPath((Join-Path "$testRoot/.build" $baseSettings.AvdDirectory))) { throw '일반 프로젝트 설정 선택 실패' }
Set-Content "$testRoot/.build/Start-App.ps1" 'param([string]$Gpu); [IO.File]::WriteAllText("$PSScriptRoot/launch-args.txt", $Gpu)' -Encoding UTF8
& "$testRoot/Start-App.ps1" -Gpu software
if ((Get-Content "$testRoot/.build/launch-args.txt") -ne 'software') { throw '생성된 앱 실행 진입점 인자 전달 실패' }
Add-Content "$testRoot/local.properties" 'custom.setting=keep' -Encoding UTF8
& "$testRoot/.build/Setup-Portable.ps1" -Emulator | Out-Null
$properties = Get-Content "$testRoot/local.properties"
if ((Get-Content "$testRoot/.build/emulator-option.txt") -ne 'True') { throw '에뮬레이터 설치 옵션 전달 실패' }
if (@($properties | Where-Object { $_ -match '^sdk\.dir=' }).Count -ne 1 -or 'custom.setting=keep' -notin $properties) { throw 'SDK 설정 중복 또는 기존 설정 손실' }
Set-Content "$testRoot/gradlew.bat" '@exit /b 7' -Encoding ASCII
$failed = $false
try { & "$testRoot/.build/Setup-Portable.ps1" | Out-Null }
catch { $failed = $_.Exception.Message -match 'Gradle.*7' }
if (-not $failed -or (Get-Location).Path -ne $originalLocation.Path) { throw 'Gradle 실패 처리 또는 작업 경로 복구 실패' }

$source = Get-Content "$PSScriptRoot/Emulator.ps1" -Encoding UTF8 -Raw
$start = $source.IndexOf('$arguments = @(')
$end = $source.IndexOf('$process = Start-Process', $start)
$options = [scriptblock]::Create($source.Substring($start, $end - $start))
$avdName = 'PortableApi35'
foreach ($ShowWindow in @($false, $true)) {
    foreach ($Gpu in @('auto', 'software')) {
        $DnsServer = '1.1.1.1,8.8.8.8'
        . $options
        if (('-no-window' -in $arguments) -eq $ShowWindow) { throw '창 표시 옵션 실패' }
        if ($arguments[$arguments.IndexOf('-gpu') + 1] -ne $Gpu) { throw 'GPU 옵션 전달 실패' }
        if ($arguments[$arguments.IndexOf('-avd') + 1] -ne $avdName -or $arguments[$arguments.IndexOf('-port') + 1] -ne [string]$PortableProject.EmulatorPort) { throw '기기 설정 전달 실패' }
        if ($arguments[-1] -ne $DnsServer) { throw 'DNS 옵션 전달 실패' }
        if ($ShowWindow -and $windowStyle -ne 'Normal') { throw '창 표시 방식 실패' }
    }
}
$DnsServer = ''
. $options
if ('-dns-server' -in $arguments) { throw '자동 DNS 선택 실패' }

# 실제 부팅 대기 분기에 ADB 응답만 대체하여 성공과 시간 초과를 확인합니다.
$appSource = Get-Content "$PSScriptRoot/Start-App.ps1" -Encoding UTF8 -Raw
$start = $appSource.IndexOf('$deadline = ')
$end = $appSource.IndexOf('$apk = ', $start)
$bootCheck = [scriptblock]::Create($appSource.Substring($start, $end - $start))
$adb = Join-Path $testRoot 'fake-adb.ps1'
$device = 'emulator-5556'
Set-Content $adb '$global:LASTEXITCODE = 0; Write-Output 1' -Encoding UTF8
$TimeoutSeconds = 1
. $bootCheck | Out-Null
if (-not $booted) { throw '앱 실행 전 부팅 완료 확인 실패' }
Set-Content $adb '$global:LASTEXITCODE = 1' -Encoding UTF8
$timedOut = $false
try { . $bootCheck | Out-Null }
catch { $timedOut = $_.Exception.Message -match '시간 초과' }
if (-not $timedOut) { throw '부팅 대기 시간 초과 처리 실패' }
# 같은 공용 환경에 전용 설정을 넣었을 때만 기존 프로젝트 값을 덮어씁니다.
New-Item -ItemType Directory -Path "$testRoot/.build/Seal" -Force | Out-Null
Set-Content "$testRoot/.build/Seal/Project.psd1" "@{ BuildTask=':app:assembleGenericDebug'; ApplicationId='com.junkfood.seal.debug'; AvdDirectory='Seal/avd' }" -Encoding UTF8
. "$testRoot/.build/Environment.ps1"
if ($PortableProject.BuildTask -ne ':app:assembleGenericDebug' -or $PortableProject.ApplicationId -ne 'com.junkfood.seal.debug' -or [IO.Path]::GetFullPath($env:ANDROID_AVD_HOME) -ne [IO.Path]::GetFullPath("$testRoot/.build/Seal/avd")) { throw '전용 설정 덮어쓰기 실패' }
Write-Output '포터블 진입점·SDK 설정·실행 스크립트 생성·GPU/창/DNS·부팅 대기 검사 통과'
Write-Output "검사 파일: $testRoot"
. "$PSScriptRoot/Environment.ps1"
