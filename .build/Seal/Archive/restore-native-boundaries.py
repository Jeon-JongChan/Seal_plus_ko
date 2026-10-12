"""사용자가 지정한 기준 커밋으로 원본 작업 경계를 복원합니다."""

raise SystemExit("이전 작업 보관용입니다. 원본 소스 보호를 위해 실행하지 않습니다.")
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java'
BACKUP = ROOT / '.build/plugin-isolation-before/native-queue'


def baseline(path):
    """지정된 기준 커밋의 정확한 파일 바이트를 가져옵니다."""
    return subprocess.check_output(['git', 'show', '7677f61:' + path], cwd=ROOT)


for name in ['download/Task.kt', 'download/DownloaderV2.kt', 'ui/page/downloadv2/ActionSheet.kt']:
    relative = 'app/src/main/java/com/junkfood/seal/' + name
    target = ROOT / relative
    preserved = BACKUP / name
    preserved.parent.mkdir(parents=True, exist_ok=True)
    if not preserved.exists():
        preserved.write_bytes(target.read_bytes())
    target.write_bytes(baseline(relative))

relative = 'app/src/main/java/com/junkfood/seal/ui/page/downloadv2/DownloadPageV2.kt'
target = ROOT / relative
preserved = BACKUP / 'ui/page/downloadv2/DownloadPageV2.kt'
if not preserved.exists():
    preserved.write_bytes(target.read_bytes())
value = baseline(relative).decode('utf-8')
value = value.replace('    val view = LocalView.current', '''    if (com.plugin.PluginState.isEnabled(com.plugin.PluginId.QUEUE)) {
        com.plugin.queue.EnhancedDownloadPageV2(modifier, onMenuOpen, dialogViewModel, downloader)
        return
    }
    val view = LocalView.current''', 1)
target.write_text(value, encoding='utf-8', newline='\n')

native_actions = value[value.index('sealed interface UiAction {'):value.index('@OptIn(ExperimentalMaterial3Api::class)')]
native_actions = native_actions.replace('    data object Delete : UiAction',
    '    data object Delete : UiAction\n\n    /** 完了ファイル削除を専用画面で扱います。 */\n    data object DeleteFiles : UiAction'.replace('完了ファイル削除を専用画面で扱います。', '완료 파일 삭제를 전용 화면에서 처리합니다.'))
(SRC / 'com/plugin/queue/UiAction.kt').write_text(
    'package com.plugin.queue\n\n/** 전용 대기열 화면의 조작 요청입니다. */\n' + native_actions,
    encoding='utf-8', newline='\n')
sheet = SRC / 'com/plugin/queue/ActionSheet.kt'
sheet.write_text(sheet.read_text(encoding='utf-8').replace(
    'import com.junkfood.seal.ui.page.downloadv2.UiAction\n', ''), encoding='utf-8', newline='\n')
