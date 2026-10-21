<# .SYNOPSIS 공식 두 모델 다운로드·무결성과 가속 실패 대체를 검증 에뮬레이터에서 확인합니다. #>
. "$PSScriptRoot/Environment.ps1"
Push-Location $ProjectRoot
try {
    $ErrorActionPreference = 'Continue'
    & "$ProjectRoot/gradlew.bat" ':app:assembleGenericDebug' '--offline' '--no-daemon' '--no-configuration-cache' '--console=plain' '--max-workers=2' '-Pkotlin.compiler.execution.strategy=in-process' '-PnoSplits' '-PABI_FILTERS=arm64-v8a;x86_64' 2>&1 |
        Tee-Object -FilePath "$LogRoot/duplicate-acceleration-build.log" | Select-Object -Last 20
    if ($LASTEXITCODE -ne 0) { throw '중복 가속 검사 빌드 실패' }
    $adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
    $device = "emulator-$($PortableProject.EmulatorPort)"
    # 빌드 중 검증 장치가 종료되어도 설치 직전에 준비하며 실제 사용자 기기는 사용하지 않습니다.
    $connected = & $adb devices
    if (($connected -join "`n") -notmatch "(?m)^$([regex]::Escape($device))\s+device$") { & "$PSScriptRoot/Emulator.ps1" -Gpu software }
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $booted = & $adb -s $device shell getprop sys.boot_completed 2>$null
        if ($LASTEXITCODE -eq 0 -and "$booted".Trim() -eq '1') { $ready = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw '검증 에뮬레이터 부팅 시간 초과' }
    $apk = Get-ChildItem -LiteralPath "$ProjectRoot/app/build/outputs/apk/generic/debug" -Filter '*.apk' | Select-Object -First 1
    & $adb -s $device install -r $apk.FullName
    if ($LASTEXITCODE -ne 0) { throw '검증 앱 설치 실패' }
    $checkOutput = & $adb -s $device shell am instrument -r -w "$($PortableProject.ApplicationId)/com.plugin.duplicates.DuplicateAccelerationCheck" 2>&1
    $checkOutput | Tee-Object -FilePath "$LogRoot/duplicate-acceleration.log" | Select-Object -Last 20
    if ($LASTEXITCODE -ne 0 -or ($checkOutput -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1') { throw '실제 모델 가속 검사 실패' }
} finally { Pop-Location }
