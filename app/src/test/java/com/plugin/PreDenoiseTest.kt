package com.plugin

import com.plugin.compatibility.enhancement.PreDenoise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 영상 전처리 노이즈 제거의 판단 기준과 윤곽 보존을 검증합니다. */
class PreDenoiseTest {
    /** 1080p 30fps에서 1MB/10초는 저밀도, 20MB/10초는 충분한 밀도입니다. */
    @Test fun lowBitrateDetected() {
        assertTrue(PreDenoise.needed(1_000_000, 10_000_000, 1920, 1080, 30.0))
        assertFalse(PreDenoise.needed(20_000_000, 10_000_000, 1920, 1080, 30.0))
        assertFalse(PreDenoise.needed(0, 10_000_000, 1920, 1080, 30.0))
    }

    /** 작은 잡음은 평탄화하고 큰 밝기 단차(윤곽)는 유지합니다. */
    @Test fun smoothsNoiseKeepsEdge() {
        val w = 8; val h = 3
        val src = IntArray(w * h) { i ->
            val x = i % w
            val base = if (x < 4) 40 else 220
            val noise = if (i % 2 == 0) 4 else -4
            val v = base + noise
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val out = PreDenoise.bilateral3x3(src, w, h)
        fun red(p: Int) = p shr 16 and 0xFF
        // 평탄 영역의 편차가 줄고 좌우 영역 차이는 100 이상 남는다.
        assertTrue(kotlin.math.abs(red(out[1 * w + 1]) - 40) <= 4)
        assertTrue(red(out[1 * w + 6]) - red(out[1 * w + 2]) > 100)
        assertEquals(src.size, out.size)
    }

    /** 같은 밝기 차이라도 가까운 상하좌우 이웃이 먼 모서리 이웃보다 결과에 더 크게 반영됩니다(거리 가중). */
    @Test fun nearNeighbourWeighsMoreThanCorner() {
        fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        fun centerAfter(index: Int, distance: Boolean): Int {
            val src = IntArray(9) { gray(100) }.also { it[index] = gray(112) }
            return PreDenoise.bilateral3x3(src, 3, 3, distance = distance)[4] and 0xFF
        }
        assertTrue(centerAfter(1, true) > centerAfter(0, true))
        // 거리 가중치를 끄면 위치와 무관하게 같은 결과여야 합니다.
        assertEquals(centerAfter(1, false), centerAfter(0, false))
    }
}
