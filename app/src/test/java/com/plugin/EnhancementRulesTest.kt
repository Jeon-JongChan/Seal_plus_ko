package com.plugin

import com.plugin.enhancement.EnhancementRules
import org.junit.Assert.*
import org.junit.Test

/** 비율·메모리 한도·원격 모델 링크와 미디어 ID 보존을 검증합니다. */
class EnhancementRulesTest {
    /** 전체 보정은 순서·중복 제거를 유지하며 영상과 NOFACE 폴더를 제외합니다. */
    @Test fun postImagesExcludeVideosAndNoFace() {
        assertEquals(listOf("/post/1.jpg", "/post/2.png"), EnhancementRules.postImages(listOf(
            "/post/1.jpg", "/post/clip.mp4", "/post/2.png", "/post/1.jpg", "/post/NOFACE/3.jpg", "/post/song.mp3")))
        assertTrue(EnhancementRules.postImages(listOf("/post/clip.mp4", "/NOFACE/a.jpg")).isEmpty())
    }
    /** CPU 선택은 가속을 켜지 않고 사용자 ONNX 모델에는 LiteRT GPU 경로를 넣지 않습니다. */
    @Test fun nativeTerminationRecoveryOrder() {
        assertEquals(listOf("automatic", "gpu", "cpu"), EnhancementRules.recoveryRoutes("litert", true, true))
        assertEquals(listOf("gpu", "cpu"), EnhancementRules.recoveryRoutes("litert", true, true, false))
        assertEquals(listOf("automatic", "cpu"), EnhancementRules.recoveryRoutes("onnx", true, true, false))
        assertEquals(listOf("automatic", "cpu"), EnhancementRules.recoveryRoutes("onnx", true, true))
        assertEquals(listOf("automatic", "cpu"), EnhancementRules.recoveryRoutes("litert", true, false))
        assertEquals(listOf("cpu"), EnhancementRules.recoveryRoutes("litert", false, true))
    }
    /** 코어 수가 작은 기기도 최소 한 스레드를 제공하고 저장값을 범위 안으로 제한합니다. */
    @Test fun cpuThreadsStayWithinHalfOfDevice() {
        assertEquals(1, EnhancementRules.cpuThreadLimit(1))
        assertEquals(4, EnhancementRules.cpuThreadLimit(8))
        assertEquals(3, EnhancementRules.cpuThreadLimit(7))
        assertEquals(1, EnhancementRules.cpuThreads(0, 8))
        assertEquals(4, EnhancementRules.cpuThreads(100, 8))
    }

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
        assertEquals("https://huggingface.co/a/b/resolve/main/model.tflite", EnhancementRules.modelUrl("https://huggingface.co/a/b/blob/main/model.tflite"))
        assertEquals("https://huggingface.co/a/b/resolve/main/model.onnx", EnhancementRules.modelUrl("https://huggingface.co/a/b/blob/main/model.onnx?download=true"))
        for (url in listOf("http://huggingface.co/a/b/resolve/main/a.onnx", "https://evil.example/a.onnx",
            "https://user@huggingface.co/a/b/resolve/main/a.onnx", "https://huggingface.co/a/b/resolve/../a.onnx", "https://huggingface.co/a/b/resolve/main/a.pth")) {
            assertTrue(runCatching { EnhancementRules.modelUrl(url) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals("사진-AI-abcd [123456].png", EnhancementRules.duplicateName("사진 [123456].jpg", "abcd"))
        assertEquals("사진-AI-abcd.png", EnhancementRules.duplicateName("사진.webp", "abcd"))
    }

    /** 삭제 경계가 단일 모델 파일만 허용하고 폴더·상위 경로·다른 형식을 거부합니다. */
    @Test fun deletionStaysWithinManagedModelFolders() {
        val root = java.nio.file.Files.createTempDirectory("managed-model-check").toFile()
        val model = java.io.File(root, "model.tflite").apply { writeText("검사") }
        val nested = java.io.File(root, "nested").apply { mkdir() }
        val other = java.io.File(nested, "model.onnx").apply { writeText("검사") }
        val unrelated = java.io.File(root, "photo.jpg").apply { writeText("검사") }
        try {
            assertTrue(EnhancementRules.managedFile(model, listOf(root)))
            assertFalse(EnhancementRules.managedFile(other, listOf(root)))
            assertFalse(EnhancementRules.managedFile(unrelated, listOf(root)))
            assertFalse(EnhancementRules.managedFile(root, listOf(root)))
            assertFalse(EnhancementRules.managedFile(java.io.File(nested, "../model.tflite"), listOf(root)))
        } finally { model.delete(); other.delete(); unrelated.delete(); nested.delete(); root.delete() }
    }
}
