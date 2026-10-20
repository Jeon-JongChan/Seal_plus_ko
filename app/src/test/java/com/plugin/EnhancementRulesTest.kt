package com.plugin

import com.plugin.compatibility.enhancement.EnhancementRules
import org.junit.Assert.*
import org.junit.Test

/** 비율·메모리 한도·원격 모델 링크와 미디어 ID 보존을 검증합니다. */
class EnhancementRulesTest {
    /** 확대 여부에 따른 순서·낮은 FPS 생략·직접 코덱 선택이 서로 독립적인지 검증합니다. */
    @Test fun videoOrderAndExplicitCodecFollowTheRequest() {
        val rules = com.plugin.compatibility.enhancement.VideoRules
        assertTrue(rules.enhanceFirst(1280, 720, 1280 to 720))
        assertFalse(rules.enhanceFirst(1280, 720, 2560 to 1440))
        // 저해상도 보간을 고르면 크기를 유지해도 보정을 보간 뒤로 미룬다.
        assertFalse(rules.enhanceFirst(1280, 720, 1280 to 720, interpolateLow = true))
        assertTrue(rules.frameNote(24, 30.0, 300).contains("보간 생략"))
        assertTrue(rules.frameNote(30, 30.0, 300).contains("보간 생략"))
        assertTrue(rules.frameNote(60, 30.0, 300).contains("프레임 보간"))
        // 선택한 코덱이 첫 후보이고, 해상도 한계에 대비한 대체가 뒤따릅니다.
        assertEquals(listOf("video/avc", "video/hevc", "video/av01"), rules.encoderMimes("video/hevc", 35, selected = "h264"))
        assertEquals(listOf("video/hevc", "video/avc", "video/av01"), rules.encoderMimes("video/avc", 35, selected = "h265"))
        assertEquals(listOf("video/avc", "video/av01", "video/hevc"), rules.encoderMimes("video/avc", 35, true))
    }
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

    /** 이력 링크가 끊긴 AI 복제(복제의 복제 포함)도 같은 폴더의 원본 파일 이름으로 원본을 찾습니다. */
    @Test
    fun duplicateFindsOriginalByName() {
        val once = EnhancementRules.duplicateName("사진 [123].jpg", "0a1b2c3d")
        val twice = EnhancementRules.duplicateName(once, "11112222")
        assertEquals("사진 [123]", EnhancementRules.duplicateOrigin(once))
        assertEquals(null, EnhancementRules.duplicateOrigin("사진 [123].jpg"))
        val origin = com.plugin.compatibility.MediaRules.duplicateOrigins(listOf("/d/a/사진 [123].jpg", "/d/b/사진 [123].jpg")) { it }
        assertEquals("/d/a/사진 [123].jpg", origin("/d/a/$twice"))
        assertEquals(null, origin("/d/c/$once"))
        assertTrue(com.plugin.compatibility.PluginMediaMetadataStore.isAiDuplicate(
            "content://x/document/primary%3ADownload%2F" + java.net.URLEncoder.encode(once, "UTF-8").replace("+", "%20")))
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
