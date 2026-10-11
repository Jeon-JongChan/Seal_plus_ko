# 플러그인과 필수 호환 기반

선택 기능은 같은 APK에 빌드되는 내장 플러그인이다. 최초에는 모두 꺼져 있으며 관리 화면에서 하나씩 활성화한다. 필수 호환 플러그인은 항상 활성화되어 있고 사용자가 끌 수 없다.

| 구분 | ID | 담당 기능 | 폴더 |
| --- | --- | --- | --- |
| 필수 | 호환 기반 | 기존 데이터 형식, 파일 검증·저장소·이력, 공통 설정·옵션 UI, 원본과 확장 실행 연결 | `compatibility/`, `runtime/` |
| 선택 | SOCIAL | 게시물 이미지·영상 분석, 혼합 게시물 선택, 인스타그램·틱톡 | `image/`, `instagram/`, `tiktok/` |
| 선택 | LIST | 별도 목록 화면, 계정 조회, 이미지·영상 선택, 전체 다운로드 | `listdownload/` |
| 선택 | MEDIA | 파일 재사용, AV1 변환, 컨테이너 선택 | `media/` |
| 선택 | LIBRARY | 미디어 폴더, 이미지·영상 해상도별 분류, 이력 필터·파일 관리·자체 뷰어 | `library/` |
| 선택 | QUEUE | 대기열 묶음·상세 정보, 선택·삭제, 자체 뷰어 | `queue/` |
| 선택 | INTERACTION | 통합 입력, 메뉴 즐겨찾기, 빠른 쿠키 선택 | `interaction/` |

## 원본 보존

비교 기준은 JunkFood 원본 커밋 `7677f61a20fda4210e84215fef9e9b51d25daa47`이다. 원본 `DownloaderV2Impl`에는 선택 기능 구현·참조를 두지 않는다. 원본 다운로드 설정 창과 탐색 메뉴도 복원하고 앞쪽에서 확장 화면에 전달한다. 추가 설명·주석·README는 원본 폴더에 두지 않는다.

`App`에서 주입하는 `PluginDownloaderV2`는 원본 엔진과 확장 엔진이 같은 작업 저장소·코루틴을 쓰도록 연결한다. 새 요청은 `PluginPolicy`로 제한하고, 실행 경로는 작업 사본의 `socialProcessing`, `listProcessing`, `queueEnhancements`, 미디어 설정 및 기존 소셜 작업 유형으로 결정한다. 스위치 변경 시 객체나 진행 중인 작업의 설정을 교체하지 않는다.

원본 경로에는 설정·작업·상태 모델과 Room 스키마, 데이터 접근 API가 호환 계약으로 남는다. 설정 생성·파일 처리·이력 복구의 확장 구현은 필수 기반에 전달한다. DB 모델까지 이전 버전으로 되돌리거나 타입 이름을 임의 변경하면 기존 데이터를 읽지 못하므로 이를 원본 복원의 대상으로 삼지 않는다.

## 선택 기능의 독립성

선택 플러그인은 다른 선택 플러그인의 클래스나 함수를 직접 참조하지 않는다. 공통 API는 필수 기반을 사용하고 실행 등록은 `runtime/`이 담당한다. `FeaturePlugin`은 메뉴와 화면 진입 규약이며 목록 등록은 조합을 담당하는 진입점이다.

LIST의 조회·다운로드·선택 구현은 자체 사본을 가지므로 SOCIAL을 켜지 않아도 계정과 이미지 목록을 처리한다. LIBRARY와 QUEUE의 뷰어·상세 UI도 자체 구현을 가진다. 공통 파일 엔진·메타데이터·계산 규칙은 필수 기반에서 제공한다. 중복 구현을 수정할 때 의도적으로 양쪽 동작을 바꾸는 경우에만 각각 수정한다.

이 구조는 기능별 실행과 직접 코드 참조의 분리다. 독립 Gradle 모듈·별도 APK·외부 파일 동적 로딩이나 독립 업데이트 구조는 아니다. 기능을 소스에서 제거하려면 조합 진입점의 등록과 호출도 함께 정리해야 한다.

## 검사

```powershell
python -X utf8 .build/Test-PluginIsolation.py
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Test-Localization.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .build/Build.ps1 -Offline -Test :app:assembleGenericDebug :app:assembleGenericDebugAndroidTest
```

`PluginExecutionPolicyTest`는 원본 경로, 개별 실행 경로, 작업 사본 유지, LIST 단독 사용을 검사한다. `SharedDownloaderStateTest`는 네트워크를 실행하지 않고 원본 엔진의 공유 저장소·취소·재시작을 검사한다. `PluginStateTest`는 MMKV와 필수 활성화를, `.build/Test-PluginUi.py`는 변경 불가 호환 항목과 여섯 개 선택 스위치를 검사한다.

번역은 Android 리소스 규칙에 따라 `res/values*/`에 둔다. 소셜 API 접근 제한·로그인 요구, 기기의 인코더·SAF 공급자 차이는 기능 분리만으로 해결되지 않는다.

## 미디어 폴더·페이지 삭제·원본 보존 규칙

폴더 분류는 LIBRARY만 켜도 실행한다. MEDIA는 인코딩·파일 재사용을 담당하며 폴더 설정을 쓰지 않는다. 이미지·영상 구간은 각각 켜고 끄며 꺼진 구간은 더 낮은 구간으로 대체하지 않고 종류별 기본 폴더에 둔다. 구간 설정은 기존 문자열 필드에 비활성 구간을 함께 넣어 등록 작업과 백업의 사본을 유지한다.

LIBRARY와 QUEUE의 뷰어는 삭제 성공 후 넘김 목록에서 해당 파일을 제외한다. 전체 페이지 수는 처음 열었을 때의 수와 이력의 원래 게시물 수 중 큰 값으로 유지한다. 실패한 삭제는 목록을 변경하지 않는다.

Codex는 루트 `AGENTS.md`, Antigravity는 `GEMINI.md`와 `.agent/rules/junkfood-preservation.md`의 항상 적용 규칙을 따른다. `com/junkfood` 소스 수정·추가·삭제·이동·주석·포맷은 금지하며 별도 명시적 허용이 있을 때만 예외를 허용한다.
