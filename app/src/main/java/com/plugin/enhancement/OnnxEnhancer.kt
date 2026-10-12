package com.plugin.enhancement

import ai.onnxruntime.*
import ai.onnxruntime.providers.NNAPIFlags
import android.os.Build
import java.util.EnumSet
import android.graphics.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** RGB/NCHW float32 단일 입출력 모델을 작은 겹침 타일로 실행합니다. */
object OnnxEnhancer {
    /** 지원 GPU·NPU를 자동 활용하고 네이티브 가속 오류일 때만 CPU로 재시도합니다. */
    suspend fun process(source: Bitmap, model: String, target: Pair<Int, Int>, progress: (Int) -> Unit,
        accelerated: Boolean, engine: (String) -> Unit): Bitmap {
        if (accelerated && Build.VERSION.SDK_INT >= 29 && OrtProvider.NNAPI in OrtEnvironment.getAvailableProviders()) {
            try {
                engine("ONNX · NNAPI 자동 (GPU/NPU·CPU 병행 가능)")
                return run(source, model, target, progress, true)
            } catch (_: OrtException) {
                // 가속 드라이버·모델 지원 오류는 원본에서 CPU로 다시 시작합니다.
                currentCoroutineContext().ensureActive()
            }
        }
        engine(if (accelerated) "ONNX · CPU (가속 미지원 또는 실패)" else "ONNX · CPU")
        return run(source, model, target, progress, false)
    }

    /** 모델 세션과 타일 버퍼를 요청 안에서만 소유해 다운로드와 실행 자원을 공유하지 않습니다. */
    private suspend fun run(source: Bitmap, model: String, target: Pair<Int, Int>, progress: (Int) -> Unit, accelerated: Boolean): Bitmap {
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            if (accelerated) options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
            // 다운로드를 위한 CPU 여유를 남기고 다중 코어 기기에서만 두 스레드를 사용합니다.
            options.setIntraOpNumThreads(if (!accelerated && Runtime.getRuntime().availableProcessors() >= 4) 2 else 1)
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
                var current = source
                try {
                    // 8배는 네이티브 배율을 넘으면 실제 AI 추론을 다시 거칩니다.
                    repeat(4) {
                        val output = renderPass(environment, session, current, tileWidth, tileHeight, target, progress)
                        if (current !== source) current.recycle()
                        current = output
                        if (current.width == target.first && current.height == target.second) return current
                    }
                    error("선택한 모델로 목표 해상도에 도달할 수 없습니다.")
                } catch (failure: Throwable) { if (current !== source) current.recycle(); throw failure }
            }
        }
    }

    /** 픽셀을 일괄 읽고 직접 버퍼·텐서·타일 비트맵을 재사용하며 경계 패딩은 유지합니다. */
    private suspend fun renderPass(environment: OrtEnvironment, session: OrtSession, input: Bitmap,
        tileWidth: Int, tileHeight: Int, target: Pair<Int, Int>, progress: (Int) -> Unit): Bitmap {
        val padding = 16
        val stepX = tileWidth - padding * 2; val stepY = tileHeight - padding * 2
        val pixels = IntArray(tileWidth * tileHeight)
        val floats = FloatArray(pixels.size * 3)
        val buffer = java.nio.ByteBuffer.allocateDirect(floats.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        var output: Bitmap? = null
        var tile: Bitmap? = null
        var colors = IntArray(0)
        var resultFloats = FloatArray(0)
        val count = ((input.width + stepX - 1) / stepX) * ((input.height + stepY - 1) / stepY)
        var done = 0
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        try {
            // 직접 버퍼의 내용을 갱신하면 같은 텐서를 다시 사용할 수 있어 타일마다 복사·생성을 피합니다.
            OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, tileHeight.toLong(), tileWidth.toLong())).use { tensor ->
                val feed = mapOf(session.inputNames.single() to tensor)
                for (top in 0 until input.height step stepY) for (left in 0 until input.width step stepX) {
                    currentCoroutineContext().ensureActive()
                    val readLeft = (left - padding).coerceAtLeast(0); val readTop = (top - padding).coerceAtLeast(0)
                    val readWidth = minOf(input.width, left + tileWidth - padding) - readLeft
                    val readHeight = minOf(input.height, top + tileHeight - padding) - readTop
                    input.getPixels(pixels, 0, tileWidth, readLeft, readTop, readWidth, readHeight)
                    for (y in 0 until tileHeight) for (x in 0 until tileWidth) {
                        val index = y * tileWidth + x
                        val color = pixels[(top + y - padding - readTop).coerceIn(0, readHeight - 1) * tileWidth +
                            (left + x - padding - readLeft).coerceIn(0, readWidth - 1)]
                        floats[index] = Color.red(color) / 255f
                        floats[index + pixels.size] = Color.green(color) / 255f
                        floats[index + pixels.size * 2] = Color.blue(color) / 255f
                    }
                    buffer.clear(); buffer.put(floats); buffer.rewind()
                    session.run(feed).use { result ->
                        val image = result[0] as? OnnxTensor ?: error("이미지 결과 텐서가 아닙니다.")
                        val shape = image.info.shape
                        require(image.info.type == OnnxJavaType.FLOAT && shape.size == 4 && shape[0] == 1L && shape[1] == 3L) { "RGB float32 결과가 필요합니다." }
                        val height = shape[2].toInt(); val width = shape[3].toInt()
                        require(width % tileWidth == 0 && height % tileHeight == 0 && width / tileWidth == height / tileHeight && width / tileWidth in 1..4) { "1~4배 정수 배율 모델만 지원합니다." }
                        val scale = width / tileWidth
                        require(scale > 1 || target.first == input.width && target.second == input.height) { "선택 모델은 업스케일 모델이 아닙니다." }
                        if (output == null) output = Bitmap.createBitmap(minOf(target.first, input.width * scale), minOf(target.second, input.height * scale), Bitmap.Config.ARGB_8888)
                        val values = image.floatBuffer ?: error("결과 버퍼를 읽을 수 없습니다.")
                        val plane = width * height
                        if (colors.size != plane) {
                            tile?.recycle(); tile = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            colors = IntArray(plane); resultFloats = FloatArray(plane * 3)
                        }
                        values.rewind(); values.get(resultFloats)
                        for (i in colors.indices) colors[i] = Color.rgb(
                            (resultFloats[i] * 255).roundToInt().coerceIn(0, 255),
                            (resultFloats[i + plane] * 255).roundToInt().coerceIn(0, 255),
                            (resultFloats[i + plane * 2] * 255).roundToInt().coerceIn(0, 255))
                        tile!!.setPixels(colors, 0, width, 0, 0, width, height)
                        val right = minOf(left + stepX, input.width); val bottom = minOf(top + stepY, input.height)
                        val destination = output!!
                        Canvas(destination).drawBitmap(tile!!, Rect(padding * scale, padding * scale,
                            (padding + right - left) * scale, (padding + bottom - top) * scale),
                            Rect((left.toLong() * destination.width / input.width).toInt(), (top.toLong() * destination.height / input.height).toInt(),
                                (right.toLong() * destination.width / input.width).toInt(), (bottom.toLong() * destination.height / input.height).toInt()), paint)
                    }
                    progress(++done * 100 / count)
                }
            }
            val destination = checkNotNull(output)
            if (input.hasAlpha()) {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                Canvas(destination).drawBitmap(input, null, Rect(0, 0, destination.width, destination.height), paint)
            }
            return destination
        } catch (failure: Throwable) { output?.recycle(); throw failure }
        finally { tile?.recycle() }
    }
}
