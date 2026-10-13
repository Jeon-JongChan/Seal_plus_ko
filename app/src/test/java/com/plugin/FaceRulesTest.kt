package com.plugin

import com.plugin.faces.FaceRules
import org.junit.Assert.*
import org.junit.Test

/** 폴더별 화질 상속·영상 제외·안전한 해상도·YuNet 점수 규칙을 검사합니다. */
class FaceRulesTest {
    /** 같은 720p 폴더라도 각 상위 일반 폴더의 NOFACE로 모이며 루트를 벗어나지 않습니다. */
    @Test fun destinationsAndAnalysis() {
        assertEquals(listOf("Images"), FaceRules.destinationParent(listOf("Images", "720p"), true))
        assertEquals(listOf("Images", "Hot"), FaceRules.destinationParent(listOf("Images", "Hot", "720p"), true))
        assertEquals(listOf("Images"), FaceRules.destinationParent(listOf("Images", "720p", "1080x1920"), true))
        assertEquals(emptyList<String>(), FaceRules.destinationParent(listOf("Images", "Hot"), false))
        assertTrue(FaceRules.excluded("VIDEOS", "Clips"))
        assertTrue(FaceRules.excluded("Clips", "Clips"))
        assertTrue(FaceRules.excluded("NOFACE", "Videos"))
        assertFalse(FaceRules.excluded("720p", "Videos"))
        assertTrue(FaceRules.isImage("사진.JPG"))
        assertFalse(FaceRules.isImage("영상.mp4"))
        assertEquals(640 to 360, FaceRules.analysisSize(1920, 1080, 640))
        assertEquals(120 to 80, FaceRules.analysisSize(120, 80, 640))
        assertEquals(1000 to 1000, FaceRules.analysisSize(1000, 1000, 0))
        assertTrue(runCatching { FaceRules.analysisSize(8000, 6000, 0) }.isFailure)
        assertEquals(0.5f, FaceRules.score(0.25f, 1f), 0.00001f)
        assertTrue(runCatching { FaceRules.score(Float.NaN, 1f) }.isFailure)
    }
}
