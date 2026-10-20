package com.plugin

import com.plugin.compatibility.enhancement.VideoRules
import org.junit.Assert.*
import org.junit.Test

/** 영상의 실제 시각 보존과 프레임 증가·색 변환 경계를 검사합니다. */
class VideoRulesTest {
    /** 품질 선택이 제조사의 CQ 범위를 넘지 않고 잘못된 범위를 거부합니다. */
    @Test fun hardwareQualityRespectsDeviceRange() {
        val rules = com.plugin.compatibility.MediaRules
        assertEquals(68, rules.hardwareQuality(20, 0, 100))
        assertEquals(44, rules.hardwareQuality(35, 0, 100))
        assertEquals(21, rules.hardwareQuality(50, 0, 100))
        assertEquals(6, rules.hardwareQuality(35, 5, 7))
        assertEquals(68, rules.hardwareQuality(Int.MIN_VALUE, 0, 100))
        try { rules.hardwareQuality(35, 10, 9); fail("잘못된 품질 범위를 허용했습니다.") }
        catch (_: IllegalArgumentException) { }
    }
    /** 원본 우선·AV1 대체·구형 플랫폼의 저장 경계를 검사합니다. */
    @Test fun originalCodecThenAv1() {
        assertEquals(listOf("video/av01", "video/hevc", "video/avc"), VideoRules.encoderMimes("video/hevc", 34))
        assertEquals(listOf("video/hevc", "video/av01", "video/avc"), VideoRules.encoderMimes("video/hevc", 34, true))
        assertEquals(listOf("video/av01", "video/hevc", "video/avc"), VideoRules.encoderMimes("video/x-vnd.on2.vp8", 34))
        assertEquals(listOf("video/av01", "video/hevc", "video/avc"), VideoRules.encoderMimes("video/av01", 34))
        assertEquals(listOf("video/avc", "video/hevc"), VideoRules.encoderMimes("video/avc", 28))
        // 지원하지 않는 원본도 H.265·H.264로 저장할 수 있어 인코더 후보가 비지 않습니다.
        assertEquals(listOf("video/hevc", "video/avc"), VideoRules.encoderMimes("video/av01", 33))
        assertEquals(listOf("video/hevc", "video/avc"), VideoRules.encoderMimes("video/unknown", 28))
    }
    /** 프레임 하나를 복제해 AI 보간으로 표시하지 않습니다. */
    @Test fun singleFrameKeepsOriginal() {
        assertFalse(VideoRules.increases(60, 30.0, 1))
        assertTrue(VideoRules.increases(60, 30.0, 2))
    }
    /** 장시간 시각 계산·원본보다 낮은 선택·기본 YUV 색을 함께 확인합니다. */
    @Test fun frameClockAndColorBounds() {
        assertEquals(3_600_000_123L, VideoRules.timestamp(123, 216_000, 60))
        assertFalse(VideoRules.increases(0, 23.976)); assertTrue(VideoRules.increases(30, 23.976))
        assertFalse(VideoRules.increases(30, 60.0)); assertFalse(VideoRules.increases(60, 60.0))
        assertEquals(0xff000000.toInt(), VideoRules.rgb(16, 128, 128, false, true))
        assertEquals(0xffffffff.toInt(), VideoRules.rgb(235, 128, 128, false, true))
        assertEquals(0xff808080.toInt(), VideoRules.rgb(128, 128, 128, true, false))
        val red = VideoRules.rgb(81, 90, 240, false, false)
        assertTrue(red ushr 16 and 255 >= 250); assertTrue(red and 255 <= 5)
        val red709 = VideoRules.rgb(63, 102, 240, false, true)
        assertTrue(red709 ushr 16 and 255 >= 250); assertTrue(red709 ushr 8 and 255 <= 5)
    }
}
