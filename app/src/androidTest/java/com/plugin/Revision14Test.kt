package com.plugin

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateMapOf
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.DownloadUtil
import com.plugin.compatibility.PostMediaViewer
import com.plugin.queue.PluginTask
import com.plugin.runtime.pluginQueue
import com.plugin.ui.PluginMainActivity
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 14차 개선안(홈 버튼 정지·중복 추가 차단·보고서 길게 보기)을 실제 화면에서 검사합니다. */
class Revision14Test {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** 홈 버튼으로 나갔다 돌아오면 백그라운드 시간 동안 재생 위치가 진행하지 않아야 합니다. */
    @Test fun homeButtonPausesVideo(): Unit = runBlocking {
        val root = File(context.cacheDir, "revision14-home-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val video = File(root, "clip.mp4")
        try {
            clip(video, 30)
            val scenario = ActivityScenario.launch(PluginMainActivity::class.java)
            try {
                scenario.onActivity { it.setContent { SealTheme { PostMediaViewer(listOf(video.path), video.path) {} } } }
                await("일시정지"); SystemClock.sleep(2500)
                val before = position()
                assertTrue("재생 위치가 진행하지 않음: $before", before >= 1)
                assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))
                SystemClock.sleep(5000)
                context.startActivity(Intent(context, PluginMainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                // 복귀 후에는 직전에 재생 중이었으므로 다시 재생 상태가 되어야 합니다.
                await("일시정지"); SystemClock.sleep(300)
                val after = position()
                // 백그라운드 5초가 재생으로 계산됐다면 차이는 6초를 넘습니다.
                android.util.Log.i("Revision14", "홈 전 $before 초, 복귀 후 $after 초")
                assertTrue("백그라운드에서 재생이 계속됨: $before → $after", after - before < 4)
            } finally { runCatching { scenario.close() } }
        } finally { root.deleteRecursively() }
    }

    /** 같은 요청을 다시 추가해도 기존 항목을 유지하고 새 상태로 덮지 않아야 합니다. */
    @Test fun duplicateEnqueueIsRejected() {
        val queue = org.koin.core.context.GlobalContext.get().get<com.junkfood.seal.download.DownloaderV2>().pluginQueue()
        val task = PluginTask("https://example.com/revision14-dup", preferences = DownloadUtil.DownloadPreferences.EMPTY)
        fun state(title: String) = PluginTask.State(PluginTask.DownloadState.Completed("/rev14.mp4"), null, PluginTask.ViewState(title = title))
        try {
            queue.enqueue(task, state("첫째")); queue.enqueue(task, state("둘째"))
            assertEquals("첫째", queue.getTaskStateMap()[task]?.viewState?.title)
            assertEquals(1, queue.getTaskStateMap().keys.count { it.url == task.url })
        } finally { instrumentation.runOnMainSync { queue.getTaskStateMap().remove(task) } }
    }

    /** 보고서 복사 버튼을 0.5초 이상 누르면 보고서 미리보기가 열려야 합니다. */
    @Test fun longPressOpensReportPreview() {
        val task = PluginTask("https://example.com/revision14-error", preferences = DownloadUtil.DownloadPreferences.EMPTY)
        val error = PluginTask.DownloadState.Error(IllegalStateException("rev14-boom"), PluginTask.RestartableAction.Download)
        val states = mutableStateMapOf(task to PluginTask.State(error, null, PluginTask.ViewState(title = "14차 오류")))
        var actions = 0
        ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { SealTheme {
                com.plugin.queue.DownloadPageImplV2(taskDownloadStateMap = states, onActionPost = { _, _ -> actions++ })
            } } }
            tap("목록으로 보기")
            tap(context.getString(com.junkfood.seal.R.string.show_more_actions))
            val label = context.getString(com.junkfood.seal.R.string.copy_error_report)
            // 시트 올라오는 애니메이션 중에는 좌표가 움직이므로 위치가 안정된 뒤에만 누르고, 실패하면 다시 시도합니다.
            var opened = false
            repeat(3) { attempt ->
                if (opened) return@repeat
                var previous = Rect(); var stable = false
                repeat(20) {
                    if (stable) return@repeat
                    val now = Rect().also { await(label).getBoundsInScreen(it) }
                    stable = now == previous; previous = now; if (!stable) SystemClock.sleep(300)
                }
                longTouch(await(label))
                opened = runCatching { await(context.getString(com.junkfood.seal.R.string.loc_report_preview_title)) }.isSuccess
                if (!opened) android.util.Log.w("Revision14", "길게 누름 ${attempt + 1}회 실패")
            }
            assertTrue("보고서 미리보기가 열리지 않음", opened)
            await("rev14-boom", contains = true)
            assertEquals("길게 누름이 복사 동작을 함께 실행하면 안 됩니다", 0, actions)
        }
    }

    /** AI 복제 페이지를 삭제하면 대기열의 전체 페이지 수와 파일 경로가 함께 줄어야 합니다. */
    @Test fun duplicateDeletionUpdatesQueueCount(): Unit = runBlocking {
        val root = File(context.cacheDir, "revision14-delete-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val original = File(root, "original.png"); val copy = File(root, "copy.png")
        val paths = listOf(original.path, copy.path)
        val queuePaths = paths
        val queue = org.koin.core.context.GlobalContext.get().get<com.junkfood.seal.download.DownloaderV2>().pluginQueue()
        val task = PluginTask("https://www.instagram.com/p/REV14/", preferences = DownloadUtil.DownloadPreferences.EMPTY)
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
            for (file in listOf(original, copy)) file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            com.plugin.compatibility.PluginMediaMetadataStore.save(original.path, postPageCount = 1)
            com.plugin.compatibility.PluginMediaMetadataStore.save(copy.path, postPageCount = 1, aiDuplicate = true)
            instrumentation.runOnMainSync { queue.getTaskStateMap()[task] = PluginTask.State(
                PluginTask.DownloadState.Completed(queuePaths[0], queuePaths), null, PluginTask.ViewState(isImage = true)) }
            val before = com.plugin.queue.QueueMedia.grouped(queue.getTaskStateMap().toMap()).values.single().viewState.mediaCount
            val scenario = ActivityScenario.launch(PluginMainActivity::class.java)
            try {
                scenario.onActivity { it.setContent { SealTheme { PostMediaViewer(paths, copy.path) {} } } }
                await("2 / 2")
                tap(context.getString(com.junkfood.seal.R.string.loc_delete_page))
                await(context.getString(com.junkfood.seal.R.string.revision5_delete_file_hint))
                tap(context.getString(com.junkfood.seal.R.string.loc_delete_page))
                await("1 / 1")
                val done = queue.getTaskStateMap()[task]?.downloadState as PluginTask.DownloadState.Completed
                assertEquals(listOf(queuePaths[0]), done.filePaths)
                val after = com.plugin.queue.QueueMedia.grouped(queue.getTaskStateMap().toMap()).values.single().viewState.mediaCount
                assertEquals("대기열 개수: $before → $after", before - 1, after)
            } finally { runCatching { scenario.close() } }
        } finally {
            instrumentation.runOnMainSync { queue.getTaskStateMap().remove(task) }
            paths.forEach(com.plugin.compatibility.PluginMediaMetadataStore::delete); root.deleteRecursively()
        }
    }

    /** 앱에 포함된 FFmpeg로 단색 영상을 자체 캐시에 생성합니다. */
    private fun clip(output: File, seconds: Int) {
        FFmpeg.getInstance().init(context)
        val process = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").path,
            "-y", "-nostdin", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=red:s=64x48:r=3:d=$seconds",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-bf", "0", "-f", "mp4", output.path).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python", "aria2c").joinToString(":") {
                File(context.noBackupFilesDir, "youtubedl-android/packages/$it/usr/lib").path
            }
        }.start()
        val errors = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { errors }
    }

    /** 화면의 첫 번째 m:ss 텍스트(현재 재생 위치)를 초로 읽습니다. */
    private fun position(): Int {
        val texts = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.text?.toString()?.let(texts::add)
            for (index in 0 until node.childCount) walk(node.getChild(index))
        }
        instrumentation.uiAutomation.clearCache(); walk(instrumentation.uiAutomation.rootInActiveWindow)
        val match = texts.firstNotNullOfOrNull { Regex("^(\\d+):(\\d\\d)$").find(it) } ?: error("재생 위치 텍스트가 없습니다: $texts")
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    /** 노드 중앙을 0.8초 눌렀다 뗍니다. 초기 패스 길게 누름 감지는 실제 터치 이벤트가 필요합니다. */
    private fun longTouch(node: AccessibilityNodeInfo) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, time: Long) {
            val event = MotionEvent.obtain(down, time, action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, down); SystemClock.sleep(800); send(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
    }

    /** 접근성 레이블이 같은 항목 중 클릭 가능한 노드를 실행합니다. */
    private fun tap(label: String) {
        val end = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, label, clickable = true)?.let {
                assertTrue(it.performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.waitForIdleSync(); return
            }
            SystemClock.sleep(100)
        }
        error("버튼을 찾지 못했습니다: $label")
    }

    /** 화면에 레이블이 나타날 때까지 제한 시간 동안 기다립니다. */
    private fun await(label: String, contains: Boolean = false): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, label, contains = contains)?.let { return it }
            SystemClock.sleep(100)
        }
        error("화면을 찾지 못했습니다: $label")
    }

    /** 화면 트리에서 일치하는 레이블을 깊이 우선 탐색합니다. */
    private fun find(node: AccessibilityNodeInfo?, label: String, clickable: Boolean = false, contains: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        fun matches(value: CharSequence?) = value?.toString()?.let { if (contains) it.contains(label) else it == label } == true
        if ((matches(node.text) || matches(node.contentDescription)) && (!clickable || node.isClickable)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label, clickable, contains)?.let { return it }
        if (clickable && node.isClickable && find(node, label, contains = contains) != null) return node
        return null
    }
}
