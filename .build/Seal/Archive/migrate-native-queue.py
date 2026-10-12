"""플러그인 대기열 연결을 전용 모델과 복제 작업자로 이전합니다."""

raise SystemExit("이전 작업 보관용입니다. 원본 소스 보호를 위해 실행하지 않습니다.")
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java'


def read(path):
    """프로젝트 소스를 UTF-8로 읽습니다."""
    return (SOURCE / path).read_text(encoding='utf-8-sig')


def write(path, value):
    """지정한 소스 파일만 UTF-8로 기록합니다."""
    target = SOURCE / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(value, encoding='utf-8', newline='\n')


def native(value):
    """원본 작업 모델 참조를 플러그인 전용 모델 참조로 변경합니다."""
    return value.replace('import com.junkfood.seal.download.Task\n',
                         'import com.plugin.queue.PluginTask as Task\n').replace(
        'com.junkfood.seal.download.Task.', 'com.plugin.queue.PluginTask.').replace(
        'TypeInfo.Instagram', 'TypeInfo.SocialMedia')


engine = native(read('com/plugin/runtime/PluginDownloaderV2.kt'))
engine = engine.replace('import com.junkfood.seal.download.*',
                        'import com.plugin.queue.PluginTask as Task\nimport com.plugin.queue.PluginTaskDownloader\nimport com.plugin.queue.PluginTaskBackup')
engine = engine.replace('class PluginDownloaderV2(', 'class PluginTaskEngine(').replace(
    ': DownloaderV2, KoinComponent', ': PluginTaskDownloader, KoinComponent')
engine = engine.replace('DownloaderV2Impl(appContext, scope, taskStateMap, startWorkers = false)',
                        'BaselineTaskWorker(appContext, scope, taskStateMap)')
engine = engine.replace('PreferenceUtil.encodeTaskListBackup(it)', 'PluginTaskBackup.save(it)').replace(
    'PreferenceUtil.decodeTaskListBackup()', 'PluginTaskBackup.load()')
write('com/plugin/runtime/PluginTaskEngine.kt', engine)

base = subprocess.check_output(['git', 'show', '7677f61:app/src/main/java/com/junkfood/seal/download/DownloaderV2.kt'], cwd=ROOT).decode('utf-8')
imports = native(base[:base.index('interface DownloaderV2')]).replace(
    'package com.junkfood.seal.download', 'package com.plugin.runtime')
imports += 'import com.plugin.queue.PluginTask as Task\n\n'
body = base[base.index('    private var Task.state:'):]
body = native(body).replace('private fun doYourWork()', 'internal fun doYourWork()')
worker = imports + '''/** 기준 커밋의 처리 흐름을 전용 작업 모델에서 실행하는 기본 작업자입니다. */
internal class BaselineTaskWorker(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val taskStateMap: SnapshotStateMap<Task, Task.State>,
) {
    /** 기본 작업을 기준 커밋의 취소 흐름으로 중단합니다. */
    fun cancel(task: Task): Boolean = task.cancelImpl()

    /** 기본 작업을 기준 커밋의 재시작 흐름으로 되돌립니다. */
    fun restart(task: Task) = task.restartImpl()

    /** 실행 중 작업 수를 전체 전용 대기열에서 계산합니다. */
    private fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
        state.downloadState is Running || state.downloadState is FetchingInfo
    }

''' + body
write('com/plugin/runtime/BaselineTaskWorker.kt', worker)

paths = [
    'compatibility/DownloaderExtensions.kt', 'compatibility/QueueMedia.kt',
    'instagram/SocialDownloadController.kt', 'listdownload/ListDownloadController.kt',
    'library/PostMediaDetails.kt', 'library/QueueMedia.kt', 'library/VideoListPage.kt',
    'queue/QueueMedia.kt', 'queue/VideoCardV2.kt', 'runtime/PluginExecutionPolicy.kt',
]
for path in paths:
    write('com/plugin/' + path, native(read('com/plugin/' + path)))

page = native(read('com/plugin/queue/DownloadPageV2.kt')).replace(
    'import com.junkfood.seal.download.DownloaderV2',
    'import com.junkfood.seal.download.DownloaderV2\nimport com.plugin.queue.PluginTaskDownloader\nimport com.plugin.runtime.pluginQueue')
page = page.replace('    val view = LocalView.current',
                    '    val queue = downloader.pluginQueue()\n    val view = LocalView.current', 1)
page = page.replace('downloader.getTaskStateMap()', 'queue.getTaskStateMap()').replace(
    'downloader.refreshCompletedFiles()', 'queue.refreshCompletedFiles()')
for name in ['cancel', 'restart', 'remove']:
    page = page.replace('downloader.' + name + '(', 'queue.' + name + '(')
page = page.replace('object : DownloaderV2 {', 'object : PluginTaskDownloader {').replace(
    'val downloader: DownloaderV2 = mockDownloader', 'val queue: PluginTaskDownloader = mockDownloader')
write('com/plugin/queue/DownloadPageV2.kt', page)

sheet = native(read('com/junkfood/seal/ui/page/downloadv2/ActionSheet.kt')).replace(
    'package com.junkfood.seal.ui.page.downloadv2', 'package com.plugin.queue').replace(
    'import com.junkfood.seal.download.FakeDownloaderV2', '')
sheet = sheet.replace('    val downloader = FakeDownloaderV2\n', '')
write('com/plugin/queue/ActionSheet.kt', sheet)
