package com.plugin.compatibility.enhancement

import kotlin.math.roundToLong

/** 프레임 유지·증가의 시각과 영상 인코더 크기를 공통으로 검증합니다. */
object VideoRules {
    val frameRates = listOf(0, 24, 25, 30, 50, 60)

    /**
     * 기본은 AV1 우선이며 원본 유지 선택과 플랫폼 지원 범위에 따라 대체 순서를 정합니다.
     * H.264 인코더는 기기마다 최대 해상도(1080p·2K 등)가 달라 업스케일 결과를 처리하지 못하는 일이 흔하므로,
     * 선택·원본 코덱이 실패해도 H.265(MP4 기록 가능) → H.264 → AV1 순으로 항상 저장 가능한 대체를 이어 붙입니다.
     */
    fun encoderMimes(source: String, sdk: Int, preserveOriginal: Boolean = false, selected: String = "auto"): List<String> = buildList {
        if (selected == "h264" || selected == "h265") add(if (selected == "h264") "video/avc" else "video/hevc")
        else {
            val original = source in listOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp", "video/x-vnd.on2.vp9") ||
                source == "video/av01" && sdk >= 34
            if (preserveOriginal && original) add(source)
            // 플랫폼 MP4 기록기의 AV1 지원은 Android 14부터입니다.
            if (sdk >= 34 && "video/av01" !in this) add("video/av01")
            if (original && source !in this) add(source)
        }
        // 마지막 대체: 큰 해상도는 HEVC가 더 넓게 지원되고, 작은 해상도는 H.264가 지원됩니다.
        for (fallback in listOf("video/hevc", "video/avc")) if (fallback !in this) add(fallback)
        if (sdk >= 34 && "video/av01" !in this) add("video/av01")
    }

    /** 프레임 증가 선택은 원본보다 낮은 프레임률로 줄이지 않습니다. */
    fun increases(requested: Int, original: Double, frames: Long = Long.MAX_VALUE): Boolean {
        require(requested in frameRates && original.isFinite() && original >= 0 && frames > 0)
        return frames > 1 && requested > 0 && requested > original + 0.01
    }

    /**
     * 크기를 유지하는 보정은 원본만 보정하고 확대는 저해상도에서 먼저 보간합니다.
     * [interpolateLow]가 true(보간 시 사용자 선택)면 크기와 무관하게 저해상도에서 먼저 보간한 뒤 모든 프레임을 보정합니다.
     */
    fun enhanceFirst(width: Int, height: Int, target: Pair<Int, Int>, interpolateLow: Boolean = false): Boolean =
        !interpolateLow && target.first <= width && target.second <= height

    /** 원본보다 낮거나 같은 FPS를 선택했을 때 보간을 생략한 이유를 표시합니다. */
    fun frameNote(requested: Int, original: Double, frames: Long): String = when {
        requested == 0 -> "보간 생략 · 원본 FPS 유지"
        frames <= 1 -> "보간 생략 · 원본이 한 프레임입니다"
        !increases(requested, original, frames) -> "보간 생략 · 선택 ${requested}fps ≤ 원본 %.2ffps".format(java.util.Locale.ROOT, original)
        else -> "프레임 보간 · 원본 %.2ffps → ${requested}fps".format(java.util.Locale.ROOT, original)
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
