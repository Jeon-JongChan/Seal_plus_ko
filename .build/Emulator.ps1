<#
.SYNOPSIS
포터블 AVD를 GPU 자동 선택으로 실행합니다. -ShowWindow로 화면을 표시합니다.
#>
param([string]$DnsServer, [switch]$ShowWindow, [ValidateSet('auto', 'host', 'software', 'swiftshader')][string]$Gpu = 'auto')
. "$PSScriptRoot/Environment.ps1"
$emulator = "$env:ANDROID_HOME/emulator/emulator.exe"
$avdManager = "$env:ANDROID_HOME/cmdline-tools/latest/bin/avdmanager.bat"
$image = 'system-images;android-35;google_apis;x86_64'
if (-not (Test-Path -LiteralPath $emulator) -or -not (Test-Path "$env:ANDROID_HOME/system-images/android-35/google_apis/x86_64/system.img")) {
    throw '.build/Setup.ps1 -Emulator를 먼저 실행하세요.'
}
$ErrorActionPreference = 'Continue'
& $emulator -accel-check 2>&1 | Tee-Object -FilePath "$BuildRoot/logs/acceleration.log" | Select-Object -Last 15
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw '가상화 가속을 사용할 수 없습니다. .build/logs/acceleration.log를 확인하세요.' }
$avdPath = Join-Path $env:ANDROID_AVD_HOME 'SealApi35.avd'
if (-not (Test-Path "$avdPath/config.ini")) {
    $ErrorActionPreference = 'Continue'
    'no' | & $avdManager create avd --name SealApi35 --package $image --path $avdPath 2>&1 | Select-Object -Last 15
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw 'AVD 생성에 실패했습니다.' }
}
# AVD 사용자 데이터는 유지하고 PC 이동으로 달라진 등록 경로만 갱신합니다.
[IO.File]::WriteAllLines((Join-Path $env:ANDROID_AVD_HOME 'SealApi35.ini'), @('avd.ini.encoding=UTF-8', "path=$avdPath", 'target=android-35'), [Text.UTF8Encoding]::new($false))
# 기존 AVD의 GPU 비활성화도 해제합니다. auto는 호스트 환경에 따라 렌더러를 선택합니다.
$configPath = Join-Path $avdPath 'config.ini'
$configLines = @(Get-Content -LiteralPath $configPath | Where-Object { $_ -notmatch '^hw\.gpu\.(enabled|mode)\s*=' })
[IO.File]::WriteAllLines($configPath, $configLines + @('hw.gpu.enabled=yes', "hw.gpu.mode=$Gpu"), [Text.UTF8Encoding]::new($false))
$ErrorActionPreference = 'Continue'
& "$env:ANDROID_HOME/platform-tools/adb.exe" start-server 2>&1 | Select-Object -Last 8
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw 'ADB 시작에 실패했습니다.' }
$running = & "$env:ANDROID_HOME/platform-tools/adb.exe" devices
if ($running -match '^emulator-5556\s+(device|offline)') { Write-Output 'SealApi35가 실행 또는 부팅 중입니다. 변경한 GPU·창 옵션은 종료 후 재실행하면 적용됩니다.'; return }
$arguments = @('-avd', 'SealApi35', '-port', '5556', '-no-audio', '-no-snapshot', '-no-boot-anim', '-gpu', $Gpu)
$windowStyle = 'Hidden'
if ($ShowWindow) { $windowStyle = 'Normal' }
else { $arguments += '-no-window' }
# VPN 등으로 자동 DNS가 실패할 때만 에뮬레이터의 DNS를 별도로 지정합니다.
if ($DnsServer) { $arguments += @('-dns-server', $DnsServer) }
$process = Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle $windowStyle -RedirectStandardOutput "$BuildRoot/logs/emulator.out.log" -RedirectStandardError "$BuildRoot/logs/emulator.err.log" -PassThru
Write-Output "에뮬레이터 시작: PID $($process.Id), GPU $Gpu, adb 장치 emulator-5556"
