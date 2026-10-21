package com.plugin.compatibility.enhancement

import ai.onnxruntime.*
import ai.onnxruntime.providers.NNAPIFlags
import android.os.Build
import java.util.EnumSet
import android.graphics.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import org.json.JSONArray

/** RGB/NCHW float32 단일 입출력 모델을 작은 겹침 타일로 실행합니다. */
object OnnxEnhancer {
    /** 지원 GPU·NPU를 자동 활용하고 네이티브 가속 오류일 때만 CPU로 재시도합니다. */
    suspend fun process(source: Bitmap, model: String, target: Pair<Int, Int>, progress: (Int) -> Unit,
        accelerated: Boolean, engine: (String) -> Unit, cpuThreads: Int = 2, sampling: Int = EnhancementSampling.BILINEAR): Bitmap {
        if (accelerated && Build.VERSION.SDK_INT >= 29 && OrtProvider.NNAPI in OrtEnvironment.getAvailableProviders()) {
            try {
                engine("NNAPI 가속 요청 · 첫 타일 실행 확인 중")
                return run(source, model, target, progress, true, engine, cpuThreads, sampling)
            } catch (_: OrtException) {
                // 가속 드라이버·모델 지원 오류는 원본에서 CPU로 다시 시작합니다.
                currentCoroutineContext().ensureActive()
            }
        }
        engine(if (accelerated) "CPU 대체 요청 · 첫 타일 실행 확인 중" else "CPU 요청 · 첫 타일 실행 확인 중")
        return run(source, model, target, progress, false, engine, cpuThreads, sampling)
    }

    /** 모델 세션과 타일 버퍼를 요청 안에서만 소유해 다운로드와 실행 자원을 공유하지 않습니다. */
    private suspend fun run(source: Bitmap, model: String, target: Pair<Int, Int>, progress: (Int) -> Unit, accelerated: Boolean, engine: (String) -> Unit, cpuThreads: Int, sampling: Int): Bitmap {
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            if (accelerated) {
                // 알려진 가변 차원은 세션 최적화 전에 고정해 NNAPI 연산 배정을 가능하게 합니다.
                options.setSymbolicDimensionValue("batch", 1)
                options.setSymbolicDimensionValue("height", 192)
                options.setSymbolicDimensionValue("width", 192)
                options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
            }
            options.enableProfiling(File(File(model).parentFile, "execution-profile").path)
            // 순수 CPU와 가속되지 않는 연산에 사용자 스레드 설정을 동일하게 적용합니다.
            options.setIntraOpNumThreads(EnhancementRules.cpuThreads(cpuThreads, Runtime.getRuntime().availableProcessors()))
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            environment.createSession(model, options).use { session ->
                require(session.inputInfo.size == 1 && session.outputInfo.size == 1) { "단일 이미지 입출력 모델만 지원합니다." }
                val info = session.inputInfo.values.single().info as? TensorInfo ?: error("이미지 텐서 모델이 아닙니다.")
                require(info.type == OnnxJavaType.FLOAT && info.shape.size == 4 && info.shape[0] in listOf(-1L, 1L) && info.shape[1] == 3L) {
                    "RGB/NCHW float32 ONNX 모델이 필요합니다."
                }
                val fixedHeight = info.shape[2].toInt(); val fixedWidth = info.shape[3].toInt()
                val limit = if (Runtime.getRuntime().maxMemory() >= 256L * 1024 * 1024) 192 else 128
                val tileHeight = if (fixedHeight > 0) fixedHeight else (source.height + 32).coerceIn(48, limit)
                val tileWidth = if (fixedWidth > 0) fixedWidth else (source.width + 32).coerceIn(48, limit)
                require(tileWidth in 48..256 && tileHeight in 48..256) { "모델 타일 크기는 48~256픽셀이어야 합니다." }
                var measured = false
                val inspect = {
                    if (!measured) {
                        measured = true
                        val profile = File(session.endProfiling())
                        try {
                            check(profile.length() <= 2 * 1024 * 1024) { "실행 기록이 너무 큽니다." }
                            val events = JSONArray(profile.readText())
                            val providers = (0 until events.length()).mapNotNull {
                                events.optJSONObject(it)?.optJSONObject("args")?.optString("provider")?.takeIf(String::isNotBlank)
                            }.groupingBy { it }.eachCount()
                            val cpu = providers["CPUExecutionProvider"] ?: 0
                            val nnapi = providers["NnapiExecutionProvider"] ?: providers["NNAPIExecutionProvider"] ?: 0
                            engine(if (nnapi > 0) "실제 실행 · NNAPI $nnapi / CPU $cpu 단위 · GPU/NPU 종류는 확인 불가"
                                else if (cpu > 0) "실제 실행 · CPU $cpu 단위 · ${EnhancementRules.cpuThreads(cpuThreads, Runtime.getRuntime().availableProcessors())} 스레드"
                                else "실행 장치 기록을 확인할 수 없습니다.")
                        } catch (_: Exception) { engine("실행 장치 기록을 확인할 수 없습니다.") }
                        finally { profile.delete() }
                    }
                }
                val buffer = java.nio.ByteBuffer.allocateDirect(tileWidth * tileHeight * 3 * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                var resultFloats = FloatArray(0)
                OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, tileHeight.toLong(), tileWidth.toLong())).use { tensor ->
                    val feed = mapOf(session.inputNames.single() to tensor)
                    return ImageTiles.process(source, target, tileWidth, tileHeight, true, progress, inspect, sampling) { floats, consume ->
                        buffer.clear(); buffer.put(floats); buffer.rewind()
                        session.run(feed).use { result ->
                            val image = result[0] as? OnnxTensor ?: error("이미지 결과 텐서가 아닙니다.")
                            val shape = image.info.shape
                            require(image.info.type == OnnxJavaType.FLOAT && shape.size == 4 && shape[0] == 1L && shape[1] == 3L) { "RGB float32 결과가 필요합니다." }
                            val width = shape[3].toInt(); val height = shape[2].toInt()
                            val size = width * height * 3
                            if (resultFloats.size != size) resultFloats = FloatArray(size)
                            val values = image.floatBuffer ?: error("결과 버퍼를 읽을 수 없습니다.")
                            values.rewind(); values.get(resultFloats); consume(resultFloats, width, height)
                        }
                    }
                }
            }
        }
    }
}
