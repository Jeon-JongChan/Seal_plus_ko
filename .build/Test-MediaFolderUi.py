"""LIBRARY 단독 화면의 이미지·영상 해상도 선택을 검사하고 설정을 복원합니다."""

from pathlib import Path
import runpy
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UI = runpy.run_path(str(ROOT / ".build/Test-PluginUi.py"))
adb, screen, tap = UI["adb"], UI["screen"], UI["tap"]


def menu(title):
    """홈의 탐색 메뉴에서 지정 화면을 엽니다."""
    tree = screen("folder-menu-home")
    tap(next(n for n in tree.iter("node") if n.get("content-desc") == "내비게이션 드로어 표시"))
    tree = screen("folder-menu-open")
    tap(next(n for n in tree.iter("node") if n.get("text") == title))
    expected = "플러그인" if title == "플러그인 관리" else title
    for attempt in range(3):
        tree = screen(f"folder-destination-{attempt}")
        if any(n.get("text") == expected and center_y(n) < 100 for n in tree.iter("node")):
            return
    raise AssertionError(f"화면 이동 실패: {title}")


def top():
    """스크롤 화면을 처음으로 되돌립니다."""
    for _ in range(8):
        adb("shell", "input", "swipe", "160", "160", "160", "580", "200")


def center_y(node):
    """분리된 텍스트와 스위치를 같은 행의 화면 좌표로 연결합니다."""
    bounds = list(map(int, re.findall(r"\d+", node.attrib["bounds"])))
    return (bounds[1] + bounds[3]) / 2


def toggle(title, name):
    """한 행에 제목과 스위치가 함께 있는 접근성 노드를 찾아 반환합니다."""
    top()
    for attempt in range(24):
        tree = screen(f"folder-{name}-{attempt}")
        switches = [n for n in tree.iter("node") if n.get("checkable") == "true"]
        for label in tree.iter("node"):
            if label.get("text") != title:
                continue
            candidates = [n for n in switches if abs(center_y(n) - center_y(label)) <= 36
                          and 120 <= center_y(n) <= 580]
            if candidates:
                return min(candidates, key=lambda n: abs(center_y(n) - center_y(label)))
        adb("shell", "input", "swipe", "160", "500", "160", "300", "500")
    raise AssertionError(f"분류 스위치 없음: {title}")


def main():
    """인코딩을 켜지 않고 분류 화면과 개별 구간을 검사하며 파일은 변경하지 않습니다."""
    resources = {}
    for p in (ROOT / "app/src/main/res/values-ko").glob("*.xml"):
        resources.update({n.attrib["name"]: n.text for n in ET.parse(p).getroot() if n.tag == "string"})
    menu("플러그인 관리")
    encoding = UI["switch"](resources["loc_media_tools"], "folder-encoding-off")
    encoding_was_enabled = encoding.get("checked") == "true"
    if encoding_was_enabled:
        tap(encoding)
    top()
    library = UI["switch"](resources["plugin_library"], "folder-enable")
    library_was_enabled = library.get("checked") == "true"
    if not library_was_enabled:
        tap(library)
    adb("shell", "input", "keyevent", "4")
    menu(resources["plugin_library"])
    classification = toggle(resources["loc_classify_downloads"], "classification")
    original = classification.get("checked") == "true"
    if not original:
        tap(classification)
    try:
        top()
        tree = screen("folder-before-manager")
        tap(next(n for n in tree.iter("node") if n.get("text") == resources["loc_media_file_manager"]))
        tree = screen("folder-file-manager")
        assert any(n.get("text") == resources["loc_media_file_manager"] for n in tree.iter("node"))
        (ROOT / ".build/logs/ui-review-file-manager.png").write_bytes(adb("exec-out", "screencap", "-p"))
        adb("shell", "input", "keyevent", "4")
        print("파일 관리 대화상자 열기·닫기 통과 (파일 변경 없음)", flush=True)
        for title in ("1080x1920", "1080p"):
            node = toggle(title, title)
            previous = node.get("checked")
            tap(node)
            try:
                assert toggle(title, title + "-changed").get("checked") != previous
            finally:
                current = toggle(title, title + "-restore")
                if current.get("checked") != previous:
                    tap(current)
            assert toggle(title, title + "-restored").get("checked") == previous
            print(f"해상도 개별 스위치 통과: {title}", flush=True)
    finally:
        node = toggle(resources["loc_classify_downloads"], "classification-restore")
        if (node.get("checked") == "true") != original:
            tap(node)
        adb("shell", "input", "keyevent", "4")
        menu("플러그인 관리")
        top()
        if not library_was_enabled:
            tap(UI["switch"](resources["plugin_library"], "folder-disable"))
        if encoding_was_enabled:
            top()
            tap(UI["switch"](resources["loc_media_tools"], "folder-encoding-restore"))
        adb("shell", "input", "keyevent", "4")
    print("LIBRARY 단독 분류 UI 통과 및 설정 복원 완료", flush=True)


if __name__ == "__main__":
    main()
