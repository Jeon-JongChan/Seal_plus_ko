<#
.SYNOPSIS
코드만 내려받은 Windows x64 프로젝트에 포터블 JDK·Android SDK·Gradle을 설치합니다.
.DESCRIPTION
-Emulator를 지정하면 에뮬레이터와 API 35 이미지도 설치합니다. 재실행 시 기존 도구를 재사용합니다.
SDK 라이선스에 동의하고 설치하며 시스템 환경 변수는 변경하지 않습니다.
#>
param([switch]$Emulator)
& "$PSScriptRoot/.build/Setup-Portable.ps1" @PSBoundParameters
