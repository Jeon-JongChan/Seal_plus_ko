package com.plugin

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.ui.theme.SealTheme
import com.plugin.compatibility.PostMediaViewer
import com.plugin.compatibility.enhancement.EnhancementModels
import com.plugin.ui.PluginMainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 사진·실제 영상의 확대와 재생/음량 컨트롤 분리를 화면에서 검증합니다. */
class PostViewerUiTest {
    /** 실제 영상이 준비되고 확대·초기화·일시정지가 가능하며 두 슬라이더가 겹치지 않습니다. */
    @Test fun videoZoomAndSeparateControls(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val clip = InstrumentationRegistry.getArguments().getString("viewer_video") ?: error("검사용 영상 경로가 필요합니다.")
        val fixture = File(context.cacheDir, "viewer-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val video = File(fixture, "clip.mp4")
        val image = File(fixture, "image.png")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        try {
            EnhancementModels.stage(context, clip, video, 8 * 1024 * 1024)
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { SealTheme {
                    PostMediaViewer(listOf(video.path, image.path), video.path) {}
                } } }
                await("일시정지")
                val windowBounds = Rect().also { instrumentation.uiAutomation.rootInActiveWindow.getBoundsInScreen(it) }
                val wholeScreen = instrumentation.uiAutomation.takeScreenshot()
                try {
                    assertEquals("뷰어 창이 화면 위를 채우지 않습니다.", 0, windowBounds.top)
                    assertEquals("뷰어 창이 화면 아래를 채우지 않습니다.", wholeScreen.height, windowBounds.bottom)
                } finally { wholeScreen.recycle() }
                tap("일시정지"); await("재생")
                pinchOut()
                val enlarged = Regex("확대 (\\d+)%").find(labels(instrumentation.uiAutomation.rootInActiveWindow))
                    ?.groupValues?.get(1)?.toIntOrNull() ?: error("제스처 확대 표시가 없습니다.")
                assertTrue("두 손가락 제스처로 확대되지 않았습니다.", enlarged > 100)
                repeat(3) {
                    val label = Regex("확대 \\d+%").find(labels(instrumentation.uiAutomation.rootInActiveWindow))?.value
                        ?: error("확대 버튼이 없습니다.")
                    if (label != "확대 100%") tap(label)
                    instrumentation.waitForIdleSync()
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                }
                val seek = Rect().also { await("재생 위치").getBoundsInScreen(it) }
                val volume = Rect().also { await("음량").getBoundsInScreen(it) }
                assertTrue("재생바와 음량바가 겹칩니다.", seek.bottom <= volume.top)
                val zoom = Rect().also { await("확대 100%").getBoundsInScreen(it) }
                assertTrue("확대 배율 표시가 재생 조작부에 닿습니다.", zoom.bottom < seek.top)
                assertTrue("확대 배율 표시가 오른쪽에 없습니다.", zoom.centerX() > windowBounds.centerX())
                val before = instrumentation.uiAutomation.takeScreenshot()
                tap("확대 100%"); await("확대 200%")
                SystemClock.sleep(150)
                val after = instrumentation.uiAutomation.takeScreenshot()
                try {
                    var changed = 0
                    for (y in before.height / 4 until before.height / 2) for (x in before.width / 4 until before.width * 3 / 4)
                        if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
                    assertTrue("실제 영상 화면이 확대되지 않았습니다.", changed > 20)
                } finally { before.recycle(); after.recycle() }
                tap("확대 200%"); await("확대 400%")
                tap("확대 400%"); await("확대 100%")
                val screen = instrumentation.uiAutomation.takeScreenshot()
                File(context.cacheDir, "post-video-controls-ui.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }; screen.recycle()
                // 사진으로 페이지를 넘긴 뒤 영상 컨트롤이 남지 않고 같은 확대 동작을 제공합니다.
                swipeLeft()
                await("2 / 2"); await("확대 100%")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "재생 위치"))
                tap("확대 100%"); await("확대 200%")
            }
        } finally { fixture.walkBottomUp().forEach { it.delete() } }
    }

    /** 두 포인터를 동시에 벌려 버튼과 독립적인 실제 터치 확대를 검사합니다. */
    private fun pinchOut() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val bounds = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
        val centerX = bounds.exactCenterX(); val centerY = bounds.top + bounds.height() * 0.35f
        val start = SystemClock.uptimeMillis()
        val properties = Array(2) { index -> android.view.MotionEvent.PointerProperties().apply {
            id = index; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER
        } }
        /** 터치 순서와 포인터 번호를 실제 Android 다중 터치 규약대로 전달합니다. */
        fun inject(action: Int, count: Int, span: Float) {
            val coordinates = Array(2) { index -> android.view.MotionEvent.PointerCoords().apply {
                x = centerX + if (index == 0) -span else span; y = centerY; pressure = 1f; size = 1f
            } }
            val event = android.view.MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, count, properties,
                coordinates, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        inject(android.view.MotionEvent.ACTION_DOWN, 1, 35f)
        inject(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), 2, 35f)
        for (step in 1..12) { SystemClock.sleep(20); inject(android.view.MotionEvent.ACTION_MOVE, 2, 35f + step * 7f) }
        inject(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl 8), 2, 119f)
        inject(android.view.MotionEvent.ACTION_UP, 1, 119f)
        instrumentation.waitForIdleSync()
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
    }

    /** 확대하지 않은 페이지의 중앙을 왼쪽으로 넘깁니다. */
    private fun swipeLeft() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val bounds = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
        val start = SystemClock.uptimeMillis()
        for (step in 0..10) {
            val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 10 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
            val event = android.view.MotionEvent.obtain(start, start + step * 30L, action,
                bounds.left + bounds.width() * (0.85f - step * 0.07f), bounds.top + bounds.height() * 0.4f, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            automation.injectInputEvent(event, true); event.recycle()
        }
    }

    /** 콘텐츠 설명의 클릭 가능한 부모 버튼을 사용합니다. */
    private fun tap(label: String) {
        var node = await(label)
        while (!node.isClickable) node = node.parent ?: error("버튼이 없습니다: $label")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /** 네이티브 재생 준비와 Compose 접근성 트리를 기다립니다. */
    private fun await(label: String): AccessibilityNodeInfo {
        repeat(200) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            // 영상의 고정 창은 접근성 캐시에 이전 버튼 설명이 남아 실제 화면과 다를 수 있습니다.
            if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
            find(automation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        error("화면에서 찾을 수 없습니다: $label · ${labels(root)}")
    }

    /** 검사 실패 시 실제 전경 화면의 텍스트를 기록해 시스템 창과 앱 상태를 구별합니다. */
    private fun labels(node: AccessibilityNodeInfo?): String {
        if (node == null) return "화면 없음"
        return listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ") +
            (0 until node.childCount).joinToString(" ") { labels(node.getChild(it)) }
    }

    /** 텍스트·접근성 설명으로 화면 노드를 찾습니다. */
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}
