package com.plugin

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.plugin.enhancement.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Android의 실제 LiteRT v2 CPU 결과를 ONNX와 비교하고 분할·결합 품질을 확인합니다. */
class LiteRtExecutionTest {
    /** 테스트 전용 보정 프로세스를 종료시켜 GPU·CPU 새 프로세스 재시도를 확인합니다. */
    @Test fun nativeWorkerTerminationRecovers(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicecheck") || context.packageName.endsWith(".debug"))
        val fixture = File(context.cacheDir, "litert-recovery-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val keys = listOf(EnhancementModels.SELECTED, EnhancementModels.ACCELERATED, EnhancementModels.GOOGLE_FIRST, EnhancementModels.BACKEND, EnhancementModels.NPU_FIRST)
        val existed = keys.filter(kv::containsKey).toSet()
        val selected = kv.decodeString(keys[0], ""); val accelerated = kv.decodeBool(keys[1], true)
        val google = kv.decodeBool(keys[2], false); val backend = kv.decodeString(keys[3], "litert")
        val npuFirst = kv.decodeBool(keys[4], false)
        var preview: EnhancementClient.Preview? = null
        var killed = false
        try {
            kv.encode(keys[0], EnhancementModels.DEFAULT_MODEL); kv.encode(keys[1], true)
            kv.encode(keys[2], false); kv.encode(keys[3], "litert")
            kv.encode(keys[4], true)
            val source = File(fixture, "source.png")
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val bytes = source.readBytes()
            preview = EnhancementClient.enhance(context, source.path, 2, onEngine = { engine ->
                if (!killed && engine == "LiteRT NPU·GPU 초기화 중") {
                    val processes = context.getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                    val worker = processes?.firstOrNull { it.processName == "${context.packageName}:image_ai" }
                        ?: error("검사용 보정 프로세스가 없습니다.")
                    check(worker.pid != android.os.Process.myPid())
                    killed = true
                    android.os.Process.killProcess(worker.pid)
                }
            }) {}
            assertTrue(killed)
            assertEquals(64, preview.width); assertEquals(48, preview.height)
            assertTrue(preview.engine.startsWith("프로세스 종료 후 재시도"))
            assertArrayEquals(bytes, source.readBytes())
            assertNotEquals(android.os.Process.myPid(), preview.workerPid)
        } finally {
            preview?.let(EnhancementClient::discard)
            kv.encode(keys[0], selected); kv.encode(keys[1], accelerated); kv.encode(keys[2], google); kv.encode(keys[3], backend)
            kv.encode(keys[4], npuFirst)
            (keys - existed).forEach(kv::removeValueForKey)
            fixture.walkBottomUp().forEach { it.delete() }
        }
    }

    /** 두 엔진에서 같은 타일·픽셀 위치와 원본 보존을 확인합니다. */
    @Test fun liteRtMatchesOnnx(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "litert-compare-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val pixels = IntArray(192 * 160) { i -> Color.rgb(i % 192, i / 192, 100) }
        val source = Bitmap.createBitmap(pixels, 192, 160, Bitmap.Config.ARGB_8888)
        var old: Bitmap? = null; var new: Bitmap? = null
        try {
            // 모델 없이 항등 타일을 결합해 두 채널 배치의 모서리·겹침 좌표를 독립적으로 검사합니다.
            for (channelsFirst in listOf(true, false)) {
                val identity = ImageTiles.process(source, 192 to 160, 48, 48, channelsFirst, {}, {}) { values, consume ->
                    consume(values, 48, 48)
                }
                try {
                    val actual = IntArray(pixels.size); identity.getPixels(actual, 0, 192, 0, 0, 192, 160)
                    assertArrayEquals(pixels, actual)
                } finally { identity.recycle() }
            }
            val onnx = File(fixture, "model.onnx"); val lite = File(fixture, "model.tflite")
            EnhancementModels.stage(context, EnhancementModels.DEFAULT_MODEL, onnx)
            EnhancementModels.stage(context, EnhancementModels.LITERT_MODEL, lite)
            old = OnnxEnhancer.process(source, onnx.path, 384 to 320, {}, false, {}, cpuThreads = 1)
            var engine = ""
            new = LiteRtEnhancer.process(context, source, lite.path, 384 to 320, {}, false, 1) { engine = it }
            assertTrue(engine.startsWith("실제 실행") && engine.contains("LiteRT") && engine.contains("1 스레드"))
            val a = IntArray(384 * 320); val b = IntArray(a.size)
            old.getPixels(a, 0, 384, 0, 0, 384, 320); new.getPixels(b, 0, 384, 0, 0, 384, 320)
            val error = a.indices.maxOf { i -> maxOf(kotlin.math.abs(Color.red(a[i]) - Color.red(b[i])),
                kotlin.math.abs(Color.green(a[i]) - Color.green(b[i])), kotlin.math.abs(Color.blue(a[i]) - Color.blue(b[i]))) }
            assertTrue("엔진 간 최대 색상 차이: $error", error <= 1)
            File(context.cacheDir, "litert-onnx-color-error.txt").writeText(error.toString())
            val original = IntArray(pixels.size); source.getPixels(original, 0, 192, 0, 0, 192, 160)
            assertArrayEquals(pixels, original)
        } finally { old?.recycle(); new?.recycle(); source.recycle(); fixture.walkBottomUp().forEach { it.delete() } }
    }

    /** 실제 가속 요청이 장치 미지원일 때도 대체 엔진으로 완료되고 요청 설정이 보존되는지 확인합니다. */
    @Test fun acceleratedRequestRunsOrFallsBack(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "litert-gpu-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val keys = listOf(EnhancementModels.SELECTED, EnhancementModels.ACCELERATED, EnhancementModels.GOOGLE_FIRST, EnhancementModels.CPU_THREADS, EnhancementModels.BACKEND)
        val existed = keys.filter(kv::containsKey).toSet()
        val model = kv.decodeString(keys[0], ""); val accelerated = kv.decodeBool(keys[1], true)
        val google = kv.decodeBool(keys[2], false); val threads = kv.decodeInt(keys[3], 2)
        val backend = kv.decodeString(keys[4], "litert")
        var preview: EnhancementClient.Preview? = null
        try {
            kv.encode(keys[0], EnhancementModels.DEFAULT_MODEL); kv.encode(keys[1], true)
            kv.encode(keys[2], false); kv.encode(keys[3], 1); kv.encode(keys[4], "litert")
            val file = File(fixture, "source.png")
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            preview = EnhancementClient.enhance(context, file.path, 2) {}
            assertEquals(64, preview.width); assertEquals(64, preview.height)
            assertTrue(preview.engine.contains("실행") && (preview.engine.contains("LiteRT v2") || preview.engine.startsWith("LiteRT 대체")))
            assertNotEquals(android.os.Process.myPid(), preview.workerPid)
            assertTrue(kv.decodeBool(keys[1]))
            File(context.cacheDir, "litert-accelerated-engine.txt").writeText(preview.engine)
        } finally {
            preview?.let(EnhancementClient::discard)
            kv.encode(keys[0], model); kv.encode(keys[1], accelerated); kv.encode(keys[2], google)
            kv.encode(keys[3], threads); kv.encode(keys[4], backend)
            keys.filterNot { it in existed }.forEach(kv::removeValueForKey)
            source.recycle(); fixture.walkBottomUp().forEach { it.delete() }
        }
    }
}
