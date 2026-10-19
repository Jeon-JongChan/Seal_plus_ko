package com.plugin

import android.graphics.Bitmap
import android.graphics.Color
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
import com.plugin.compatibility.*
import com.plugin.compatibility.enhancement.EnhancementClient
import com.plugin.compatibility.enhancement.VideoEnhancementWork
import com.plugin.queue.PluginTask
import com.plugin.runtime.pluginQueue
import com.plugin.ui.PluginMainActivity
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 13차의 영상 전환·반복·삭제 확인을 별도 패키지의 실제 화면에서 검사합니다. */
class Revision13Test {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** 짧은 원본·결과 영상을 여러 번 전환해 각 영상의 실제 색상과 반복 재생을 확인합니다. */
    @Test fun comparisonSwitchesAndWallpaperShowsVideo(): Unit = runBlocking {
        check(context.packageName.endsWith(".devicecheck"))
        val root = File(context.cacheDir, "revision13-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            clip(File(root, "source"), "red"); clip(File(root, "preview.mp4"), "blue")
            val preview = EnhancementClient.Preview("/revision13.mp4", root, "test", 64, 48, "mp4", "검사", 0, true, 3.0, 1.0, "video/avc")
            instrumentation.runOnMainSync {
                VideoEnhancementWork.state.value = VideoEnhancementWork.Companion.State(preview = preview)
                VideoEnhancementWork.showResult.value = true
            }
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme { com.plugin.videoenhancement.VideoEnhancementResultHost() } } }
                repeat(6) { index ->
                    if (index > 0) tap(if (index % 2 == 0) "보정 결과" else "원본")
                    await("미리보기 재생 중")
                    // 첫 렌더링 이후에도 한 영상 길이보다 오래 기다려 반복 상태에서 실제 픽셀을 검사합니다.
                    SystemClock.sleep(1300)
                    assertColor(if (index % 2 == 0) Color.BLUE else Color.RED)
                }
                instrumentation.runOnMainSync { VideoEnhancementWork.showResult.value = false }
                scenario.onActivity { it.setContent { SealTheme {
                    com.plugin.ui.WallpaperPreviewDialog(listOf(com.plugin.ui.WallpaperPageItem(File(root, "preview.mp4").path))) {}
                } } }
                await(context.getString(com.junkfood.seal.R.string.loc_wallpaper_preview))
                SystemClock.sleep(1800)
                assertColor(Color.BLUE)
            }
        } finally {
            instrumentation.runOnMainSync { VideoEnhancementWork.showResult.value = false; VideoEnhancementWork.state.value = VideoEnhancementWork.Companion.State() }
            root.deleteRecursively()
        }
    }

    /** 게시글 영상이 끝나도 재생하고 한 번 터치하면 정지·재생하는지 확인합니다. */
    @Test fun postVideoLoopsAndSingleTapPauses(): Unit = runBlocking {
        val root = File(context.cacheDir, "revision13-tap-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val video = File(root, "clip.mp4")
        val enabled = PluginState.isEnabled(PluginId.VIDEO_AI)
        val previousSaved = VideoEnhancementWork.saved.value
        try {
            clip(video, "red")
            instrumentation.runOnMainSync {
                PluginState.setEnabled(PluginId.VIDEO_AI, true)
                VideoEnhancementWork.saved.value = VideoEnhancementWork.Companion.Saved(video.path, File(root, "missing-AI-1234abcd.mp4").path, false)
            }
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme { PostMediaViewer(listOf(video.path), video.path) {} } } }
                await("일시정지"); SystemClock.sleep(2200); await("일시정지")
                await("1 / 1"); assertNull(VideoEnhancementWork.saved.value)
                touchCenter(); await("재생")
                touchCenter(); await("일시정지")
            }
        } finally {
            instrumentation.runOnMainSync { PluginState.setEnabled(PluginId.VIDEO_AI, enabled); VideoEnhancementWork.saved.value = previousSaved }
            root.deleteRecursively()
        }
    }

    /** 마지막 AI 복제 페이지를 삭제하면 분모와 대기열 경로가 즉시 줄어드는지 확인합니다. */
    @Test fun duplicateDeletionRemovesPageImmediately(): Unit = runBlocking {
        val root = File(context.cacheDir, "revision13-delete-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val original = File(root, "original.png"); val copy = File(root, "copy.png")
        val paths = listOf(original.path, copy.path)
        val queue = org.koin.core.context.GlobalContext.get().get<com.junkfood.seal.download.DownloaderV2>().pluginQueue()
        val task = PluginTask("https://www.instagram.com/p/REV13/", preferences = DownloadUtil.DownloadPreferences.EMPTY)
        try {
            val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            for (file in listOf(original, copy)) file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            PluginMediaMetadataStore.save(original.path, postPageCount = 1)
            PluginMediaMetadataStore.save(copy.path, postPageCount = 1, aiDuplicate = true)
            instrumentation.runOnMainSync { queue.getTaskStateMap()[task] = PluginTask.State(PluginTask.DownloadState.Completed(original.path, paths), null, PluginTask.ViewState(isImage = true)) }
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme { PostMediaViewer(paths, copy.path) {} } } }
                await("2 / 2")
                tap(context.getString(com.junkfood.seal.R.string.loc_delete_page))
                await(context.getString(com.junkfood.seal.R.string.revision5_delete_file_hint))
                tap(context.getString(com.junkfood.seal.R.string.loc_delete_page))
                await("1 / 1")
                assertFalse(copy.exists()); assertTrue(original.exists())
                assertEquals(listOf(original.path), (queue.getTaskStateMap()[task]?.downloadState as PluginTask.DownloadState.Completed).filePaths)
                assertColor(Color.RED)
            }
        } finally {
            instrumentation.runOnMainSync { queue.getTaskStateMap().remove(task) }
            paths.forEach(PluginMediaMetadataStore::delete); root.deleteRecursively()
        }
    }

    /** 단일 게시글 메뉴의 삭제도 파일 삭제 여부를 묻고 취소하면 대기열을 보존합니다. */
    @Test fun singleQueueDeleteAsksAboutFiles() {
        val task = PluginTask("https://example.com/revision13", preferences = DownloadUtil.DownloadPreferences.EMPTY)
        val states = mutableStateMapOf(task to PluginTask.State(PluginTask.DownloadState.Completed("/test.mp4"), null, PluginTask.ViewState(title = "13차 검사")))
        var actions = 0
        ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { SealTheme {
                com.plugin.queue.DownloadPageImplV2(taskDownloadStateMap = states, onActionPost = { _, _ -> actions++ })
            } } }
            tap("목록으로 보기")
            tap(context.getString(com.junkfood.seal.R.string.show_more_actions))
            tap(context.getString(com.junkfood.seal.R.string.delete))
            await(context.getString(com.junkfood.seal.R.string.loc_delete_files_and_history))
            tap(context.getString(com.junkfood.seal.R.string.cancel))
            assertEquals(0, actions); assertEquals(1, states.size)
        }
    }

    /** 고정 색상의 짧은 영상을 앱에 포함된 FFmpeg로 자체 캐시에 생성합니다. */
    private fun clip(output: File, color: String) {
        FFmpeg.getInstance().init(context)
        val process = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").path,
            "-y", "-nostdin", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=$color:s=64x48:r=3:d=1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-bf", "0", "-f", "mp4", output.path).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python", "aria2c").joinToString(":") {
                File(context.noBackupFilesDir, "youtubedl-android/packages/$it/usr/lib").path
            }
        }.start()
        val errors = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { errors }
    }

    /** 재생창 중심에 기대 색상의 영상 픽셀이 실제 표시되는지 검사합니다. */
    private fun assertColor(expected: Int) {
        val end = SystemClock.elapsedRealtime() + 5_000
        var count: Int
        do {
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        try {
            count = 0
            for (y in screenshot.height / 4 until screenshot.height * 3 / 4)
                for (x in screenshot.width / 3 until screenshot.width * 2 / 3) {
                    val pixel = screenshot.getPixel(x, y)
                    if (expected == Color.RED && Color.red(pixel) > Color.blue(pixel) + 70 ||
                        expected == Color.BLUE && Color.blue(pixel) > Color.red(pixel) + 70) count++
                }
            if (count > 200) return
            if (SystemClock.elapsedRealtime() >= end) File(context.cacheDir, "revision13-pixel-failure.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
        SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < end)
        fail("미디어의 실제 색상 프레임이 보이지 않습니다: $count")
    }

    /** 이동 없는 한 손가락 터치를 재생 영역 중심에 보냅니다. */
    private fun touchCenter() {
        val bounds = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
        }
    }

    /** 접근성 레이블이 같은 제목과 버튼 중 클릭 가능한 항목만 실행합니다. */
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

    /** 비동기 디코딩과 대기열 묶음 갱신을 제한된 시간 동안 기다립니다. */
    private fun await(label: String): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        error("화면을 찾지 못했습니다: $label")
    }

    /** 화면 트리에서 일치하는 레이블을 깊이 우선 탐색합니다. */
    private fun find(node: AccessibilityNodeInfo?, label: String, clickable: Boolean = false): AccessibilityNodeInfo? {
        if (node == null) return null
        if ((node.text?.toString() == label || node.contentDescription?.toString() == label) && (!clickable || node.isClickable)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label, clickable)?.let { return it }
        // Compose는 버튼 레이블을 클릭 가능한 부모의 자식으로 노출하기도 합니다.
        if (clickable && node.isClickable && find(node, label) != null) return node
        return null
    }
}
