# 포터블 Android 개발 환경

Windows x64용 JDK·Android SDK·Gradle을 프로젝트 안에 준비합니다. 생성 도구·캐시는 Git 업로드에서 제외합니다.

## 프로젝트 설정

`Project.psd1`에서 작업·패키지명·SDK·출력 경로를 지정합니다. 현재 프로젝트는 Generic Debug를 사용합니다.

빌드 시 도구나 RIFE JNI가 없으면 준비 스크립트를 실행합니다. 오프라인에서는 누락 항목을 안내합니다.

빌드는 `--no-daemon`으로 실행해 다른 권한으로 시작된 데몬을 재사용하지 않습니다. 의존성·구성 캐시는 그대로 사용합니다.

다른 앱으로 복사할 때는 `Project.psd1`을 맞추고 프로젝트 전용 `native/`를 제외합니다. RIFE 원본 준비에는 Git이 필요합니다.

```powershell
# 도구가 없는 새 환경에서 구성합니다. SDK 라이선스 동의 및 최초 다운로드가 필요합니다.
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Setup-Portable.ps1 -Emulator
# 옵션이 없으면 앱 APK만 생성합니다.
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 -Offline :app:assembleGenericRelease
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Start-App.ps1
```

## 복사 범위

| 항목 | 내용 |
| --- | --- |
| `tools/` | 공용 JDK·Android SDK·ADB·에뮬레이터 도구 |
| `gradle-cache/` | 공유 가능한 Gradle 배포본·의존성 캐시. 새 의존성은 추가 다운로드가 필요합니다. |
| `downloads/` | 도구 설치 아카이브. 도구가 이미 있으면 복사는 선택 사항입니다. |
| `*.ps1`, `Project.psd1` | 공용 설치·환경·빌드·실행 스크립트 |
| `native/rife/` | 직접 작성한 JNI 연결·고정 버전 빌드 소스. 업로드 필요. |
| `runtime/`, `cache/` | 생성 JNI·다운로드 SDK. 업로드 제외, 필요 시 재생성. |

공용 스크립트는 프로젝트 위치를 기준으로 경로를 구성하고 `local.properties`의 SDK 경로를 갱신합니다. 도구·캐시는 Git에 포함하지 않습니다. 기존 도구는 자동으로 최신 버전으로 교체하지 않습니다. 에뮬레이터 실행에는 PC의 가상화 지원이 필요하며, 실제 AI 가속 성능은 실제 기기에서 측정해야 합니다.

사용자 지정 JNI는 다운로드 SDK로 복원할 수 없어 소스 4개를 유지합니다. 반복 빌드는 Gradle 데몬과 프로젝트 구성 캐시 설정을 사용하며 출력에는 이번에 갱신된 APK·AAB만 표시합니다.

## 최적화 회귀 검사

로컬 테스트·검증 프로그램과 입력 자료는 Git에서 제외하는 `.build/.test/`에 보관합니다. 이 폴더는 새 체크아웃에 포함되지 않습니다. 기존 JunkFood 원본 테스트는 보존합니다.

Android 검증 진입점·예제 미디어는 일반 APK에서 제외합니다. 로컬 검증 APK가 필요하면 `.build/Build.ps1 -Offline :app:assembleGenericDebug -PlocalChecks`로 빌드합니다. 아래 검증 스크립트는 해당 옵션을 자동 지정합니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .build/.test/Check-Optimizations.ps1
```

앱의 실제 JVM 클래스에서 정렬 순서·키 조회 수, 게시물 집계·재시도·AI 복제·삭제 수, 진행률·취소·해시 계약, 중복 판정·보존 우선순위·BK 인덱스를 검사합니다. 별도 테스트 프레임워크를 설치하지 않으며 Android UI·실기기 성능 검사를 대신하지 않습니다.

디버그 APK를 에뮬레이터에 설치한 뒤 실제 모델·디코더·삭제 경계도 검사할 수 있습니다. 앱 소유 외부 폴더 아래 `duplicate-check/duplicate-check-*`에 검사 자료를 생성하고 그 자료만 조작하므로 모든 파일 접근 권한을 부여할 필요가 없습니다. 검사 중 저장 루트는 이 폴더로 바꾸고 기본 실행은 완료 후 복원합니다. `keepUi=true`는 후보·필터 화면 확인용 자료와 설정을 보존합니다.

```powershell
adb -s emulator-5556 shell am instrument -w -e keepUi true com.junkfood.seal.debug/com.plugin.duplicates.DuplicateCheck
adb -s emulator-5556 shell am instrument -w -e restoreSettings true com.junkfood.seal.debug/com.plugin.duplicates.DuplicateCheck
```

첫 명령은 UI 확인용 실행이며 확인이 끝나면 두 번째 명령으로 저장 루트·기능 스위치를 복원하고 생성한 검사 대기열·이력만 정리합니다. 파일은 앱 소유 검사 폴더에 남습니다. 기존 제외 기록이 있는 기기에서는 검사를 중단합니다.

검사는 MobileNetV3 Small/Large, 이미지 재압축·영상 해상도 변형, 다른 이미지·길이 오탐 방어, 캐시 복원·제외 기록, 변경된 삭제 대상 거절, 승인 삭제와 실패 시 이력 보존을 실행합니다. 이 검사 진입점은 릴리스 APK에 포함되지 않습니다.

중복 비교 가속 옵션과 두 모델의 LiteRT 전처리 동등성·실행 실패 대체는 다음 명령으로 확인합니다. 디버그 APK를 빌드한 뒤 지정된 검증 에뮬레이터를 확인하고 필요하면 시작합니다. 실제 사용자 기기는 사용하지 않으며 NPU 런타임은 다운로드하지 않습니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .build/.test/Check-DuplicateAcceleration.ps1
```
