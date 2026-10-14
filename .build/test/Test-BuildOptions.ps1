<#
.SYNOPSIS
Gradle을 실행하지 않고 Build.ps1의 실제 작업 선택 분기를 검사합니다.
#>
$ErrorActionPreference = 'Stop'
$selectionLines = Get-Content "$PSScriptRoot/../Build.ps1" -Encoding UTF8 |
    Where-Object { $_ -match '^if \((-not \$Tasks|\$Test)' }
if ($selectionLines.Count -ne 3) { throw '검사할 작업 선택 분기가 변경되었습니다.' }
$selection = [scriptblock]::Create($selectionLines -join "`n")
foreach ($profile in @(
    @{ BuildTask = ':app:assembleDebug'; TestTask = ':app:testDebugUnitTest'; TestApkTask = ':app:assembleDebugAndroidTest' },
    @{ BuildTask = ':app:assembleGenericDebug'; TestTask = ':app:testGenericDebugUnitTest'; TestApkTask = ':app:assembleGenericDebugAndroidTest' }
)) {
    $PortableProject = $profile
    $cases = @(
        @{ Tasks = $null; Test = $false; Expected = $profile.BuildTask },
        @{ Tasks = $null; Test = $true; Expected = "$($profile.TestTask),$($profile.BuildTask)" },
        @{ Tasks = @(':app:assembleRelease'); Test = $true; Expected = "$($profile.TestTask),:app:assembleRelease" },
        @{ Tasks = @($profile.TestTask); Test = $true; Expected = $profile.TestTask },
        @{ Tasks = $null; Test = $false; TestApk = $true; Expected = "$($profile.BuildTask),$($profile.TestApkTask)" },
        @{ Tasks = $null; Test = $true; TestApk = $true; Expected = "$($profile.TestTask),$($profile.BuildTask),$($profile.TestApkTask)" },
        @{ Tasks = @($profile.TestApkTask); Test = $false; TestApk = $true; Expected = $profile.TestApkTask }
    )
    foreach ($case in $cases) {
    $Tasks = $case.Tasks
    $Test = $case.Test
    $TestApk = [bool]$case.TestApk
    # 원본 스크립트의 분기를 현재 범위에서 실행하여 결과를 확인합니다.
    . $selection
    if (($Tasks -join ',') -ne $case.Expected) { throw "작업 선택 실패: $($Tasks -join ',')" }
    }
}
Write-Output '일반 앱·현재 프로젝트의 작업 선택 14개 검사 통과'
