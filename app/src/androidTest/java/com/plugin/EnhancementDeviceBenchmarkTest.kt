package com.plugin

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.plugin.enhancement.*
import com.google.ai.edge.litert.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** 사용자 사진과 분리한 샘플로 기기 가속 경로·준비 시간·동일 입력 추론을 측정합니다. */
class EnhancementDeviceBenchmarkTest {
    /** 작은 자연스러운 시험 입력으로 최소·최대 프리셋의 파일 준비·출력 저장까지 포함합니다. */
    @Test fun minAndMaxPresetsComplete(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val fixture = File(context.cacheDir, "ai-presets-" + UUID.randomUUID()).apply { mkdirs() }
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val keys = listOf(EnhancementModels.SELECTED, EnhancementModels.BACKEND, EnhancementModels.ACCELERATED, EnhancementModels.GOOGLE_FIRST)
        val existed = keys.filter(kv::containsKey).toSet()
        val selected = kv.decodeString(keys[0], ""); val backend = kv.decodeString(keys[1], "litert")
        val accelerated = kv.decodeBool(keys[2], true); val google = kv.decodeBool(keys[3], false)
        try {
            kv.encode(keys[0], EnhancementModels.DEFAULT_MODEL); kv.encode(keys[1], "litert")
            kv.encode(keys[2], true); kv.encode(keys[3], false)
            val file = File(fixture, "sample.png")
            val bitmap = Bitmap.createBitmap(IntArray(960 * 540) { i ->
                Color.rgb(i % 960 * 255 / 959, i / 960 * 255 / 539, 120)
            }, 960, 540, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val original = file.readBytes()
            for (option in listOf(EnhancementRules.MINIMUM, EnhancementRules.MAXIMUM)) {
                val row = JSONObject().put("option", if (option == EnhancementRules.MINIMUM) "최소" else "최대").put("source", "960x540")
                val before = SystemClock.elapsedRealtimeNanos()
                val preview = EnhancementClient.enhance(context, file.path, option) {}
                try {
                    row.put("total_ms", (SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0)
                        .put("target", "${preview.width}x${preview.height}").put("engine", preview.engine)
                    assertTrue(preview.image.isFile)
                    assertNotEquals(android.os.Process.myPid(), preview.workerPid)
                    assertArrayEquals(original, file.readBytes())
                    instrumentation.sendStatus(0, Bundle().apply { putString("preset_benchmark", row.toString()) })
                } finally { EnhancementClient.discard(preview) }
                SystemClock.sleep(1000)
            }
        } finally {
            kv.encode(keys[0], selected); kv.encode(keys[1], backend); kv.encode(keys[2], accelerated); kv.encode(keys[3], google)
            (keys - existed).forEach(kv::removeValueForKey)
            fixture.walkBottomUp().forEach { it.delete() }
        }
    }

    /** GPU와 NPU를 각각 단독 요청해 실행 성공과 지연을 구분해 기록합니다. */
    @Test fun acceleratorTileLatency() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "ai-benchmark-" + UUID.randomUUID()).apply { mkdirs() }
        val rows = JSONArray()
        try {
            val model = File(fixture, "model.tflite")
            context.assets.open("image-ai/realesr-general-x4v3-mobile.tflite").use { input -> model.outputStream().use { input.copyTo(it) } }
            val values = FloatArray(192 * 192 * 3) { (it % 255) / 255f }
            var reference: FloatArray? = null
            val cases = listOf(
                "GPU FP32" to CompiledModel.GpuOptions.Precision.FP32,
                "GPU FP16" to CompiledModel.GpuOptions.Precision.FP16,
                "GPU FP16 누적 FP32" to CompiledModel.GpuOptions.Precision.FP16_WITH_FP32_ACCUM,
                "NPU" to null)
            for ((label, precision) in cases) {
                val accelerator = if (precision == null) Accelerator.NPU else Accelerator.GPU
                val row = JSONObject().put("route", label)
                val started = SystemClock.elapsedRealtimeNanos()
                try {
                    val environment = if (accelerator == Accelerator.NPU) Environment.create(context, BuiltinNpuAcceleratorProvider(context))
                        else Environment.create(context)
                    environment.use { env ->
                        row.put("available", env.getAvailableAccelerators().map { it.name }.joinToString(","))
                        val options = CompiledModel.Options(setOf(accelerator)).apply {
                            if (precision != null) gpuOptions = CompiledModel.GpuOptions(precision = precision)
                        }
                        CompiledModel.create(model.path, options, env).use { compiled ->
                            val input = compiled.createInputBuffers(0)
                            val output = compiled.createOutputBuffers(0)
                            try {
                                row.put("prepare_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                                val samples = JSONArray()
                                repeat(4) {
                                    val before = SystemClock.elapsedRealtimeNanos()
                                    input.single().writeFloat(values)
                                    compiled.run(input, output, 0)
                                    val result = output.single().readFloat()
                                    assertEquals(768 * 768 * 3, result.size)
                                    assertTrue(result.all { it.isFinite() })
                                    samples.put((SystemClock.elapsedRealtimeNanos() - before) / 1_000_000.0)
                                    if (it == 3) {
                                        val baseline = reference
                                        if (baseline == null && label == "GPU FP32") reference = result
                                        else if (baseline != null) row.put("max_color_delta_vs_fp32", result.indices.maxOf { index ->
                                            kotlin.math.abs((result[index].coerceIn(0f, 1f) * 255).toInt() -
                                                (baseline[index].coerceIn(0f, 1f) * 255).toInt())
                                        })
                                    }
                                }
                                row.put("inference_ms", samples).put("completed", true)
                            } finally { input.forEach { it.close() }; output.forEach { it.close() } }
                        }
                    }
                } catch (failure: Exception) { row.put("completed", false).put("error", failure.message) }
                catch (failure: LinkageError) { row.put("completed", false).put("error", failure.message) }
                rows.put(row)
                instrumentation.sendStatus(0, Bundle().apply { putString("accelerator_benchmark", row.toString()) })
                // 짧은 측정 사이에도 반복 부하를 완화합니다.
                SystemClock.sleep(1000)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("accelerator_results", rows.toString()) })
        } finally { fixture.walkBottomUp().forEach { it.delete() } }
    }

