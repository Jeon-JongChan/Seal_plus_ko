"""APK의 ARM64 ELF 정렬을 실제 NDK 검사 도구로 검증합니다."""
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    """ZIP 자료 컨테이너와 실행 ELF를 구분하며 16KB 미만 LOAD 정렬을 거부합니다."""
    tool = ROOT / '.build/tools/android-sdk/ndk/29.0.13846066/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
    assert tool.is_file(), 'RIFE 빌드용 NDK를 먼저 준비하세요.'
    checked = 0
    containers = 0
    failures = []
    with zipfile.ZipFile(sys.argv[1]) as apk, tempfile.TemporaryDirectory(dir=ROOT / '.build/tmp') as directory:
        for name in apk.namelist():
            if not name.startswith('lib/arm64-v8a/') or not name.endswith('.so'):
                continue
            content = apk.read(name)
            if content[:4] != b'\x7fELF':
                assert name.endswith('.zip.so') and content[:2] == b'PK', '예상하지 못한 비 ELF 라이브러리: ' + name
                containers += 1
                continue
            file = Path(directory) / Path(name).name
            file.write_bytes(content)
            output = subprocess.check_output([str(tool), '--program-headers', '--wide', str(file)], text=True)
            loads = [line.split() for line in output.splitlines() if line.strip().startswith('LOAD ')]
            if not loads or any(int(line[-1], 0) < 16384 for line in loads):
                failures.append(name)
            checked += 1
    assert not failures, '16KB 정렬 미지원: ' + ', '.join(failures)
    print(f'ARM64 ELF {checked}개 16KB 정렬 통과, ZIP 자료 컨테이너 {containers}개 구분')


if __name__ == '__main__':
    main()
