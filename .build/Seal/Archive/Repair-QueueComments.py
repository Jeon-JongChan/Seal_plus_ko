"""대기열 소스의 손상된 주석 인코딩만 정리하고 실행 코드는 그대로 유지합니다."""

raise SystemExit("이전 작업 보관용입니다. 원본 소스 보호를 위해 실행하지 않습니다.")
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PATH = ROOT / 'app/src/main/java/com/plugin/queue/DownloadPageV2.kt'


def spans(text):
    """문자열 리터럴을 건너뛰고 Kotlin 주석의 위치를 반환합니다."""
    index = 0
    while index < len(text):
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            index = len(text) if end < 0 else end + 3
        elif text[index] in ('"', "'"):
            quote = text[index]
            index += 1
            while index < len(text):
                if text[index] == '\\': index += 2
                elif text[index] == quote:
                    index += 1
                    break
                else: index += 1
        elif text.startswith('//', index):
            end = text.find('\n', index)
            end = len(text) if end < 0 else end
            yield index, end
            index = end
        elif text.startswith('/*', index):
            start, depth = index, 1
            index += 2
            while index < len(text) and depth:
                if text.startswith('/*', index): depth += 1; index += 2
                elif text.startswith('*/', index): depth -= 1; index += 2
                else: index += 1
            yield start, index
        else: index += 1


if __name__ == '__main__':
    raw = PATH.read_bytes()
    text = raw.decode('utf-8', errors='surrogateescape')
    original_code = text
    replacements = []
    for start, end in spans(text):
        comment = text[start:end]
        original_code = original_code.replace(comment, '')
        if any(0xDC80 <= ord(char) <= 0xDCFF for char in comment) or '\ufffd' in comment or '?\u0095' in comment:
            following = text[end:end+220]
            symbol = re.search(r'(?:fun|class|enum class)\s+(\w+)', following)
            label = symbol.group(1) if symbol else '화면 상태'
            replacement = ('/** ' + label + '의 대기열 표시와 사용자 동작을 처리합니다. */'
                           if comment.startswith('/**') else '// 대기열 상태를 보존하며 화면 동작을 처리합니다.')
            replacements.append((start, end, replacement))
    assert not any(0xDC80 <= ord(char) <= 0xDCFF for char in original_code), '손상된 실행 코드는 자동 수정하지 않습니다.'
    backup = ROOT / '.build/logs/queue-comments-before.bin'
    assert not backup.exists(), '이미 적용한 복구입니다.'
    backup.write_bytes(raw)
    for start, end, replacement in reversed(replacements):
        text = text[:start] + replacement + text[end:]
    PATH.write_text(text, encoding='utf-8', newline='\n')
    print(f'실행 코드 변경 없이 손상 주석 {len(replacements)}개 복구')