    /** 같은 640×360 이미지를 2배로 처리해 LiteRT와 ONNX NNAPI의 전체 모델 처리 시간을 비교합니다. */
    @Test fun sameImagePipelineLatency(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "ai-pipeline-" + UUID.randomUUID()).apply { mkdirs() }
        val source = Bitmap.createBitmap(IntArray(640 * 360) { i ->
            Color.rgb(i % 640 * 255 / 639, i / 640 * 255 / 359, 120)
        }, 640, 360, Bitmap.Config.ARGB_8888)
        try {
            val onnx = File(fixture, "model.onnx"); val lite = File(fixture, "model.tflite")
            context.assets.open("image-ai/realesr-general-x4v3-mobile.onnx").use { input -> onnx.outputStream().use { input.copyTo(it) } }
            context.assets.open("image-ai/realesr-general-x4v3-mobile.tflite").use { input -> lite.outputStream().use { input.copyTo(it) } }
            for (route in listOf("LiteRT GPU", "LiteRT 자동", "ONNX NNAPI")) {
                val row = JSONObject().put("route", route).put("source", "640x360").put("target", "1280x720")
                var engine = "초기화"
                var result: Bitmap? = null
                val started = SystemClock.elapsedRealtimeNanos()
                try {
                    result = if (route == "ONNX NNAPI") OnnxEnhancer.process(source, onnx.path, 1280 to 720, {}, true, { engine = it }, 2)
                        else LiteRtEnhancer.process(context, source, lite.path, 1280 to 720, {}, true, 2, route != "LiteRT GPU") { engine = it }
                    assertEquals(1280, result.width); assertEquals(720, result.height)
                    row.put("completed", true)
                } catch (failure: Exception) { row.put("completed", false).put("error", failure.message) }
                catch (failure: LinkageError) { row.put("completed", false).put("error", failure.message) }
                finally {
                    result?.recycle()
                    row.put("total_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0).put("engine", engine)
                    instrumentation.sendStatus(0, Bundle().apply { putString("pipeline_benchmark", row.toString()) })
                }
                SystemClock.sleep(1000)
            }
        } finally { source.recycle(); fixture.walkBottomUp().forEach { it.delete() } }
    }
}
