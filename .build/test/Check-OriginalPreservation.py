"""UI 정리 전후의 모든 junkfood 소스가 바뀌지 않았는지 검사합니다."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    """별도 기준 파일 없이 HEAD의 모든 원본 파일과 실제 내용을 비교합니다."""
    names = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', 'HEAD', 'app/src'], cwd=ROOT).decode().splitlines()
    names = [name for name in names if '/com/junkfood/' in name]
    current = {p.relative_to(ROOT).as_posix() for p in (ROOT / 'app/src').rglob('*')
               if p.is_file() and '/com/junkfood/' in p.as_posix()}
    assert current == set(names), '원본 파일 추가·삭제·이동 감지'
    data = subprocess.check_output(['git', 'cat-file', '--batch'], cwd=ROOT,
                                   input=('\n'.join('HEAD:' + name for name in names) + '\n').encode())
    offset = 0
    for name in names:
        end = data.index(b'\n', offset)
        size = int(data[offset:end].split()[-1])
        offset = end + 1
        # Git 체크아웃의 CRLF 변환은 내용 변경과 구분합니다.
        assert (ROOT / name).read_bytes().replace(b'\r\n', b'\n') == data[offset:offset + size].replace(b'\r\n', b'\n'), '원본 변경: ' + name
        offset += size + 1
    print(f'원본 {len(names)}개 파일 내용·경로 보존 확인')


if __name__ == '__main__':
    main()
