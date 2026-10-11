"""선택 플러그인 간 직접 참조와 원본 코드에 추가한 주석을 검사합니다."""

from collections import Counter
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = "7677f61a20fda4210e84215fef9e9b51d25daa47"
GROUPS = {
    "image": "SOCIAL", "instagram": "SOCIAL", "tiktok": "SOCIAL",
    "listdownload": "LIST", "media": "MEDIA", "library": "LIBRARY",
    "queue": "QUEUE", "interaction": "INTERACTION",
}
SHARED_QUEUE_TYPES = r"com\.plugin\.queue\.(?:PluginTaskFactory|PluginTaskDownloader|PluginTaskAdapter|PluginTaskBackup|PluginTask)(?!\w)"


def optional_references(text):
    """항상 사용하는 작업 모델·어댑터는 선택 대기열 UI의 의존성과 구분합니다."""
    text = re.sub(SHARED_QUEUE_TYPES, "shared_task_model", text)
    return re.findall(r"com\.plugin\.(\w+)\.", text)


def comments(text):
    """문자열과 중첩 블록을 구분해 Kotlin 주석만 반환합니다."""
    found = []
    index = 0
    while index < len(text):
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            index = len(text) if end < 0 else end + 3
        elif text[index] in ('"', "'"):
            quote = text[index]
            index += 1
            while index < len(text):
                if text[index] == "\\":
                    index += 2
                elif text[index] == quote:
                    index += 1
                    break
                else:
                    index += 1
        elif text.startswith("//", index):
            end = text.find("\n", index)
            end = len(text) if end < 0 else end
            found.append(text[index:end])
            index = end
        elif text.startswith("/*", index):
            start = index
            index += 2
            depth = 1
            while index < len(text) and depth:
                if text.startswith("/*", index):
                    depth += 1
                    index += 2
                elif text.startswith("*/", index):
                    depth -= 1
                    index += 2
                else:
                    index += 1
            found.append(text[start:index])
        else:
            index += 1
    return found


def originals(paths):
    """Git 객체를 한 번에 읽어 현재 파일과 같은 순서로 반환합니다."""
    refs = [f"{BASE}:{path.relative_to(ROOT).as_posix()}" for path in paths]
    data = subprocess.check_output(
        ["git", "cat-file", "--batch"], cwd=ROOT,
        input=("\n".join(refs) + "\n").encode("utf-8"),
    )
    offset = 0
    for _ in paths:
        end = data.index(b"\n", offset)
        header = data[offset:end].decode("utf-8")
        offset = end + 1
        if header.endswith(" missing"):
            yield ""
        else:
            size = int(header.split()[-1])
            yield data[offset:offset + size].decode("utf-8")
            offset += size + 1


def main():
    """선택 기능 독립성·필수 기반 의존성·원본 주석 보존을 검사합니다."""
    assert comments('val url = "https://example.org" // 설명\n/* 바깥 /* 안 */ */') == [
        "// 설명", "/* 바깥 /* 안 */ */",
    ]
    plugins = ROOT / "app/src/main/java/com/plugin"
    checked = 0
    for path in plugins.rglob("*.kt"):
        own = GROUPS.get(path.parent.name)
        if own is None:
            continue
        text = path.read_text(encoding="utf-8")
        for package in optional_references(text):
            dependency = GROUPS.get(package)
            assert dependency is None or dependency == own, (
                f"선택 기능 간 직접 참조: {path.name} → {package}"
            )
        checked += 1
    for path in (plugins / "compatibility").glob("*.kt"):
        text = path.read_text(encoding="utf-8")
        # 과거 JSON 형식의 직렬화 이름은 코드 참조가 아닙니다.
        text = re.sub(r'@SerialName\("[^"]*"\)', "", text)
        assert not any(package in GROUPS for package in optional_references(text)), f"필수 기반이 선택 기능 참조: {path.name}"
    core = ROOT / "app/src/main/java/com/junkfood"
    paths = list(core.rglob("*.kt"))
    for path, original in zip(paths, originals(paths)):
        added = Counter(comments(path.read_text(encoding="utf-8"))) - Counter(comments(original))
        assert not added, f"원본에 추가한 주석: {path.relative_to(ROOT)}"
    assert not list(core.rglob("README*")), "원본 폴더에 추가한 README가 남아 있습니다."
    engine = (core / "seal/download/DownloaderV2.kt").read_text(encoding="utf-8")
    assert "com.plugin" not in engine, "원본 엔진에 확장 구현이 남아 있습니다."
    print(f"선택 플러그인 {checked}개 파일 독립성, 필수 기반 및 원본 주석·README 검사 통과")


if __name__ == "__main__":
    main()
