package com.plugin.compatibility.enhancement

import kotlin.math.roundToLong

/** 프레임 유지·증가의 시각과 영상 인코더 크기를 공통으로 검증합니다. */
object VideoRules {
    val frameRates = listOf(0, 24, 25, 30, 50, 60)

    /** 프레임 증가 선택은 원본보다 낮은 프레임률로 줄이지 않습니다. */
    fun increases(requested: Int, original: Double, frames: Long = Long.MAX_VALUE): Boolean {
        require(requested in frameRates && original.isFinite() && original >= 0 && frames > 0)
        return frames > 1 && requested > 0 && requested > original + 0.01
    }

    /** 누적 반올림 오차 없이 프레임 번호에서 마이크로초 시각을 계산합니다. */
    fun timestamp(first: Long, frame: Long, fps: Int): Long {
        require(first >= 0 && frame >= 0 && fps in frameRates && fps > 0)
        return Math.addExact(first, (frame * 1_000_000.0 / fps).roundToLong())
    }

    /** 지원 인코더의 짝수 크기로 맞추며 과도한 출력은 기존 이미지 제한을 적용합니다. */
    fun size(width: Int, height: Int, option: Int): Pair<Int, Int> {
        val target = EnhancementRules.target(width, height, option)
        return maxOf(2, target.first / 2 * 2) to maxOf(2, target.second / 2 * 2)
    }

    /** 제한 범위 YUV를 SDR RGB로 변환하고 표준별 계수를 적용합니다. */
    fun rgb(y: Int, u: Int, v: Int, full: Boolean, bt709: Boolean): Int {
        val luminance = if (full) y.toDouble() else (y - 16) * 255.0 / 219
        val blue = (u - 128) * if (full) 1.0 else 255.0 / 224
        val red = (v - 128) * if (full) 1.0 else 255.0 / 224
        val r = (luminance + red * (if (bt709) 1.5748 else 1.402)).toInt().coerceIn(0, 255)
        val g = (luminance - blue * (if (bt709) 0.187324 else 0.344136) - red * (if (bt709) 0.468124 else 0.714136)).toInt().coerceIn(0, 255)
        val b = (luminance + blue * (if (bt709) 1.8556 else 1.772)).toInt().coerceIn(0, 255)
        return -0x1000000 or (r shl 16) or (g shl 8) or b
    }
}
