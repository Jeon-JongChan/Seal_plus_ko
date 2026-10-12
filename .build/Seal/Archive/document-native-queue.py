"""복제한 플러그인 소스에 한국어 함수 설명을 추가합니다."""

raise SystemExit("이전 작업 보관용입니다. 원본 소스 보호를 위해 실행하지 않습니다.")
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/plugin'
for name in ['runtime/BaselineTaskWorker.kt', 'queue/ActionSheet.kt']:
    path = SRC / name
    value = path.read_text(encoding='utf-8')
    value = value.replace('/** Processes pending tasks, prioritizing downloads. */',
                          '/** 전체 동시 실행 한도 안에서 다운로드 대기 작업을 우선 처리합니다. */')
    value = re.sub(r'/\*\*\s*\n\s*\* Execute a custom command task.*?\*/',
                   '/** 기준 커밋의 사용자 명령 실행 흐름을 제공합니다. */', value, flags=re.S)
    lines = value.splitlines()
    output = []
    for line in lines:
        match = re.match(r'(\s*)(?:(?:private|internal|public)\s+)?fun\s+(?:Task\.|LazyListScope\.)?(\w+)', line)
        if match and (not output or not output[-1].strip().endswith('*/')):
            output.append(match[1] + '/** ' + match[2] + '의 전용 대기열 처리 또는 화면 동작을 제공합니다. */')
        output.append(line)
    path.write_text('\n'.join(output) + '\n', encoding='utf-8', newline='\n')

path = SRC / 'queue/UiAction.kt'
value = path.read_text(encoding='utf-8')
labels = {'OpenFile': '완료 파일을 엽니다.', 'ShareFile': '완료 파일을 공유합니다.',
          'OpenThumbnailURL': '썸네일 주소를 엽니다.', 'CopyVideoURL': '다운로드 주소를 복사합니다.',
          'OpenVideoURL': '영상 주소를 엽니다.', 'Cancel': '실행 중인 작업을 취소합니다.',
          'Delete': '대기열의 작업을 제거합니다.', 'Resume': '중단된 작업을 재개합니다.',
          'CopyErrorReport': '오류 내용을 복사합니다.'}
for name, label in labels.items():
    value = re.sub(r'    (data (?:class|object) ' + name + r'\b)',
                   '    /** ' + label + ' */\n    \\1', value)
path.write_text(value, encoding='utf-8', newline='\n')
