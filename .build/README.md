# 포터블 Android 개발 환경

다른 Windows x64 Android 프로젝트에 `.build`를 복사할 때 **`Seal/` 폴더만 제외**하면 됩니다. 새 프로젝트에는 자체 소스·Gradle 설정·Wrapper(`gradlew.bat`, `gradle/wrapper/`)가 필요합니다.

## 프로젝트 설정

`Project.psd1`은 일반 Android 앱의 `assembleDebug`·`testDebugUnitTest`를 기본값으로 사용합니다. 새 앱의 패키지명은 `ApplicationId`에 입력하고, 변형·모듈·SDK가 다르면 작업 이름·APK 경로·API 수준·Build Tools 버전을 맞추세요. 동시에 다른 에뮬레이터를 실행하려면 이름과 짝수 포트도 다르게 지정합니다.

Seal 저장소에서는 `Seal/Project.psd1`이 필요한 값만 덮어씁니다. 기존 `.build/Build.ps1`, `.build/Emulator.ps1`, `.build/Start-App.ps1` 명령을 그대로 사용할 수 있습니다.

```powershell
# 도구가 없는 새 환경에서 구성합니다. SDK 라이선스 동의 및 최초 다운로드가 필요합니다.
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Setup-Portable.ps1 -Emulator
# 기본 APK 또는 명시한 Gradle 작업을 실행합니다.
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 -Test
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 -Offline :app:assembleRelease
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Start-App.ps1
```

## 복사 범위

| 항목 | 내용 |
| --- | --- |
| `tools/` | 공용 JDK·Android SDK·ADB·에뮬레이터 도구 |
| `gradle-cache/` | 공유 가능한 Gradle 배포본·의존성 캐시. 새 의존성은 추가 다운로드가 필요합니다. |
| `downloads/` | 도구 설치 아카이브. 도구가 이미 있으면 복사는 선택 사항입니다. |
| `*.ps1`, `Project.psd1` | 공용 설치·환경·빌드·실행·검사 스크립트 |
| `Seal/` | Seal 설정·기기 데이터·로그·검사·이전 작업 보관본. 다른 앱에는 복사하지 않습니다. |

공용 스크립트는 프로젝트 위치를 기준으로 경로를 구성하고 `local.properties`의 SDK 경로를 갱신합니다. 도구·캐시는 Git에 포함하지 않습니다. 기존 도구는 자동으로 최신 버전으로 교체하지 않습니다. 에뮬레이터 실행에는 PC의 가상화 지원이 필요하며, 실제 AI 가속 성능은 실제 기기에서 측정해야 합니다.

## 검사

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Test-BuildOptions.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Test-PortableOptions.ps1
```

Seal 전용 검사와 기존 구성 기록은 [Seal/README.md](Seal/README.md)에 보관합니다.
