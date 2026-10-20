package com.plugin.compatibility.enhancement

import android.graphics.Bitmap
import com.junkfood.seal.util.PreferenceUtil.getString
import kotlin.math.exp

/**
 * 저비트레이트 영상의 블록·모스키토 노이즈를 업스케일 전에 완화하는 CPU 전처리입니다.
 * 모델이 노이즈를 디테일로 오인해 확대하는 문제를 줄이며 GPU(RIFE)·NPU 자원을 쓰지 않습니다.
 */
object PreDenoise {
    /** 비트레이트 밀도(bits per pixel per frame)가 이 값보다 낮으면 압축 열화가 큰 원본으로 봅니다. */
    private const val LOW_BPP = 0.10

    const val AUTO = "auto"
    const val OFF = "off"
    const val ALWAYS = "always"

    /** 정지 이미지는 픽셀당 비트가 이 값보다 낮으면 강한 JPEG 압축으로 봅니다. */
    private const val LOW_IMAGE_BPP = 1.5

    /** 저장된 사용자 선택을 읽고 알 수 없는 값은 자동으로 취급합니다. 영상·이미지 키가 따로 있습니다. */
    fun mode(image: Boolean = false): String = com.junkfood.seal.util.PreferenceUtil.run {
        (if (image) EnhancementModels.IMAGE_DENOISE else EnhancementModels.VIDEO_DENOISE).getString(AUTO)
    }.takeIf { it == OFF || it == ALWAYS } ?: AUTO

    /** 노이즈 제거 엔진: 앱 내장 프레임별 필터 또는 FFmpeg 시간축 필터(hqdn3d). */
    const val BUILTIN = "builtin"
    const val FFMPEG = "ffmpeg"

    /** 저장된 영상 노이즈 제거 엔진을 읽고 알 수 없는 값은 내장 필터로 취급합니다. */
    fun engine(): String = com.junkfood.seal.util.PreferenceUtil.run {
        EnhancementModels.VIDEO_DENOISE_ENGINE.getString(BUILTIN)
    }.takeIf { it == FFMPEG } ?: BUILTIN

    /**
     * FFmpeg `hqdn3d` 필터 인자를 내장 필터와 같은 sigma 눈금(보통=14)에서 계산합니다.
     * hqdn3d의 기본값(4:3:6:4.5)이 보통에 해당하며 공간(휘도:색)·시간(휘도:색) 순서입니다.
     */
    fun hqdn3d(sigma: Float): String {
        val luma = sigma * 4f / 14f; val chroma = luma * 0.75f; val tLuma = luma * 1.5f; val tChroma = tLuma * 0.75f
        return "hqdn3d=" + listOf(luma, chroma, tLuma, tChroma).joinToString(":") { "%.2f".format(java.util.Locale.ROOT, it) }
    }

    /** 강도 선택지와 필터 sigma입니다. 클수록 더 강하게 평탄화하지만 디테일 손실도 커집니다. */
    val strengths = listOf("light" to 8f, "normal" to 14f, "strong" to 22f)

    /** 저장된 강도 이름을 sigma로 변환하며 알 수 없는 값은 보통으로 취급합니다. */
    fun sigma(): Float = com.junkfood.seal.util.PreferenceUtil.run {
        EnhancementModels.DENOISE_STRENGTH.getString("normal")
    }.let { name -> strengths.firstOrNull { it.first == name }?.second ?: 14f }

