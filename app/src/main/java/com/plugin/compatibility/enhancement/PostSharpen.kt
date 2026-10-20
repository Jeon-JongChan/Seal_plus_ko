package com.plugin.compatibility.enhancement

import android.graphics.Bitmap
import com.junkfood.seal.util.PreferenceUtil.getString

/**
 * 업스케일 결과의 윤곽을 세우는 CPU 후처리(제한형 언샤프)입니다.
 * 3x3 평균과의 차이를 더하되 결과를 이웃 최소·최대 안으로 제한해 윤곽 주변의 흰 테두리(헤일로)를 막습니다.
 */
object PostSharpen {
    const val OFF = "off"

    /** R·G·B 채널의 비트 이동량입니다. 픽셀마다 배열을 새로 만들지 않도록 한 번만 만듭니다. */
    private val SHIFTS = intArrayOf(16, 8, 0)

    /** 선택지 이름과 강도입니다. 클수록 윤곽이 강해지지만 잔노이즈도 함께 선명해집니다. */
    val strengths = listOf(OFF to 0f, "light" to 0.3f, "normal" to 0.6f, "strong" to 1.0f)

    /** 저장된 선택을 강도로 변환하며 알 수 없는 값은 끔으로 취급합니다. */
    fun amount(): Float = com.junkfood.seal.util.PreferenceUtil.run {
        EnhancementModels.SHARPEN.getString(OFF)
    }.let { name -> strengths.firstOrNull { it.first == name }?.second ?: 0f }

    /** 제한형 언샤프를 적용합니다. 행 단위 병렬 처리이며 출력 영역이 겹치지 않습니다. */
    fun sharpen(src: IntArray, width: Int, height: Int, amount: Float): IntArray {
        if (amount <= 0f) return src
        val out = IntArray(src.size)
        java.util.stream.IntStream.range(0, height).parallel().forEach { y ->
            // 이웃 9픽셀을 채널 3회 반복 대신 한 번만 읽습니다(행마다 하나의 작업 배열 재사용).
            val near = IntArray(9)
            for (x in 0 until width) {
                val center = src[y * width + x]
                var result = center and 0xFF000000.toInt()
                var n = 0
                for (dy in -1..1) {
                    val row = (y + dy).coerceIn(0, height - 1) * width
                    for (dx in -1..1) near[n++] = src[row + (x + dx).coerceIn(0, width - 1)]
                }
                for (shift in SHIFTS) {
                    var sum = 0; var low = 255; var high = 0
                    for (p in near) {
                        val v = p shr shift and 0xFF
                        sum += v; if (v < low) low = v; if (v > high) high = v
                    }
                    val c = center shr shift and 0xFF
                    // 평균 대비 차이를 더한 뒤 이웃 범위로 제한해 과도한 오버슈트를 막습니다.
                    val v = (c + amount * (c - sum / 9f)).toInt().coerceIn(low, high)
                    result = result or (v shl shift)
                }
                out[y * width + x] = result
            }
        }
        return out
    }

    /** 비트맵을 선명하게 만든 새 비트맵을 반환합니다. 호출자가 원본 해제를 담당합니다. */
    fun apply(bitmap: Bitmap, amount: Float): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        return Bitmap.createBitmap(sharpen(pixels, w, h, amount), w, h, Bitmap.Config.ARGB_8888)
    }
}
