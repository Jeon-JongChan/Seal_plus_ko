"""선택 기능 간 별칭을 독립 구현으로 바꾸며 원본 소스는 수정하지 않습니다."""

raise SystemExit("이전 작업 보관용입니다. 원본 소스 보호를 위해 실행하지 않습니다.")
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLUGINS = ROOT / 'app/src/main/java/com/plugin'
BACKUP = ROOT / '.build/logs/ui-isolation-before'
PAIRS = [
    ('instagram/InstagramUtil.kt', 'listdownload/ListMediaUtil.kt', 'com.plugin.instagram', 'com.plugin.listdownload'),
    ('tiktok/TikTokUtil.kt', 'listdownload/ListTikTokUtil.kt', 'com.plugin.tiktok', 'com.plugin.listdownload'),
    ('instagram/InstagramSelectionPage.kt', 'listdownload/ListSelectionPage.kt', 'com.plugin.instagram', 'com.plugin.listdownload'),
    ('queue/QueueMedia.kt', 'library/QueueMedia.kt', 'com.plugin.queue', 'com.plugin.library'),
    ('queue/QueueMedia.kt', 'compatibility/QueueMedia.kt', 'com.plugin.queue', 'com.plugin.compatibility'),
]

if __name__ == '__main__':
    for source, target, old_package, new_package in PAIRS:
        destination = PLUGINS / target
        backup = BACKUP / target
        assert destination.resolve().is_relative_to(PLUGINS.resolve())
        assert not backup.exists(), '이미 적용된 이전입니다: ' + target
        backup.parent.mkdir(parents=True, exist_ok=True)
        backup.write_bytes(destination.read_bytes())
        value = (PLUGINS / source).read_text(encoding='utf-8')
        value = value.replace('package ' + old_package, 'package ' + new_package, 1)
        if new_package == 'com.plugin.listdownload':
            value = value.replace('import com.plugin.tiktok.TikTokUtil', 'import com.plugin.listdownload.ListTikTokUtil')
            value = value.replace('import com.plugin.instagram.InstagramUtil', 'import com.plugin.listdownload.ListMediaUtil')
            value = value.replace('InstagramUtil', 'ListMediaUtil').replace('TikTokUtil', 'ListTikTokUtil')
            value = value.replace('ListListTikTokUtil', 'ListTikTokUtil')
            value = value.replace('InstagramSelectionPage', 'ListSelectionPage')
        if new_package == 'com.plugin.compatibility':
            value = value.replace('import com.plugin.compatibility.*\n', '')
        destination.write_text(value, encoding='utf-8', newline='\n')
    print('목록·파일 관리·호환 유틸리티의 선택 기능 직접 참조 5개 제거')
