package com.plugin.compatibility.enhancement

import android.graphics.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

/** ONNX와 LiteRT가 같은 패딩·분할·결합 규칙으로 이미지를 처리합니다. */
object ImageTiles {
    /** 목표 해상도에 필요한 추론 패스를 실행하고 중간 비트맵의 소유권을 관리합니다. */
    suspend fun process(source: Bitmap, target: Pair<Int, Int>, width: Int, height: Int,
        channelsFirst: Boolean, progress: (Int) -> Unit, inspect: () -> Unit, sampling: Int = EnhancementSampling.BILINEAR,
        infer: (FloatArray, (FloatArray, Int, Int) -> Unit) -> Unit): Bitmap {
        var current = source
        try {
            repeat(4) {
                val next = render(current, target, width, height, channelsFirst, progress, inspect, sampling, infer)
                if (current !== source) current.recycle()
                current = next
                if (current.width == target.first && current.height == target.second) return current
            }
            error("선택한 모델로 목표 해상도에 도달할 수 없습니다.")
        } catch (failure: Throwable) { if (current !== source) current.recycle(); throw failure }
    }

    /** 겹침 경계를 잘라 이어 붙이며 RGB 텐서 배치만 실행 엔진에 맞게 바꿉니다. */
    private suspend fun render(input: Bitmap, target: Pair<Int, Int>, tw: Int, th: Int,
        channelsFirst: Boolean, progress: (Int) -> Unit, inspect: () -> Unit, sampling: Int,
        infer: (FloatArray, (FloatArray, Int, Int) -> Unit) -> Unit): Bitmap {
        require(tw in 48..256 && th in 48..256) { "모델 타일 크기는 48~256픽셀이어야 합니다." }
        // ponytail: 경계 문맥은 16픽셀입니다. 이음매가 보이면 패딩 확대 또는 가중치 블렌딩으로 보완합니다.
        val padding = 16; val sx = tw - padding * 2; val sy = th - padding * 2
        val pixels = IntArray(tw * th); val floats = FloatArray(pixels.size * 3)
        var output: Bitmap? = null; var tile: Bitmap? = null; var colors = IntArray(0)
        val count = ((input.width + sx - 1) / sx) * ((input.height + sy - 1) / sy)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG); var done = 0
        try {
            for (top in 0 until input.height step sy) for (left in 0 until input.width step sx) {
                currentCoroutineContext().ensureActive()
                val rl = (left - padding).coerceAtLeast(0); val rt = (top - padding).coerceAtLeast(0)
                val rw = minOf(input.width, left + tw - padding) - rl
                val rh = minOf(input.height, top + th - padding) - rt
                input.getPixels(pixels, 0, tw, rl, rt, rw, rh)
                for (y in 0 until th) for (x in 0 until tw) {
                    val i = y * tw + x
                    val color = pixels[(top + y - padding - rt).coerceIn(0, rh - 1) * tw + (left + x - padding - rl).coerceIn(0, rw - 1)]
                    val base = if (channelsFirst) i else i * 3
                    val stride = if (channelsFirst) pixels.size else 1
                    floats[base] = Color.red(color) / 255f
                    floats[base + stride] = Color.green(color) / 255f
                    floats[base + stride * 2] = Color.blue(color) / 255f
                }
                infer(floats) { values, width, height ->
                    require(width % tw == 0 && height % th == 0 && width / tw == height / th && width / tw in 1..4) { "1~4배 정수 배율 모델만 지원합니다." }
                    val scale = width / tw
                    require(scale > 1 || target == (input.width to input.height)) { "선택 모델은 업스케일 모델이 아닙니다." }
                    if (output == null) output = Bitmap.createBitmap(minOf(target.first, input.width * scale), minOf(target.second, input.height * scale), Bitmap.Config.ARGB_8888)
                    val plane = width * height; require(values.size == plane * 3) { "RGB 결과 크기가 올바르지 않습니다." }
                    if (colors.size != plane) {
                        tile?.recycle(); tile = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); colors = IntArray(plane)
                    }
                    for (i in colors.indices) {
                        val base = if (channelsFirst) i else i * 3
                        val stride = if (channelsFirst) plane else 1
                        colors[i] = Color.rgb((values[base] * 255).roundToInt().coerceIn(0, 255),
                            (values[base + stride] * 255).roundToInt().coerceIn(0, 255), (values[base + stride * 2] * 255).roundToInt().coerceIn(0, 255))
                    }
                    tile!!.setPixels(colors, 0, width, 0, 0, width, height)
                    val right = minOf(left + sx, input.width); val bottom = minOf(top + sy, input.height)
                    val destination = checkNotNull(output)
                    EnhancementSampling.draw(Canvas(destination), tile!!, colors, Rect(padding * scale, padding * scale, (padding + right - left) * scale, (padding + bottom - top) * scale),
                        Rect((left.toLong() * destination.width / input.width).toInt(), (top.toLong() * destination.height / input.height).toInt(),
                            (right.toLong() * destination.width / input.width).toInt(), (bottom.toLong() * destination.height / input.height).toInt()), sampling)
                }
                inspect(); progress(++done * 100 / count)
            }
            val result = checkNotNull(output)
            if (input.hasAlpha()) {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                Canvas(result).drawBitmap(input, null, Rect(0, 0, result.width, result.height), paint)
            }
            return result
        } catch (failure: Throwable) { output?.recycle(); throw failure }
        finally { tile?.recycle() }
    }
}
