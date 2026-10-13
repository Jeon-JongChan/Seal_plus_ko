"""공유 전용 진입점·기본 선택·실행 아이콘 보존을 검사합니다."""
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    """Android 공유 필터가 선택 별칭에서만 등록되는지 확인합니다."""
    root = Path(__file__).resolve().parents[2]
    application = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot().find("application")
    key = "{http://schemas.android.com/apk/res/android}"
    entries = {element.get(key + "name"): element for element in application if element.tag in ("activity", "activity-alias")}
    quick = entries["com.plugin.ui.QuickShareAlias"]
    main = entries["com.plugin.ui.MainShareAlias"]
    assert quick.get(key + "enabled") == "true"
    assert main.get(key + "enabled") == "false"
    for alias, target in ((quick, "PluginQuickDownloadActivity"), (main, "PluginMainActivity")):
        assert alias.get(key + "targetActivity") == "com.plugin.ui." + target
        actions = {action.get(key + "name") for action in alias.findall("intent-filter/action")}
        assert actions == {"android.intent.action.SEND", "android.intent.action.VIEW"}
        assert alias.get(key + "exported") == "true"
    for name, entry in entries.items():
        if name.endswith("ShareAlias"):
            continue
        actions = {action.get(key + "name") for action in entry.findall("intent-filter/action")}
        assert "android.intent.action.SEND" not in actions
        assert "android.intent.action.VIEW" not in actions
    launcher = entries["com.plugin.ui.PluginMainActivity"]
    assert launcher.find("intent-filter/action").get(key + "name") == "android.intent.action.MAIN"
    assert launcher.find("intent-filter/category").get(key + "name") == "android.intent.category.LAUNCHER"
    assert entries[".QuickDownloadActivity"].get(key + "targetActivity") == "com.plugin.ui.PluginQuickDownloadActivity"
    print("공유 별칭 기본값·필터 분리·실행 아이콘·기존 진입점 검사 통과")


if __name__ == "__main__":
    main()
