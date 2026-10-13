package com.plugin

import android.graphics.Bitmap
import android.graphics.Color
import com.plugin.compatibility.enhancement.EnhancementSampling
import org.junit.Assert.*
import org.junit.Test

/** 기본 축소와 영역 평균의 색·투명도·원본 보존을 실제 Android 비트맵으로 검사합니다. */
class EnhancementSamplingTest {
    /** 영역 평균은 전체 면적을 읽고 투명 픽셀의 숨은 색을 섞지 않습니다. */
    @Test fun downsamplingPreservesMeanAndAlpha() {
        val source = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888).apply {
            setPixels(intArrayOf(Color.BLACK, Color.WHITE, Color.BLACK, Color.WHITE), 0, 4, 0, 0, 4, 1)
        }
        try {
            val average = EnhancementSampling.resize(source, 1, 1, EnhancementSampling.AREA)
            try { assertEquals(128, Color.red(average.getPixel(0, 0))) }
            finally { average.recycle() }
            source.setPixels(intArrayOf(Color.RED, Color.TRANSPARENT, Color.RED, Color.TRANSPARENT), 0, 4, 0, 0, 4, 1)
            val alpha = EnhancementSampling.resize(source, 1, 1, EnhancementSampling.AREA)
            try {
                val color = alpha.getPixel(0, 0)
                assertEquals(128, Color.alpha(color)); assertEquals(255, Color.red(color))
                assertEquals(0, Color.blue(color))
            } finally { alpha.recycle() }
            val expected = Bitmap.createScaledBitmap(source, 2, 1, true)
            val actual = EnhancementSampling.resize(source, 2, 1, EnhancementSampling.BILINEAR)
            try { assertTrue("기본 양선형 결과가 달라졌습니다.", expected.sameAs(actual)) }
            finally { expected.recycle(); actual.recycle() }
            assertFalse(source.isRecycled)
            assertEquals(EnhancementSampling.BILINEAR, EnhancementSampling.normalized(99))
        } finally { source.recycle() }
    }
}
