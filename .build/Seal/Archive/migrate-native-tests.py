"""원본 디렉터리에 추가됐던 플러그인 검사를 전용 모델 검사로 이전합니다."""

raise SystemExit("이전 작업 보관용입니다. 원본 소스 보호를 위해 실행하지 않습니다.")
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEST = ROOT / 'app/src/test/java'
for name in ['DownloadQueueTest.kt', 'QueueMediaTest.kt']:
    source = TEST / 'com/junkfood/seal/download' / name
    target = TEST / 'com/plugin/queue' / name
    if source.exists():
        # 이전 대상은 프로젝트 테스트 디렉터리 내부의 명시한 두 파일로 제한합니다.
        assert source.resolve().is_relative_to(TEST.resolve())
        assert target.resolve().is_relative_to(TEST.resolve())
        assert not target.exists(), f'기존 테스트와 충돌: {target}'
        source.rename(target)
        value = target.read_text(encoding='utf-8-sig').replace(
            'package com.junkfood.seal.download',
            'package com.plugin.queue\n\nimport com.plugin.queue.PluginTask as Task\nimport com.plugin.queue.PluginTaskDownloader as DownloaderV2')
        target.write_text(value, encoding='utf-8', newline='\n')

for path in (TEST / 'com/plugin').rglob('*.kt'):
    if path.name == 'PluginTaskAdapterTest.kt':
        continue
    value = path.read_text(encoding='utf-8-sig').replace(
        'import com.junkfood.seal.download.Task\n', 'import com.plugin.queue.PluginTask as Task\n').replace(
        'com.junkfood.seal.download.Task.', 'com.plugin.queue.PluginTask.').replace(
        'Task.TypeInfo.Instagram', 'Task.TypeInfo.SocialMedia')
    path.write_text(value, encoding='utf-8', newline='\n')