    /** 내장 필터의 거리 가중치 사용 여부를 읽습니다. 기본은 켬(정통 양방향 필터)입니다. */
    fun distanceWeight(): Boolean = com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.DENOISE_DISTANCE.getBoolean(true) }

    /** 이미지 파일 크기 대비 픽셀 수로 사용자 모드를 해석합니다. */
    fun imageEnabled(mode: String, bytes: Long, width: Int, height: Int): Boolean = when (mode) {
        OFF -> false
        ALWAYS -> true
        else -> bytes > 0 && width > 0 && height > 0 && bytes * 8.0 / (width.toDouble() * height) < LOW_IMAGE_BPP
    }

    /** 사용자 모드와 소스 밀도로 실제 적용 여부를 결정합니다. */
    fun enabled(mode: String, bytes: Long, durationUs: Long, width: Int, height: Int, fps: Double): Boolean =
        when (mode) { OFF -> false; ALWAYS -> true; else -> needed(bytes, durationUs, width, height, fps) }

    /** 소스의 압축 밀도로 전처리 필요 여부를 판단합니다. */
    fun needed(bytes: Long, durationUs: Long, width: Int, height: Int, fps: Double): Boolean {
        if (bytes <= 0 || durationUs <= 0 || width <= 0 || height <= 0 || fps <= 0) return false
        val bpp = bytes * 8.0 / (durationUs / 1_000_000.0) / (width.toDouble() * height * fps)
        return bpp < LOW_BPP
    }

    /** 밝기 차이별 가중치 표를 만듭니다. sigma가 클수록 더 강하게 평탄화합니다. */
    private fun weights(sigma: Float) = IntArray(256) { (exp(-(it * it) / (2f * sigma * sigma)) * 256).toInt() }

    /**
     * 거리 가중치 3x3 표(σs=1, 중심 256)입니다. 중심 256, 상하좌우 155, 모서리 94이며
     * 가우시안 exp(-d²/2σs²)를 정수로 바꾼 값이라 픽셀마다 다시 계산하지 않습니다.
     */
    private val flat = IntArray(9) { 256 }
    private val spatial = IntArray(9) { k ->
        val dx = k % 3 - 1; val dy = k / 3 - 1
        (exp(-(dx * dx + dy * dy) / 2f) * 256).toInt()
    }

    /**
     * 3x3 양방향 필터(Tomasi & Manduchi 1998)를 적용합니다.
     * 가중치 = 거리 가우시안 × 밝기 차이 가우시안이며, 윤곽(밝기 차이가 큰 이웃)은 가중치가 낮아 보존됩니다.
     */
    fun bilateral3x3(src: IntArray, width: Int, height: Int, sigma: Float = 14f, distance: Boolean = true): IntArray {
        val table = weights(sigma)
        // 거리 가중치를 끄면 모든 이웃의 거리 가중이 256이라 밝기 차이 가중치만 남습니다(sigma filter).
        val spatial = if (distance) spatial else flat
        val out = IntArray(src.size)
        // 행 단위로 병렬 처리합니다. 출력 영역이 겹치지 않아 동기화가 필요 없습니다.
        java.util.stream.IntStream.range(0, height).parallel().forEach { y ->
            for (x in 0 until width) {
                val center = src[y * width + x]
                val cl = luma(center)
                var sw = 0; var sr = 0; var sg = 0; var sb = 0
                for (dy in -1..1) {
                    val ny = (y + dy).coerceIn(0, height - 1)
                    for (dx in -1..1) {
                        val p = src[ny * width + (x + dx).coerceIn(0, width - 1)]
                        // 밝기 가중치(최대 256) × 거리 가중치(최대 256) >> 8 → 최대 256, 중심은 항상 256입니다.
                        val w = (table[kotlin.math.abs(luma(p) - cl)] * spatial[(dy + 1) * 3 + dx + 1]) shr 8
                        sw += w; sr += w * (p shr 16 and 0xFF); sg += w * (p shr 8 and 0xFF); sb += w * (p and 0xFF)
                    }
                }
                // 중심 가중치(256)가 항상 포함되므로 sw는 0이 될 수 없습니다.
                out[y * width + x] = (center and 0xFF000000.toInt()) or ((sr / sw) shl 16) or ((sg / sw) shl 8) or (sb / sw)
            }
        }
        return out
    }

    /** 정수 밝기(BT.601 근사)를 계산합니다. */
    private fun luma(p: Int) = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) shr 8

    /** 비트맵 픽셀을 필터링해 같은 크기의 새 비트맵을 반환합니다. 호출자가 원본 해제를 담당합니다. */
    fun apply(bitmap: Bitmap, sigma: Float = 14f, distance: Boolean = true): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        return Bitmap.createBitmap(bilateral3x3(pixels, w, h, sigma, distance), w, h, Bitmap.Config.ARGB_8888)
    }
}
