"""기존 에뮬레이터의 플러그인을 한 개씩 켜고 메뉴·원본 화면 복귀를 확인합니다."""

from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ADB = ROOT / ".build/tools/android-sdk/platform-tools/adb.exe"
DEVICE = "emulator-5556"
LOGS = ROOT / ".build/logs"


def adb(*args):
    """장치를 고정해 ADB를 실행하고 실패하면 검사를 중단합니다."""
    return subprocess.check_output([str(ADB), "-s", DEVICE, *args], timeout=30)


def screen(name):
    """접근성 트리를 프로젝트 로그에 보존하고 XML로 읽습니다."""
    for _ in range(3):
        result = adb("shell", "uiautomator", "dump", "/sdcard/plugin-check.xml")
        if b"UI hierchary dumped" in result:
            break
    assert b"UI hierchary dumped" in result, f"화면 트리 생성 실패: {result!r}"
    data = adb("exec-out", "cat", "/sdcard/plugin-check.xml")
    (LOGS / f"plugin-{name}-ui.xml").write_bytes(data)
    return ET.fromstring(data)


def tap(node):
    """접근성 노드의 실제 화면 영역 중앙을 누릅니다."""
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def switch(title, name):
    """스크롤해 해당 제목의 스위치를 찾습니다."""
    for _ in range(2):
        adb("shell", "input", "swipe", "160", "150", "160", "590", "75")
    for attempt in range(20):
        tree = screen(f"{name}-find-{attempt}")
        for node in tree.iter("node"):
            if node.attrib.get("checkable") == "true" and any(
                child.attrib.get("text") == title for child in node.iter("node")
            ):
                _, y1, _, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
                # 하단 시스템 탐색 영역에 걸친 카드를 누르지 않도록 충분히 보일 때만 선택합니다.
                if 120 <= (y1 + y2) // 2 <= 580:
                    return node
        adb("shell", "input", "swipe", "160", "500", "160", "300", "500")
    raise AssertionError(f"플러그인 스위치를 찾을 수 없습니다: {title}")


def main():
    """관리 화면에서 각 스위치를 전환하고 사용자의 검사 전 상태로 복원합니다."""
    resources = {}
    for path in (ROOT / "app/src/main/res/values-ko").glob("*.xml"):
        resources.update({item.attrib["name"]: item.text for item in ET.parse(path).getroot() if item.tag == "string"})
    titles = [resources[key] for key in ["plugin_social", "loc_list_downloads", "loc_media_tools",
        "plugin_library", "plugin_queue", "plugin_interaction"]]
    required = switch(resources["plugin_compatibility"], "required")
    assert required.attrib["checked"] == "true"
    assert required.attrib["enabled"] == "false"
    tap(required)
    assert switch(resources["plugin_compatibility"], "required-after-tap").attrib["checked"] == "true"
    print("필수 호환 플러그인 항상 활성화·변경 불가 확인", flush=True)
    previous_states = {}
    for index, title in enumerate(titles):
        node = switch(title, str(index))
        previous = node.attrib["checked"]
        previous_states[title] = previous
        tap(node)
        try:
            active = switch(title, f"{index}-on")
            assert active.attrib["checked"] != previous, f"전환 실패: {title}"
        finally:
            # 파일·쿠키·작업을 변경하지 않고 검사한 스위치만 기존 상태로 복원합니다.
            tap(switch(title, f"{index}-restore"))
        assert switch(title, f"{index}-off").attrib["checked"] == previous
        print(f"스위치 UI 검사 통과: {title}", flush=True)
    adb("shell", "input", "keyevent", "4")
    baseline = screen("restored-home")
    if previous_states[resources["plugin_queue"]] == "false":
        assert not any(node.attrib.get("text") == resources["loc_queue_select"] for node in baseline.iter("node"))
    print("모든 스위치의 검사 전 상태 복원 및 대기열 복귀 확인", flush=True)


if __name__ == "__main__":
    main()
