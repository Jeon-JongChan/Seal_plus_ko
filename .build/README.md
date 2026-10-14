# 포터블 Android 개발 환경

Windows x64용 JDK·Android SDK·Gradle을 프로젝트 안에 준비합니다. 생성 도구·캐시는 Git 업로드에서 제외합니다.

## 프로젝트 설정

`Project.psd1`에서 작업·패키지명·SDK·출력 경로를 지정합니다. 현재 프로젝트는 Generic Debug를 사용합니다.

빌드 시 도구나 RIFE JNI가 없으면 준비 스크립트를 실행합니다. 오프라인에서는 누락 항목을 안내합니다.

다른 앱으로 복사할 때는 `Project.psd1`을 맞추고 프로젝트 전용 `native/`, `test/`를 제외합니다. RIFE 원본 준비에는 Git이 필요합니다.

```powershell
# 도구가 없는 새 환경에서 구성합니다. SDK 라이선스 동의 및 최초 다운로드가 필요합니다.
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Setup-Portable.ps1 -Emulator
# 옵션이 없으면 앱 APK만 생성합니다.
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1
# 단위 검사와 검사 APK 생성은 각각 명시적으로 선택합니다.
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 -Test
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 -TestApk
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 -Offline :app:assembleGenericRelease
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Start-App.ps1
```

## 복사 범위

| 항목 | 내용 |
| --- | --- |
| `tools/` | 공용 JDK·Android SDK·ADB·에뮬레이터 도구 |
| `gradle-cache/` | 공유 가능한 Gradle 배포본·의존성 캐시. 새 의존성은 추가 다운로드가 필요합니다. |
| `downloads/` | 도구 설치 아카이브. 도구가 이미 있으면 복사는 선택 사항입니다. |
| `*.ps1`, `Project.psd1` | 공용 설치·환경·빌드·실행·검사 스크립트 |
| `test/` | 유지할 검사 코드. |
| `native/rife/` | 직접 작성한 JNI 연결·고정 버전 빌드 소스. 업로드 필요. |
| `runtime/`, `cache/` | 생성 JNI·다운로드 SDK. 업로드 제외, 필요 시 재생성. |
| `archive/`, `Seal/` | 과거 수리본·기존 기기 데이터·검사 결과. 로컬 보관, 업로드 제외. |

공용 스크립트는 프로젝트 위치를 기준으로 경로를 구성하고 `local.properties`의 SDK 경로를 갱신합니다. 도구·캐시는 Git에 포함하지 않습니다. 기존 도구는 자동으로 최신 버전으로 교체하지 않습니다. 에뮬레이터 실행에는 PC의 가상화 지원이 필요하며, 실제 AI 가속 성능은 실제 기기에서 측정해야 합니다.

## 검사

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .build/test/Test-BuildOptions.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .build/test/Test-PortableOptions.ps1
```

검사 코드는 `.build/test/`에 둡니다. 기존 로컬 기기·로그는 삭제하지 않고 업로드에서 제외합니다. 사용자 지정 JNI는 다운로드 SDK로 복원할 수 없어 소스 4개를 유지합니다.

일반 빌드는 검사 APK를 자동 추가하지 않습니다. `-Test`는 단위 검사만 실행하고, `-TestApk`는 `Project.psd1`의 `TestApkTask`를 추가해 검사 APK를 생성합니다. 두 옵션은 함께 사용할 수 있으며 검사 APK 생성만으로 기기 검사를 실행하지 않습니다. 직접 지정한 Gradle 검사 작업은 명시적 요청으로 처리합니다. 반복 빌드는 Gradle 데몬과 프로젝트 구성 캐시 설정을 사용하며 출력에는 이번에 갱신된 APK·AAB만 표시합니다.
