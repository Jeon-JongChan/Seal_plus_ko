"""UI 정리 전후의 모든 junkfood 소스가 바뀌지 않았는지 검사합니다."""
from pathlib import Path
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[2]
SNAPSHOT = ROOT / '.build/Seal/logs/plugin-ui-original-hashes.json'


def hashes():
    """모든 소스 종류에서 원본 패키지 아래 파일의 경로와 해시를 수집합니다."""
    return {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in (ROOT / 'app/src').rglob('*')
            if p.is_file() and '/com/junkfood/' in p.as_posix()}


if __name__ == '__main__':
    current = hashes()
    if '--save' in sys.argv:
        assert not SNAPSHOT.exists(), '기존 검증 기준을 덮어쓰지 않습니다.'
        SNAPSHOT.write_text(json.dumps(current, indent=2), encoding='utf-8')
        print(f'원본 {len(current)}개 파일 검증 기준 저장')
    else:
        previous = json.loads(SNAPSHOT.read_text(encoding='utf-8'))
        changed = [p for p in current.keys() | previous.keys() if current.get(p) != previous.get(p)]
        assert not changed, '원본 변경 감지: ' + ', '.join(changed)
        print(f'원본 {len(current)}개 파일 내용·경로 보존 확인')
