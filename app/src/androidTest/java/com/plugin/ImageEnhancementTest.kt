package com.plugin

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import com.plugin.ui.PluginMainActivity
import com.junkfood.seal.ui.theme.SealTheme
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.plugin.compatibility.*
import com.plugin.enhancement.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 실제 ONNX 모델·서비스 분리·취소·확정 저장을 Android에서 검증합니다. */
class ImageEnhancementTest {
    /** 동일 모델·입력의 추론 시간을 세 번 측정하고 변경 전후 색상 오차·원본 보존을 기록합니다. */
    @Test fun benchmarkTiles(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val model = args.getString("ai_model") ?: error("모델 경로가 필요합니다.")
        val phase = args.getString("ai_benchmark_phase") ?: "before"
        val colors = IntArray(128 * 128) { index -> Color.rgb(index % 128 * 2, index / 128 * 2, 100) }
        val source = Bitmap.createBitmap(colors, 128, 128, Bitmap.Config.ARGB_8888)
        val times = mutableListOf<Long>()
        val baseline = File(context.cacheDir, "ai-performance-before.png")
        try {
            repeat(4) { pass ->
                val start = android.os.SystemClock.elapsedRealtime()
                val result = com.plugin.enhancement.OnnxEnhancer.process(source, model, 256 to 256, {}, false, {})
                try {
                    if (pass > 0) times += android.os.SystemClock.elapsedRealtime() - start
                    assertEquals(256, result.width); assertEquals(256, result.height)
                    if (pass == 3 && phase == "before") baseline.outputStream().use { assertTrue(result.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    if (pass == 3 && phase == "after") {
                        val old = android.graphics.BitmapFactory.decodeFile(baseline.path)
                        try {
                            val a = IntArray(256 * 256); val b = IntArray(a.size)
                            old.getPixels(a, 0, 256, 0, 0, 256, 256); result.getPixels(b, 0, 256, 0, 0, 256, 256)
                            val error = a.indices.sumOf { kotlin.math.abs(Color.red(a[it]) - Color.red(b[it])) + kotlin.math.abs(Color.green(a[it]) - Color.green(b[it])) + kotlin.math.abs(Color.blue(a[it]) - Color.blue(b[it])) }.toDouble() / (a.size * 3)
                            assertTrue("평균 색상 오차: $error", error < 3.0)
                            File(context.cacheDir, "ai-performance-error.txt").writeText(error.toString())
                        } finally { old.recycle() }
                    }
                } finally { result.recycle() }
            }
            val check = IntArray(colors.size); source.getPixels(check, 0, 128, 0, 0, 128, 128)
            assertArrayEquals(colors, check)
            File(context.cacheDir, "ai-performance-$phase.txt").writeText(times.joinToString(","))
        } finally { source.recycle() }
    }

    /** 간편 보정·직접 해상도 선택 UI가 작은 화면에서 접근 가능한지 확인합니다. */
    @Test
    fun imageActionsShowSimpleAndAdvancedChoices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { SealTheme { ImageEnhancementPlugin.ImageAction(File(context.cacheDir, "ai-ui.png").path) { _, _ -> } } }
            }
            tap("이미지 AI 보정")
            awaitNode("최소"); awaitNode("최대")
            tap("직접 설정")
            awaitNode("크기 유지 · 화질 개선")
            tap("해상도·배율 선택")
            awaitNode("720p"); awaitNode("4K (2160p)"); awaitNode("8배")
            tap("간편 설정으로")
            awaitNode("최소")
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(context.cacheDir, "ai-simple-ui.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    /** 아이콘·텍스트 자식 대신 실제 클릭 가능한 상위 버튼에 동작을 전달합니다. */
    private fun tap(label: String) {
        var node = awaitNode(label)
        while (!node.isClickable) node = node.parent ?: error("클릭 가능한 버튼이 없습니다: $label")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /** 접근성 트리가 갱신될 때까지 지정한 버튼을 찾습니다. */
    private fun awaitNode(label: String): AccessibilityNodeInfo {
        repeat(100) {
            findNode(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        error("화면에서 찾을 수 없습니다: $label")
    }

    /** 텍스트 또는 콘텐츠 설명으로 Compose 접근성 노드를 찾습니다. */
    private fun findNode(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) findNode(node.getChild(index), label)?.let { return it }
        return null
    }

    /** 실행 중 보정을 취소해도 원본은 남고 다음 작업을 시작할 수 있습니다. */
    @Test
    fun cancelWorkerAndStartAnotherPreview() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = InstrumentationRegistry.getArguments().getString("ai_model") ?: error("ai_model 경로가 필요합니다.")
        val previousModel = EnhancementModels.SELECTED.getString()
        val previousAcceleration = EnhancementModels.ACCELERATED.getBoolean(true)
        val previousGoogle = EnhancementModels.GOOGLE_FIRST.getBoolean()
        val source = File(context.cacheDir, "ai-cancel.png")
        var preview: EnhancementClient.Preview? = null
        try {
            EnhancementModels.ACCELERATED.updateBoolean(false)
            Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(Color.BLUE)
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            EnhancementModels.SELECTED.updateString(model); EnhancementModels.GOOGLE_FIRST.updateBoolean(false)
            val hash = EnhancementModels.hash(source)
            val progress = kotlinx.coroutines.CompletableDeferred<Unit>()
            val worker = async(kotlinx.coroutines.Dispatchers.Default) {
                EnhancementClient.enhance(context, source.path, 8) { progress.complete(Unit) }
            }
            kotlinx.coroutines.withTimeout(60_000) { progress.await() }
            worker.cancel(); worker.join()
            assertTrue(worker.isCancelled); assertEquals(hash, EnhancementModels.hash(source))
            kotlinx.coroutines.delay(300)
            EnhancementModels.ACCELERATED.updateBoolean(true)
            preview = EnhancementClient.enhance(context, source.path, 1) {}
            assertTrue(preview.engine.startsWith("ONNX · "))
            assertEquals(160, preview.width); assertEquals(120, preview.height)
            assertEquals(hash, EnhancementModels.hash(source))
        } finally {
            EnhancementModels.ACCELERATED.updateBoolean(previousAcceleration)
            EnhancementModels.SELECTED.updateString(previousModel); EnhancementModels.GOOGLE_FIRST.updateBoolean(previousGoogle)
            preview?.let(EnhancementClient::discard); source.delete()
        }
    }

    /** 별도 프로세스 추론과 파일 승인 경계를 검사하고 테스트 설정·이력을 복원합니다. */
    @Test
    fun realModelPreviewAndCommitPreserveSource() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = InstrumentationRegistry.getArguments().getString("ai_model") ?: error("-e ai_model 모델경로가 필요합니다.")
        com.plugin.runtime.PluginAppHelper.initDownloader(context)
        val previousModel = EnhancementModels.SELECTED.getString()
        val previousAcceleration = EnhancementModels.ACCELERATED.getBoolean(true)
        val previousGoogle = EnhancementModels.GOOGLE_FIRST.getBoolean()
        val directory = File(context.cacheDir, "ai-regression").apply { mkdirs() }
        val source = File(directory, "원본 [ai123].png")
        var preview: EnhancementClient.Preview? = null
        var duplicate: String? = null
        try {
            EnhancementModels.ACCELERATED.updateBoolean(false)
            Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(Color.rgb(120, 160, 200))
                bitmap.setPixel(0, 0, Color.TRANSPARENT)
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, "AI 검사", "", "https://example.invalid/ai123", "", source.path, "External"))
            val originalHash = EnhancementModels.hash(source)
            EnhancementModels.SELECTED.updateString(model)
            EnhancementModels.GOOGLE_FIRST.updateBoolean(false)
            preview = EnhancementClient.enhance(context, source.path, 2) {}
            assertEquals(32, preview.width); assertEquals(24, preview.height)
            assertEquals("ONNX · CPU", preview.engine)
            assertNotEquals(android.os.Process.myPid(), preview.workerPid)
            assertEquals(originalHash, EnhancementModels.hash(source))
            assertTrue(preview.image.length() > 0)
            duplicate = EnhancementFiles.save(context, preview, false)
            assertEquals(originalHash, EnhancementModels.hash(source))
            assertEquals(32, PluginMediaMetadataStore.get(duplicate)?.quality?.width)
            assertTrue(File(duplicate).isFile)
            android.graphics.BitmapFactory.decodeFile(duplicate).let { result ->
                assertEquals(0, Color.alpha(result.getPixel(0, 0))); result.recycle()
            }
            // 미리보기 이후 원본이 바뀌면 교체를 거부하고 새 원본을 그대로 보존합니다.
            source.appendText("변경")
            val changedHash = EnhancementModels.hash(source)
            assertTrue(runCatching { EnhancementFiles.save(context, preview, true) }.exceptionOrNull() is IllegalStateException)
            assertEquals(changedHash, EnhancementModels.hash(source))
        } finally {
            EnhancementModels.ACCELERATED.updateBoolean(previousAcceleration)
            EnhancementModels.SELECTED.updateString(previousModel)
            EnhancementModels.GOOGLE_FIRST.updateBoolean(previousGoogle)
            preview?.let(EnhancementClient::discard)
            PluginDatabaseUtil.deleteInfoByPath(source.path)
            duplicate?.let { PluginDatabaseUtil.deleteInfoByPath(it) }
            directory.deleteRecursively()
        }
    }
}
