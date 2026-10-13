# Seal 전용 설정·검사·작업 기록

공용 개발 도구 안내는 [상위 README](../README.md)에 있습니다. 이 폴더를 제외하면 다른 앱에서 사용하는 공용 구성만 복사할 수 있습니다.

- `Project.psd1`: Generic Debug 작업, 패키지명, 전용 로그·임시 파일·Android 설정·AVD 경로.
- `Test-*.py`, `Test-Localization.ps1`, `Check-OriginalPreservation.py`, `check-native-boundaries.py`: Seal 전용 검사. Python 검사는 프로젝트 루트에서 `python -X utf8 .build/Seal/파일명.py`로 실행합니다.
- `Archive/`: 실행을 차단한 과거 수리·이동 스크립트와 Kotlin 보관본.
- `logs/`, `validation/`, `tmp/`, `avd/`, `android-user/`, `gradle-output/`, `plugin-isolation-before/`, `relocation-check/`: 기존 데이터와 검증 기록을 삭제 없이 이동한 보관 위치.

다음 내용은 기존 구성 기록입니다. 과거 보고서의 경로는 변경 당시의 경로이며, 현재 전용 데이터는 이 폴더에 있습니다.

---

# Windows 포터블 빌드·테스트

Windows x64용 JDK 21, Android SDK 35, Gradle 8.13와 캐시를 이 폴더에 둡니다. Python이나 Android Studio 설치 없이 PowerShell과 프로젝트의 Gradle Wrapper를 사용합니다. 모든 경로는 실행할 때 프로젝트 위치에서 다시 계산합니다.

프로젝트 루트에서 실행합니다.

```powershell
# 처음 준비하거나 빠진 도구를 설치합니다.
powershell -NoProfile -ExecutionPolicy Bypass -File Setup-Portable.ps1

# 기본: 디버그 APK만 빌드
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1

# 단위 테스트도 실행하려면 -Test 추가
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 -Test

# 캐시가 준비된 경우 인터넷 없이 빌드
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 -Offline

# 에뮬레이터와 API 35 x86_64 시스템 이미지 준비
powershell -NoProfile -ExecutionPolicy Bypass -File Setup-Portable.ps1 -Emulator
powershell -NoProfile -ExecutionPolicy Bypass -File Start-App.ps1

# VPN 환경에서 자동 DNS가 실패하면 해당 환경에서 접근 가능한 DNS 서버를 지정합니다.
# 실행 중인 에뮬레이터를 아래 종료 명령으로 종료한 다음 실행하세요.
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Emulator.ps1 -DnsServer 1.1.1.1,8.8.8.8

# 부팅된 에뮬레이터 또는 연결된 기기에서 Android 테스트
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 :app:connectedGenericDebugAndroidTest
```

다른 PC로 옮길 때 저장소 전체와 `.build/tools/`, `.build/gradle-cache/`, `.build/Seal/android-user/`를 함께 복사하세요. 숨김 폴더를 제외하지 않아야 합니다. 경로가 바뀌어도 스크립트가 JDK·SDK·캐시 경로와 기존 `local.properties`의 SDK 경로를 현재 위치에 맞춥니다. `.build/Seal/android-user/`에는 같은 디버그 서명을 유지하는 테스트용 키도 있습니다. 반복 빌드는 Gradle 데몬과 프로젝트의 구성 캐시 설정을 재사용합니다. Gradle 캐시는 필요한 빌드 작업을 한 번 실행해 내려받은 의존성 범위에서 오프라인으로 사용할 수 있습니다. 검사 APK는 기본 작업에 추가하지 않으며 `-TestApk`로 요청합니다. `-Test`는 단위 검사만 선택합니다.

| 경로 | 용도 |
| --- | --- |
| `tools/jdk/` | Azul JDK 21.0.12.1 |
| `tools/android-sdk/` | SDK·CLI·ADB·에뮬레이터·시스템 이미지 |
| `gradle-output/` | 루트 Gradle 보고서·출력 (기존 루트 build 통합) |
| `gradle-cache/` | Gradle 배포판·의존성 캐시 |
| `downloads/` | 체크섬 검증한 설치 압축파일 |
| `android-user/`, `avd/` | 포터블 Android 설정·테스트 기기 데이터 |
| `logs/` | 설치·빌드·실행 로그 |
| `tmp/` | 현재 빌드 프로세스의 임시 파일 |

