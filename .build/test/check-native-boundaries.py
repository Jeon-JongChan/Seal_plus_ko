"""원본 기준 파일과 전용 대기열의 분리 경계를 오프라인으로 검사합니다."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
prefix = 'app/src/main/java/com/junkfood/seal/'
for name in ['download/Task.kt', 'download/DownloaderV2.kt', 'ui/page/downloadv2/ActionSheet.kt']:
    expected = subprocess.check_output(['git', 'show', '7677f61:' + prefix + name], cwd=ROOT)
    assert (ROOT / prefix / name).read_bytes() == expected, f'기준 커밋과 다름: {name}'

plugins = ROOT / 'app/src/main/java/com/plugin'
for name in ['runtime/PluginTaskEngine.kt', 'runtime/BaselineTaskWorker.kt',
             'queue/DownloadPageV2.kt', 'queue/VideoCardV2.kt', 'queue/ActionSheet.kt',
             'instagram/SocialDownloadController.kt', 'listdownload/ListDownloadController.kt',
             'compatibility/DownloaderExtensions.kt']:
    content = (plugins / name).read_text(encoding='utf-8')
    assert 'com.junkfood.seal.download.Task' not in content, f'원본 작업 모델 참조: {name}'
    assert 'DownloaderV2Impl(' not in content, f'원본 작업자 연결: {name}'

print('기준 커밋 원본 3개와 전용 대기열 경계 8개 검사 통과')
