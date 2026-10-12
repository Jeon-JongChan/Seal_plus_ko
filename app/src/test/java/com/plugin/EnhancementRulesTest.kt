package com.plugin

import com.plugin.enhancement.EnhancementRules
import org.junit.Assert.*
import org.junit.Test

/** 비율·메모리 한도·원격 모델 링크와 미디어 ID 보존을 검증합니다. */
class EnhancementRulesTest {
    /** 가로·세로 사진의 해상도와 배율 선택이 원래 비율을 유지합니다. */
    @Test
    fun targetKeepsAspectAndRejectsOversizedOutput() {
        assertEquals(1920 to 1080, EnhancementRules.target(960, 540, -1080))
        assertEquals(720 to 1280, EnhancementRules.target(360, 640, -720))
        assertEquals(4096 to 4096, EnhancementRules.target(512, 512, 8))
        assertEquals(1440 to 2560, EnhancementRules.target(720, 1280, -1440))
        assertEquals(2160 to 3840, EnhancementRules.target(720, 1280, -2160))
        for (arguments in listOf(Triple(512, 768, 8), Triple(1920, 1080, -720), Triple(0, 1, 1), Triple(1, 1, 3))) {
            assertTrue(runCatching { EnhancementRules.target(arguments.first, arguments.second, arguments.third) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    /** 간편 설정은 이미 큰 사진을 축소하지 않고 극단적인 비율도 출력 한도 안에서 유지합니다. */
    @Test
    fun presetsStayWithinLimitsWithoutDownscaling() {
        assertEquals(1920 to 1080, EnhancementRules.target(960, 540, EnhancementRules.MINIMUM))
        assertEquals(3840 to 2160, EnhancementRules.target(960, 540, EnhancementRules.MAXIMUM))
        assertEquals(4000 to 3000, EnhancementRules.target(4000, 3000, EnhancementRules.MAXIMUM))
        for (option in listOf(EnhancementRules.MINIMUM, EnhancementRules.MAXIMUM)) {
            val (width, height) = EnhancementRules.target(4000, 1000, option)
            assertTrue(width >= 4000 && height >= 1000)
            assertTrue(width <= 8192 && width.toLong() * height <= EnhancementRules.MAX_PIXELS)
            assertEquals(4.0, width.toDouble() / height, 0.005)
        }
    }

    /** 외부 호스트·로그인 정보·경로 탈출·체크포인트 파일은 다운로드 입력에서 제외합니다. */
    @Test
    fun modelsAndCopiesKeepTheirBoundaries() {
        assertEquals("https://huggingface.co/a/b/resolve/main/model.onnx", EnhancementRules.modelUrl("https://huggingface.co/a/b/blob/main/model.onnx?download=true"))
        for (url in listOf("http://huggingface.co/a/b/resolve/main/a.onnx", "https://evil.example/a.onnx",
            "https://user@huggingface.co/a/b/resolve/main/a.onnx", "https://huggingface.co/a/b/resolve/../a.onnx", "https://huggingface.co/a/b/resolve/main/a.pth")) {
            assertTrue(runCatching { EnhancementRules.modelUrl(url) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals("사진-AI-abcd [123456].png", EnhancementRules.duplicateName("사진 [123456].jpg", "abcd"))
        assertEquals("사진-AI-abcd.png", EnhancementRules.duplicateName("사진.webp", "abcd"))
    }
}
