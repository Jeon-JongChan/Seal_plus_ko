"""S25의 별도 검사 앱에서 코덱 선택과 재시작 후 저장 상태를 확인합니다."""
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ADB = ROOT / '.build/tools/android-sdk/platform-tools/adb.exe'
DEVICE = 'R5CY92GGPJR'
PACKAGE = 'com.junkfood.seal.devicecheck'


def adb(*args):
    """지정한 검사 앱·기기에서만 ADB를 실행합니다."""
    return subprocess.check_output([str(ADB), '-s', DEVICE, *args], timeout=30)


def screen():
    """검사 앱의 현재 UI를 저장하고 원본 사용자 앱 화면을 조작하지 않습니다."""
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/seal-plus-devicecheck/ui.xml')
    data = adb('exec-out', 'cat', '/data/local/tmp/seal-plus-devicecheck/ui.xml')
    tree = ET.fromstring(data)
    assert any(node.get('package') == PACKAGE for node in tree.iter('node')), '검사 앱 화면이 아님'
    (ROOT / '.build/logs/revision11-video-codec-ui.xml').write_bytes(data)
    return tree


def find(label):
    """현재 화면의 레이블과 클릭·선택 상태를 가진 부모 노드를 찾습니다."""
    tree = screen()
    parents = {child: parent for parent in tree.iter() for child in parent}
    node = next(node for node in tree.iter('node') if label in (node.get('text'), node.get('content-desc')))
    while node.get('clickable') != 'true':
        node = parents[node]
    return node


def tap(label):
    """검증한 접근성 버튼의 중앙을 누릅니다."""
    node = find(label)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))


def open_settings():
    """새 프로세스에서 이미 활성화한 검사 플러그인의 영상 설정으로 이동합니다."""
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-n', PACKAGE + '/com.plugin.ui.PluginMainActivity')
    tap('내비게이션 드로어 표시')
    tap('AI 동영상 화질개선')


def main():
    """기본 AV1·원본 선택 저장을 검증하고 코덱 선택을 AV1로 복원합니다."""
    open_settings()
    assert find('AV1 우선 · 기본').get('checked') == 'true'
    tap('원본 코덱 유지')
    assert find('원본 코덱 유지').get('checked') == 'true'
    open_settings()
    assert find('원본 코덱 유지').get('checked') == 'true', '재시작 후 선택 소실'
    tap('AV1 우선 · 기본')
    assert find('AV1 우선 · 기본').get('checked') == 'true'
    print('AV1 기본·원본 유지 선택·재시작 저장·AV1 복원 통과', flush=True)


def restore_plugin():
    """검사 때만 켠 영상 플러그인 스위치를 기존 비활성 상태로 복원합니다."""
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-n', PACKAGE + '/com.plugin.ui.PluginMainActivity')
    tap('내비게이션 드로어 표시')
    tap('플러그인 관리')
    for _ in range(12):
        try:
            node = find('AI 동영상 화질개선')
            if node.get('checked') == 'true':
                tap('AI 동영상 화질개선')
            assert find('AI 동영상 화질개선').get('checked') == 'false'
            print('검사 영상 플러그인 비활성 상태 복원 통과', flush=True)
            return
        except StopIteration:
            adb('shell', 'input', 'swipe', '360', '1400', '360', '400', '350')
    raise AssertionError('검사 플러그인 복원 항목 없음')


if __name__ == '__main__':
    restore_plugin() if '--restore-plugin' in sys.argv else main()
