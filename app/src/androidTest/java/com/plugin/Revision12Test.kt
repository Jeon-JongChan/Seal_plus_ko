package com.plugin

import android.app.NotificationManager
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.ui.theme.SealTheme
import com.plugin.compatibility.TemporarySession
import com.plugin.compatibility.enhancement.EnhancementClient
import com.plugin.compatibility.enhancement.EnhancementModels
import com.plugin.ui.PluginMainActivity
import com.plugin.compatibility.enhancement.VideoEnhancementWork
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 12차 개선의 백그라운드 보정·알림 비교 재생·대량 이력을 격리 앱에서 검증합니다. */
class Revision12Test {
    /** 화면이 사라진 뒤에도 완료하고 알림으로 돌아와 원본·결과를 실제 재생합니다. */
    @Test fun backgroundCompletionAndComparison(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val key = EnhancementModels.ACCELERATED
        val existed = kv.containsKey(key); val accelerated = kv.decodeBool(key, true)
        val root = File(context.cacheDir, "revision12-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            kv.encode(key, false)
            FFmpeg.getInstance().init(context)
            val source = File(root, "source.mp4")
            val command = listOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").path,
                "-y", "-nostdin", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=3:duration=10",
                "-f", "lavfi", "-i", "sine=duration=10", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-bf", "0", "-c:a", "aac", "-shortest", source.path)
            val process = ProcessBuilder(command).redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python", "aria2c").joinToString(":") {
                    File(context.noBackupFilesDir, "youtubedl-android/packages/$it/usr/lib").path
                }
            }.start()
            val errors = process.inputStream.bufferedReader().readText()
            assertEquals(errors, 0, process.waitFor())
            source.copyTo(File(context.cacheDir, "revision12-viewer-fixture.mp4"), overwrite = true)
            val hash = EnhancementModels.hash(source)
            val activity = instrumentation.startActivitySync(android.content.Intent(context, PluginMainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PluginMainActivity
            try {
                instrumentation.runOnMainSync { activity.setContent { SealTheme {
                    com.plugin.videoenhancement.VideoEnhancementPlugin.VideoAction(source.path) { _, _ -> }
                    com.plugin.videoenhancement.VideoEnhancementResultHost()
                } } }
                tap("AI 동영상 화질개선"); tap("커스텀"); tap("백그라운드에서 계속")
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                val deadline = SystemClock.elapsedRealtime() + 120_000
                while (VideoEnhancementWork.state.value.running && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                val result = VideoEnhancementWork.state.value.preview ?: error(VideoEnhancementWork.state.value.toString())
                assertEquals(hash, result.hash)
                assertEquals(hash, EnhancementModels.hash(source))
                assertTrue(result.image.isFile)
                result.image.copyTo(File(context.cacheDir, "revision12-result.mp4"), overwrite = true)
                val notice = run {
                    val manager = context.getSystemService(NotificationManager::class.java)
                    // 완료 상태와 시스템 알림 등록은 서로 다른 스레드에서 반영됩니다.
                    repeat(100) {
                        manager.activeNotifications.firstOrNull { it.id == 7423 }?.let { return@run it }
                        SystemClock.sleep(50)
                    }
                    error("완료 알림이 없습니다. 알림 허용=${manager.areNotificationsEnabled()}")
                }
                // 앱 프로세스 재생성 후 결과 복구도 같은 비교창을 사용합니다.
                VideoEnhancementWork.state.value = VideoEnhancementWork.Companion.State()
                VideoEnhancementWork.restore(context)
                assertEquals(result.hash, VideoEnhancementWork.state.value.preview?.hash)
                assertNotNull(notice.notification.contentIntent)
                // 시스템 알림 클릭을 사용해 Android 15의 백그라운드 실행 허용 조건도 검증합니다.
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                tap("눌러서 원본과 보정 결과를 비교하세요.")
                await("미리보기 재생 중")
                tap("원본"); await("미리보기 재생 중")
                tap("보정 결과"); await("미리보기 재생 중")
                instrumentation.waitForIdleSync()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                val readyBounds = Rect().also { await("미리보기 재생 중").getBoundsInScreen(it) }
                val density = context.resources.displayMetrics.density
                val previewHeight = (240 * density).toInt()
                val previewBottom = readyBounds.top - (12 * density).toInt()
                var coloredPixels = 0
                // 알려진 색상 패턴의 중앙 영역을 검사해 재생 문구만 뜨는 빈 미리보기를 잡습니다.
                for (y in (previewBottom - previewHeight * 2 / 3).coerceAtLeast(0) until (previewBottom - previewHeight / 3).coerceAtMost(screenshot.height))
                    for (x in screenshot.width / 3 until screenshot.width * 2 / 3) {
                        val pixel = screenshot.getPixel(x, y)
                        val red = android.graphics.Color.red(pixel); val green = android.graphics.Color.green(pixel); val blue = android.graphics.Color.blue(pixel)
                        if (maxOf(red, green, blue) - minOf(red, green, blue) > 40) coloredPixels++
                    }
                File(context.cacheDir, "revision12-comparison.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
                assertTrue("비교창에 실제 영상 프레임이 보이지 않습니다.", coloredPixels > 20)
                // 시스템 뒤로와 바깥 터치는 확인되지 않은 보정 결과를 취소하지 않습니다.
                instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                await("원본과 보정 결과 비교")
                val touchTime = SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(touchTime, SystemClock.uptimeMillis(), action, 2f, 2f, 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    instrumentation.uiAutomation.injectInputEvent(event, true); event.recycle()
                }
                await("원본과 보정 결과 비교")
                tap("닫기")
                assertNull(VideoEnhancementWork.state.value.preview)
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        } finally {
            VideoEnhancementWork.cancel(context)
            VideoEnhancementWork.dismiss(context)
            if (existed) kv.encode(key, accelerated) else kv.removeValueForKey(key)
            root.deleteRecursively()
        }
    }

    /** 합성 이력 7,000개를 한 트랜잭션으로 만들고 30개 표시·스크롤 응답을 확인합니다. */
    @Test fun sevenThousandHistoryRemainsResponsive(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val token = "revision12-${java.util.UUID.randomUUID()}"
        val dao = requireNotNull(TemporarySession.normalDao)
        val initial = dao.getDownloadHistory().size
        val items = (1..7000).map { index -> DownloadedVideoInfo(0, "$token $index", "", "$token/$index", "",
            File(context.cacheDir, "$token/$index.mp4").path, "External") }
        dao.insertAll(items)
        try {
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                val started = SystemClock.elapsedRealtime()
                scenario.onActivity { it.setContent { SealTheme {
                    com.plugin.library.EnhancedVideoListPage(enhanced = false, onNavigateBack = {})
                } } }
                await("표시 30 / ${initial + 7000}")
                val elapsed = SystemClock.elapsedRealtime() - started
                instrumentation.sendStatus(0, android.os.Bundle().apply { putLong("history_7000_first_display_ms", elapsed) })
                assertTrue("7,000개 첫 표시가 15초를 넘었습니다: ${elapsed}ms", elapsed < 15_000)
                assertEquals(initial + 7000, dao.getDownloadHistory().size)
            }
        } finally { dao.deleteInfoList(dao.getDownloadHistory().filter { it.videoTitle.startsWith(token) }) }
    }

    /** 접근성 버튼의 클릭 가능한 부모를 실행합니다. */
    private fun tap(label: String) {
        var node = await(label)
        while (!node.isClickable) node = node.parent ?: error("클릭할 수 없습니다: $label")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** 실제 디코더 준비와 접근성 갱신을 짧은 간격으로 기다립니다. */
    private fun await(label: String): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(150) {
            automation.clearCache()
            find(automation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        val screenshot = automation.takeScreenshot()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "revision12-failure.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        error("화면에서 찾을 수 없습니다: $label · 결과 열기=${VideoEnhancementWork.showResult.value} · ${labels(automation.rootInActiveWindow)}")
    }

    /** 대화상자와 목록에서 텍스트 또는 콘텐츠 설명이 일치하는 노드를 찾습니다. */
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }

    /** 실패 화면의 텍스트를 남겨 알림 재진입과 디코더 오류를 구별합니다. */
    private fun labels(node: AccessibilityNodeInfo?): String {
        if (node == null) return "화면 없음"
        return listOfNotNull(node.text, node.contentDescription).joinToString(" ") +
            (0 until node.childCount).joinToString(" ") { labels(node.getChild(it)) }
    }
}
