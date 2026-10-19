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
import com.plugin.compatibility.enhancement.*
import com.plugin.enhancement.ImageEnhancementPlugin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 실제 ONNX 모델·서비스 분리·취소·확정 저장을 Android에서 검증합니다. */
class ImageEnhancementTest {
    /** 전체 아이콘·미리보기·복제 저장이 게시글의 각 원본과 연결되고 영상은 제외됩니다. */
    @Test fun postImagesShowPreviewAndSaveAllCopies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val fixture = File(context.cacheDir, "post-ai-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val images = listOf("one.png", "two.png").map { name -> File(fixture, name).apply {
            val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        } }
        val originals = images.map { it.readBytes() }
        val saved = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val keys = listOf(EnhancementModels.SELECTED, EnhancementModels.ACCELERATED, EnhancementModels.GOOGLE_FIRST, EnhancementModels.BACKEND)
        val existed = keys.filter(kv::containsKey).toSet()
        val model = kv.decodeString(keys[0], ""); val accelerated = kv.decodeBool(keys[1], true)
        val google = kv.decodeBool(keys[2], false); val backend = kv.decodeString(keys[3], "litert")
        try {
            kv.encode(keys[0], EnhancementModels.DEFAULT_MODEL); kv.encode(keys[1], false)
            kv.encode(keys[2], false); kv.encode(keys[3], "litert")
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { SealTheme {
                    ImageEnhancementPlugin.PostImageAction(images.map { it.path } + listOf(File(fixture, "clip.mp4").path, File(fixture, "NOFACE/ignored.png").path)) { source, output, replace ->
                        assertFalse(replace); saved += source to output
                    }
                } } }
                tap("게시글 전체 화질 개선"); awaitNode("최소"); tap("최소")
                awaitNode("전체 복제"); tap("다음"); awaitNode("2 / 2장")
                // 두 번째 원본 변경으로 부분 저장 실패를 만들고 성공한 첫 복제가 다시 저장되지 않는지 확인합니다.
                images[1].appendBytes(byteArrayOf(0))
                tap("전체 복제")
                awaitNode("1장 저장 완료 · 나머지 1장: 원본이 변경되었습니다. 다시 보정한 뒤 저장하세요.")
                assertEquals(1, saved.size)
                images[1].writeBytes(originals[1])
                tap("전체 복제")
                repeat(200) { if (saved.size < 2) SystemClock.sleep(100) }
                assertEquals(images.map { it.path }, saved.map { it.first })
                images.forEachIndexed { index, file -> assertArrayEquals(originals[index], file.readBytes()) }
                saved.forEach { (_, output) ->
                    val bitmap = android.graphics.BitmapFactory.decodeFile(output)
                    try { assertEquals(32, bitmap.width); assertEquals(24, bitmap.height) } finally { bitmap.recycle() }
                }
            }
            runBlocking {
                val before = File(context.cacheDir, "image-ai").listFiles().orEmpty().map { it.name }.toSet()
                assertTrue(runCatching { EnhancementClient.enhancePost(context, images.map { it.path } + File(fixture, "missing.png").path, 2) { _, _, _ -> } }.isFailure)
                // 임시 결과 정리는 별도 IO 코루틴이므로 실제 완료를 기다린 뒤 잔여 파일을 검사합니다.
                kotlinx.coroutines.withTimeout(5_000) {
                    while (before != File(context.cacheDir, "image-ai").listFiles().orEmpty().map { it.name }.toSet()) kotlinx.coroutines.delay(50)
                }
                assertEquals(before, File(context.cacheDir, "image-ai").listFiles().orEmpty().map { it.name }.toSet())
            }
        } finally {
            kv.encode(keys[0], model); kv.encode(keys[1], accelerated); kv.encode(keys[2], google); kv.encode(keys[3], backend)
            (keys - existed).forEach(kv::removeValueForKey)
            fixture.walkBottomUp().forEach { it.delete() }
        }
    }
    /** 내장 기본 모델의 실제 추론과 첫 타일 이후 CPU 실행 기록·진행 알림을 확인합니다. */
    @Test fun bundledModelReportsActualExecution(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "ai-mobile-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val source = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val messages = mutableListOf<String>()
        var progressAfterMeasurement = false
        try {
            val model = File(fixture, "model.onnx")
            EnhancementModels.stage(context, EnhancementModels.DEFAULT_MODEL, model)
            val result = OnnxEnhancer.process(source, model.path, 96 to 96,
                { if (it > 0 && messages.any { message -> message.startsWith("실제 실행") }) progressAfterMeasurement = true },
                false, { messages += it }, cpuThreads = 1)
            try { assertEquals(96, result.width); assertEquals(96, result.height) } finally { result.recycle() }
            assertTrue(messages.any { it.startsWith("실제 실행 · CPU") && it.contains("1 스레드") })
            assertTrue(progressAfterMeasurement)
            assertTrue(fixture.listFiles().orEmpty().none { it.name.startsWith("execution-profile") })
            val kv = com.tencent.mmkv.MMKV.defaultMMKV()
            val keys = listOf(EnhancementModels.SELECTED, EnhancementModels.ACCELERATED, EnhancementModels.GOOGLE_FIRST, EnhancementModels.CPU_THREADS, EnhancementModels.BACKEND)
            val existed = keys.filter(kv::containsKey).toSet()
            val previousModel = kv.decodeString(keys[0], "")
            val previousAcceleration = kv.decodeBool(keys[1], true)
            val previousGoogle = kv.decodeBool(keys[2], false)
            val previousThreads = kv.decodeInt(keys[3], 2)
            val previousBackend = kv.decodeString(keys[4], "litert")
            var preview: EnhancementClient.Preview? = null
            try {
                kv.encode(keys[0], EnhancementModels.DEFAULT_MODEL); kv.encode(keys[1], false)
                // Google 우선 설정이 남아 있어도 CPU 전용 요청은 선택 엔진으로 실행해야 합니다.
                kv.encode(keys[2], true); kv.encode(keys[3], 1); kv.encode(keys[4], "litert")
                val file = File(fixture, "source.png")
                file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
                messages.clear(); progressAfterMeasurement = false
                preview = EnhancementClient.enhance(context, file.path, 2, onEngine = { messages += it }) {
                    if (it > 0 && messages.any { message -> message.startsWith("실제 실행") }) progressAfterMeasurement = true
                }
                assertTrue(progressAfterMeasurement)
                assertTrue(preview.engine.startsWith("실제 실행") && preview.engine.contains("LiteRT") && preview.engine.contains("CPU"))
                assertNotEquals(android.os.Process.myPid(), preview.workerPid)
            } finally {
                preview?.let(EnhancementClient::discard)
                kv.encode(keys[0], previousModel); kv.encode(keys[1], previousAcceleration)
                kv.encode(keys[2], previousGoogle); kv.encode(keys[3], previousThreads); kv.encode(keys[4], previousBackend)
                keys.filterNot { it in existed }.forEach(kv::removeValueForKey)
            }
        } finally { source.recycle(); fixture.walkBottomUp().forEach { it.delete() } }
    }

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
                val result = com.plugin.compatibility.enhancement.OnnxEnhancer.process(source, model, 256 to 256, {}, false, {})
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
        repeat(300) {
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
