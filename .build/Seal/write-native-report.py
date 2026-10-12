"""관련 작업 보고서에 실제 전용 대기열 연결 결과를 갱신합니다."""
from pathlib import Path
from datetime import datetime
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / '.docs/20261011-0208-플러그인-기본경로-분리-검증.md'
MARKER = '\n## 전용 대기열 런타임 연결 결과 '
existing = REPORT.read_text(encoding='utf-8-sig').split(MARKER)[0]
changed = subprocess.check_output(['git', '-c', 'core.safecrlf=false', 'diff', '7677f61', '--name-only',
    '--', 'app/src/main/java/com/junkfood'], cwd=ROOT).decode('utf-8').splitlines()
results = ROOT / 'app/build/test-results/testGenericDebugUnitTest'
tests = failures = errors = 0
for path in results.glob('TEST-com.plugin*.xml'):
    if path.stat().st_mtime < datetime(2026, 10, 11, 3, 20).timestamp():
        continue
    suite = ET.parse(path).getroot()
    tests += int(suite.get('tests', '0'))
    failures += int(suite.get('failures', '0'))
    errors += int(suite.get('errors', '0'))
validation = f'실행된 검사 {tests}개, 실패 {failures}개, 오류 {errors}개.' if tests else '단위 테스트 실행 결과 확인 중.'
now = datetime.now().strftime('%Y-%m-%d %H:%M KST')
block = f'''{MARKER}({now})

### 구현한 연결

- `PluginTaskEngine`이 `PluginTask`와 상태 맵을 직접 소유합니다. 실행·취소·재개·완료 처리·백업·플러그인 대기열 화면·게시물 묶음 계산·파일 삭제 연동이 전용 모델을 사용합니다.
- `PluginDownloaderV2`는 원본 `DownloaderV2` API를 보존하는 어댑터입니다. 원본 화면과 서비스에는 기본 상태 복사본을 제공하며 작업 조작은 ID로 실제 전용 대기열에 전달합니다. 표시용 복사본에 플러그인 메타데이터를 왕복시켜 저장하지 않습니다.
- `BaselineTaskWorker`는 `7677f61`의 기본 처리 흐름을 전용 작업 모델에서 실행하도록 복제했습니다. 중앙 스케줄러가 선택한 작업만 실행하며, 기본·확장 작업 모두 하나의 동시 실행 한도와 서비스 수명 관리 아래에서 처리합니다.
- 소셜·목록 컨트롤러가 원본 `Task.TypeInfo.Instagram` 대신 전용 `SocialMedia` 작업을 생성합니다. 목록 작업 팩토리도 플러그인 패키지로 분리했습니다.
- 백업 키를 `plugin_task_list_v1`로 분리했습니다. 기존 `task_list`는 삭제하거나 덮어쓰지 않고 최초 이전 시 읽습니다. 타입 식별자만 JSON 구조에서 변환해 제목·URL·작업 ID·원래 페이지 수·삭제 상태·완료 파일 목록·생성 시간을 유지합니다.
- 손상된 백업은 정상적인 빈 대기열로 저장하지 않습니다. 복구 실패를 알리고 기존 백업을 보존합니다. 신규 백업 저장 실패도 앱의 작업 코루틴 밖으로 전파하지 않습니다.
- 생성 시간은 정렬·백업에 보존하되 작업 동등성에서 제외해 같은 요청의 중복 등록을 방지합니다.
- 이전 분리 작업에 남아 있던 객체 와일드카드 import와 옛 설정 키 참조를 명시적 플러그인 import로 수정해 컴파일 오류를 해소했습니다.

### 원본 복원

- `download/Task.kt`, `download/DownloaderV2.kt`, `ui/page/downloadv2/ActionSheet.kt`는 기준 커밋과 **바이트 단위로 동일**합니다.
- `ui/page/downloadv2/DownloadPageV2.kt`는 기준 내용에 플러그인 화면 진입 분기 4줄만 남겼습니다.
- 원본 디렉터리에 추가됐던 대기열 검사 2개는 `app/src/test/java/com/plugin/queue`로 이전했습니다. 원본 디렉터리에 새 구현 파일이나 README를 추가하지 않았습니다.
- 복원 전 소스는 `.build/Seal/plugin-isolation-before/native-queue/`에 보관했습니다. 커밋·푸시는 수행하지 않았습니다.

### 검증

- 포터블 JDK·SDK는 `.build/Environment.ps1`에서 확인했습니다. 이전 보고서의 Java 부재는 당시 PATH 상태이며 현재 검증에서는 이 환경을 사용합니다.
- 전용 모델의 상태 변환, 백업 왕복·기존 백업 이전·손상 백업 거부, 삭제 연동, 게시물의 전체 페이지 수 유지, 기본/확장 실행 정책을 검증합니다. {validation}
- `python .build/Seal/check-native-boundaries.py`: 원본 파일 3개 기준 바이트 일치 및 전용 대기열 경계 8개 검사 통과.
- `git diff --check` 통과.
- 최종 앱·테스트 소스 컴파일 및 선택한 단위 테스트 작업 통과. 명령은 `.build/Environment.ps1` 적용 후 `:app:testGenericDebugUnitTest --tests com.plugin.queue.* --tests com.plugin.QueuePageCountTest --tests com.plugin.PluginExecutionPolicyTest --offline`이며, 전체 로그는 `.build/Seal/logs/plugin-native-tests-final.log`에 보관합니다. 에뮬레이터에서 UI 동작을 검증한 결과는 아닙니다.
- Graphify를 지정된 `.graphify/Seal_plus_ko/graphify-out/` 경로로 갱신했습니다. Kotlin 구문 일부를 인식하지 못하는 추출 경고는 컴파일 결과와 구분합니다.
- 쿠키 인증 요청, 실제 쿠키 데이터 조작, 외부 다운로드, 에뮬레이터에서의 계정 테스트는 수행하지 않았습니다. 검사는 로컬 직렬화·상태·정책 검사입니다.

### 아직 남은 분리

이번 연결로 완전 분리가 끝난 것은 아닙니다. 전용 작업 모델이 아직 원본 `DownloadPreferences` 및 `VideoInfo`/`Format`을 필드 타입으로 사용하고, 다이얼로그 액션·선택 상태도 원본 ViewModel과 공유합니다. 다음 분리 대상은 이 모델과 다이얼로그 처리 흐름이며, Room 호환성 및 최소 진입점은 별도로 유지해야 합니다. 모든 플러그인을 껐을 때 앱 전체가 원본과 동일하다고 아직 보장하지 않습니다.

원본 소스의 기준 커밋 대비 차이는 **23개에서 {len(changed)}개**로 줄었습니다.

''' + '\n'.join('- `' + name.removeprefix('app/src/main/java/com/junkfood/seal/') + '`' for name in changed) + '''

### 현재 플러그인 목록

새 선택 플러그인 ID를 추가하거나 합치지는 않았습니다. 현재 구성은 다음과 같습니다.

| 구분 | ID | 기능 |
|---|---|---|
| 선택 | SOCIAL | 소셜 게시물 이미지·영상 분석과 선택 다운로드 |
| 선택 | LIST | 목록·계정·재생목록 분석과 일괄 다운로드 |
| 선택 | MEDIA | 영상 인코딩·컨테이너·원본 보존·재사용 설정 |
| 선택 | LIBRARY | 미디어 폴더 분류·파일 관리·게시물 열람 |
| 선택 | QUEUE | 확장 대기열·게시물 묶음·삭제 상태·페이지 수 표시 |
| 선택 | INTERACTION | 통합 입력·메뉴 즐겨찾기·빠른 설정 |
| 필수 | CompatibilityPlugin | Room·기존 이력·파일 경로 호환 처리, 항상 활성 |

`PluginTaskEngine`과 기본 복제 작업자는 실행 기반 구성 요소이며 새 사용자 선택 플러그인이 아닙니다.
'''
REPORT.write_text(existing + block, encoding='utf-8', newline='\n')
print(str(REPORT))