Git에는 `Setup-Portable.ps1`과 `.build/`의 실행 스크립트·검사 스크립트·README를 포함합니다. 도구·캐시·다운로드·AVD·로그는 제외합니다. GitHub 코드만 내려받은 경우 위 설치 명령으로 다시 구성합니다. 최초 설치에는 인터넷과 SDK 라이선스 동의가 필요하며, APK 의존성은 첫 빌드에서 내려받습니다. 실행 정책 우회는 해당 PowerShell 프로세스에만 적용됩니다.

에뮬레이터 가속은 PC에 Windows Hypervisor Platform과 펌웨어 가상화가 필요합니다. 이를 사용할 수 없으면 빌드·단위 테스트는 실행할 수 있고, Android 실행 테스트는 USB 디버깅을 허용한 기기 또는 가상화가 가능한 PC에서 수행하세요. 에뮬레이터 종료는 `.build/tools/android-sdk/platform-tools/adb.exe -s emulator-5556 emu kill`입니다.

공식 자료: [Android CLI 다운로드](https://developer.android.com/studio#command-tools), [sdkmanager](https://developer.android.com/tools/sdkmanager), [에뮬레이터 가속](https://developer.android.com/studio/run/emulator-acceleration).

공백이 있는 폴더에서도 빌드하도록 KSP2와 Kotlin 2.0에 대응하는 Room 2.7.2를 사용합니다. 기존 데이터베이스 버전과 마이그레이션 스키마는 유지하며 스키마 경로는 KSP 키·값 옵션으로 전달하고 Gradle 빌드 입력으로 추적합니다. [Room 공식 변경 기록](https://developer.android.com/jetpack/androidx/releases/room#version_27)

## 현재 추가·변경 사항 (2026-10-09)

### 앱 기능과 의존성

빠른 다운로드의 쿠키 프로필 선택, 미디어 파일 관리·비트레이트 분류(기본 4Mbps), 하위 폴더 이동 후 중복 재탐색, Instagram 이미지·계정 목록·혼합 캐러셀 선택, 선택 시 AV1 변환 후 원본 대체를 추가했습니다. AV1·폴더 분류는 기본 비활성화이며 쿠키도 사용자가 켠 경우에만 적용합니다. AV1은 libsvtav1 preset 8·기본 CRF 32를 사용합니다. 분류는 체감 화질이나 AI 평가가 아닙니다.

AGP 8.13.2, Room 2.7.2·KSP2, OkHttp 정식판 4.12.0, Coil 2.7.0, youtubedl-android 관련 래퍼 0.18.1 등으로 조정했습니다. Kotlin 2.4.21·Gradle 8.13·KSP 2.3.12를 사용하며 R8은 9.1.29로 연결합니다. 앱 DB 버전 5는 유지했습니다. 상세 표는 [프로젝트 README](../../README.md)에 있습니다.

앱의 FFmpeg·yt-dlp·aria2c는 SDK 도구가 아니라 Gradle이 받는 Android 의존성입니다. AAR은 `gradle-cache/caches/modules-2/files-2.1/`에 보관되고 APK에 포함된 Android 네이티브 파일을 기기 내부에서 실행합니다. 확인한 번들은 FFmpeg 7.1.1·aria2c 1.37.0·yt-dlp 2025.11.12이며 래퍼 0.18.1과 버전 체계가 다릅니다. yt-dlp 업데이트 기능은 FFmpeg 번들 교체 기능이 아닙니다.

### 포터블 스크립트 동작

| 파일 | 이번 구성에서 담당하는 역할 |
| --- | --- |
| `Setup.ps1` | 공식 JDK·CLI ZIP을 고정 SHA-256으로 검증하고 설치. SDK 패키지·라이선스 준비. `-Emulator`로 시스템 이미지 추가. |
| `Environment.ps1` | 현재 프로젝트 기준으로 프로세스 환경·캐시·AVD·임시 경로 설정. local.properties를 생성하거나 기존 sdk.dir만 갱신. |
| `Build.ps1` | 기본 Generic Debug APK만 생성. `-Test`로 Generic Debug 단위 테스트 추가. 임의 Gradle 작업·오프라인 실행 지원. worker 2개·Kotlin in-process·configuration cache 비활성화. |
| `Emulator.ps1` | WHPX 검사·SealApi35 AVD 생성·이동 경로 갱신. 포트 5556에서 GPU 자동 선택. -ShowWindow로 창 표시, 선택적 DNS 지정. |

기존 JDK·CLI가 있으면 초기 설치를 건너뛰며 매번 최신 도구로 자동 교체하지 않습니다. SDK 패키지 설치에는 네트워크가 필요할 수 있습니다. `--no-daemon`은 상주 데몬을 재사용하지 않는 설정이며 JVM 옵션에 따라 빌드 종료 시 종료되는 일회용 Gradle 데몬은 생성될 수 있습니다.

`-Test`는 `-Offline`과 함께 사용할 수 있습니다. 직접 Gradle 작업을 지정해도 `-Test`를 붙이면 Generic Debug 단위 테스트를 먼저 추가하며, 같은 작업이 이미 있으면 중복 추가하지 않습니다. Android 기기 테스트는 이 옵션에 포함되지 않고 `:app:connectedGenericDebugAndroidTest`로 별도 실행합니다.

작업 선택 분기만 빠르게 검사하려면 `powershell -NoProfile -ExecutionPolicy Bypass -File .build/Test-BuildOptions.ps1`을 실행합니다. Gradle 없이 기본·옵션·직접 작업·중복 방지의 네 경우를 확인합니다.

### 부팅 확인·APK 설치·화면 실행

Emulator.ps1의 실행 성공은 부팅 완료를 뜻하지 않습니다. 다음 결과가 `1`인지 확인한 뒤 설치합니다. `wait-for-device`는 ADB 연결만 기다립니다.

```powershell
$adb = Join-Path $PWD '.build/tools/android-sdk/platform-tools/adb.exe'
& $adb -s emulator-5556 wait-for-device
& $adb -s emulator-5556 shell getprop sys.boot_completed

# 먼저 Generic Debug 빌드가 성공해야 합니다.
$apk = Get-ChildItem app/build/outputs/apk/generic/debug -Filter '*x86_64*.apk' | Select-Object -First 1
& $adb -s emulator-5556 install -r $apk.FullName
& $adb -s emulator-5556 shell am start -n com.junkfood.seal.debug/com.junkfood.seal.MainActivity
```

화면 조작이 필요하면 기존 AVD를 정상 종료한 뒤 `-ShowWindow`로 실행합니다. 같은 AVD를 동시에 실행하지 않습니다.

```powershell
.build/tools/android-sdk/platform-tools/adb.exe -s emulator-5556 emu kill
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Emulator.ps1 -ShowWindow
```

직접 도구를 실행할 때는 위처럼 Environment.ps1을 dot-source해야 현재 터미널에 환경이 적용됩니다. Android Studio에서는 SDK 위치를 `.build/tools/android-sdk`, Gradle JDK를 `.build/tools/jdk`로 선택하고 프로젝트 Wrapper를 사용합니다. 일반 아이콘으로 실행한 IDE는 이 PowerShell 환경을 자동 상속하지 않습니다. CLI로 부팅한 기기를 IDE의 실행 대상으로 사용할 수 있습니다.

### 검증 결과와 로그

| 검증 | 결과·로그 |
| --- | --- |
| 단위 테스트와 APK·테스트 APK 빌드 | 단위 6개 통과, `logs/portable-build.log` |
| API 35 x86_64 Android 테스트 | 2개 통과, `logs/connected-test.log` |
| 실제 FFmpeg AV1·원본 대체·분류·이동 후 중복 | 통과, `logs/android-native-test.log` |
| 실제 yt-dlp 로컬 HTTP 다운로드 | 4,372,373 byte 원본과 SHA-256 일치, `logs/android-download-local.log` |
| 공백 경로 오프라인 테스트·APK 빌드 | 통과, `logs/relocation-offline-build.log` |

외부 HTTPS는 에뮬레이터 DNS 오류로 실패했습니다. 실사이트 YouTube·Instagram, 로그인 쿠키·대규모 목록·SAF 공급자·ARM 기기·16KB 환경은 미검증이며 ARM64 libwebp의 16KB 호환 문제도 남아 있습니다. 이동 검증은 같은 PC에서 별도 소스를 빌드하고 도구·캐시는 junction으로 재사용했습니다. 다른 물리 PC의 복사 검증은 수행하지 않았습니다.

전체 도구·캐시를 복사할 때 실행 중인 에뮬레이터도 종료해야 합니다. 기존 AVD 상태를 유지하려면 `avd/`도 복사합니다. 릴리스 서명 키는 포함하지 않았습니다. `logs/build.log`는 다음 빌드에서 덮어쓰므로 보존할 기록은 별도로 복사합니다.

로컬 상세 문서: [다크모드 HTML 보고서](../../.docs/20261009-0419-portable-build-and-code-report.html), [누적 변경·검증 기록](../../.docs/20261009-0157-dependency-and-link-improvements.md). `.docs/`는 Git 제외 경로이므로 clone만 한 폴더에는 이 보고서가 없을 수 있습니다.


GPU 기본값은 `auto`입니다. GPU를 활성화하고 호스트 환경에 따라 하드웨어 또는 소프트웨어 렌더러를 선택합니다. 강제 GPU 사용은 `-Gpu host`, 드라이버 문제가 있으면 `-Gpu software`로 실행하세요. 이미 실행 중이면 종료 후 재실행해야 옵션이 적용됩니다. [Android 공식 GPU 가속 안내](https://developer.android.com/studio/run/emulator-acceleration)

설치 진입점·SDK 설정 보존·Gradle 실패 처리·GPU/창 옵션 검사: `powershell -NoProfile -ExecutionPolicy Bypass -File .build/Test-PortableOptions.ps1`. 다운로드를 대체한 검사이며 새 PC의 실제 다운로드 검증은 별도입니다.


### 에뮬레이터와 앱 자동 실행

`Setup-Portable.ps1`은 프로젝트 루트에 `Start-App.ps1`을 생성합니다. 에뮬레이터를 설치한 뒤 다음 한 명령으로 창을 열고 Android 부팅 완료를 기다린 후 앱을 실행합니다. x86_64 Debug APK가 있으면 앱 데이터를 유지하며 업데이트 설치하고, APK가 없으면 이미 설치된 앱을 실행합니다. 앱과 APK가 모두 없으면 먼저 `.build/Build.ps1`로 빌드하세요.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File Start-App.ps1
# GPU·DNS 지정 및 부팅 대기 시간 변경
powershell -NoProfile -ExecutionPolicy Bypass -File Start-App.ps1 -Gpu auto -TimeoutSeconds 300
```

실행 본체 `.build/Start-App.ps1`은 Git에 포함합니다. 루트 실행 스크립트는 설치할 때 재생성하며 Git에서 제외합니다. 이미 실행 중인 에뮬레이터는 재사용합니다. 창 없이 실행 중이면 종료 후 이 명령을 실행해야 창이 표시됩니다.

루트 Gradle 출력은 `.build/Seal/gradle-output/`에 저장합니다. `clean`의 루트 삭제 범위는 이 출력 폴더로 제한되어 포터블 도구·캐시·AVD는 유지됩니다. `app/build/`, `color/build/`, `buildSrc/build/`는 각 모듈의 기존 출력 경로를 유지합니다.


번역을 추가한 뒤 `powershell -NoProfile -ExecutionPolicy Bypass -File .build/Seal/Test-Localization.ps1`로 영어·한국어·스페인어의 기능별 문자열 파일을 검사합니다. [내장 플러그인·번역 파일 관리 안내](../app/src/main/java/com/junkfood/seal/feature/README.md)
