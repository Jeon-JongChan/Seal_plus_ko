package com.plugin.compatibility.enhancement

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 이미지·영상의 축소 방식을 공유하며 기본 양선형 동작을 유지합니다. */
object EnhancementSampling {
    const val KEY = "image_ai_sampling"
    const val NEAREST = 0
    const val BILINEAR = 1
    const val AREA = 2

    /** 오래된 저장값도 기본 방식으로 안전하게 되돌립니다. */
    fun normalized(value: Int): Int = if (value in NEAREST..AREA) value else BILINEAR

    /** Google 결과에도 같은 축소 선택을 적용하고 투명도를 보존합니다. */
    fun resize(source: Bitmap, width: Int, height: Int, mode: Int): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val colors = if (mode == AREA) IntArray(source.width * source.height).also {
                source.getPixels(it, 0, source.width, 0, 0, source.width, source.height)
            } else IntArray(0)
            draw(Canvas(output), source, colors, Rect(0, 0, source.width, source.height), Rect(0, 0, width, height), normalized(mode))
            return output
        } catch (failure: Throwable) { output.recycle(); throw failure }
    }

    /** 영역 평균은 실제 축소에만 적용하고 그 외에는 플랫폼 그리기를 재사용합니다. */
    fun draw(canvas: Canvas, tile: Bitmap, colors: IntArray, source: Rect, target: Rect, mode: Int) {
        if (target.width() <= 0 || target.height() <= 0) return
        if (mode != AREA || (target.width() >= source.width() && target.height() >= source.height())) {
            canvas.drawBitmap(tile, source, target, Paint(if (mode == NEAREST) 0 else Paint.FILTER_BITMAP_FLAG))
            return
        }
        val output = average(colors, tile.width, source, target.width(), target.height())
        val bitmap = Bitmap.createBitmap(output, target.width(), target.height(), Bitmap.Config.ARGB_8888)
        try { canvas.drawBitmap(bitmap, target.left.toFloat(), target.top.toFloat(), null) } finally { bitmap.recycle() }
    }

    /** 출력 픽셀이 덮는 입력 영역의 분수 면적까지 가중하여 경계의 색을 보존합니다. */
    fun average(colors: IntArray, stride: Int, source: Rect, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && source.width() > 0 && source.height() > 0)
        require(source.left >= 0 && source.top >= 0 && source.right <= stride && source.bottom.toLong() * stride <= colors.size)
        val result = IntArray(width * height)
        val sx = source.width().toDouble() / width; val sy = source.height().toDouble() / height
        for (y in 0 until height) for (x in 0 until width) {
            val left = source.left + x * sx; val right = left + sx
            val top = source.top + y * sy; val bottom = top + sy
            var red = 0.0; var green = 0.0; var blue = 0.0; var alpha = 0.0; var weight = 0.0
            for (iy in floor(top).toInt() until ceil(bottom).toInt().coerceAtMost(source.bottom))
                for (ix in floor(left).toInt() until ceil(right).toInt().coerceAtMost(source.right)) {
                    val part = (minOf(right, ix + 1.0) - maxOf(left, ix.toDouble())) *
                        (minOf(bottom, iy + 1.0) - maxOf(top, iy.toDouble()))
                    val color = colors[iy * stride + ix]
                    // 알파로 가중해 투명 픽셀의 숨은 색이 경계에 번지지 않게 합니다.
                    val visible = Color.alpha(color) * part
                    red += Color.red(color) * visible; green += Color.green(color) * visible; blue += Color.blue(color) * visible
                    alpha += visible; weight += part
                }
            result[y * width + x] = if (alpha == 0.0) Color.TRANSPARENT else
                Color.argb((alpha / weight).roundToInt().coerceIn(0, 255), (red / alpha).roundToInt().coerceIn(0, 255),
                    (green / alpha).roundToInt().coerceIn(0, 255), (blue / alpha).roundToInt().coerceIn(0, 255))
        }
        return result
    }
}
