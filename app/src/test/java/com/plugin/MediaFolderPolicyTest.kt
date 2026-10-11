package com.plugin

import com.junkfood.seal.database.objects.VideoQuality
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 이미지·영상 구간의 독립 선택과 등록 작업의 설정 보존을 검증합니다. */
class MediaFolderPolicyTest {
    /** 분류 대상이 다른 종류와 다운로드 종류 선택을 바꾸지 않고 사본에도 보존되는지 검증합니다. */
    @Test
    fun classificationTargetIsIndependentAndPreserved() {
        val image = VideoQuality(1920, 1080, 0.0, 0.0, 1_000_000, "image/jpeg")
        val video = VideoQuality(1920, 1080, 30.0, 60.0, 120_000_000, "video/avc")
        assertNotNull(MediaRules.classificationFolder(image, target = 0))
        assertNotNull(MediaRules.classificationFolder(video, target = 0))
        assertNull(MediaRules.classificationFolder(image, target = 1))
        assertNotNull(MediaRules.classificationFolder(video, target = 1))
        assertNotNull(MediaRules.classificationFolder(image, target = 2))
        assertNull(MediaRules.classificationFolder(video, target = 2))
        val options = DownloadPreferences.EMPTY.copyPluginOptions(classificationTarget = 2, mediaSelection = 1)
        val copied = options.copyPreservingPluginOptions { copy() }
        assertEquals(2, copied.classificationTarget)
        assertEquals(1, copied.mediaSelection)
    }
    /** 기본 ID 파일만 연결하며 제목 전용·구간·다른 ID와 로컬 URL은 제외합니다. */
    @Test
    fun reconnectRequiresSourceId() {
        assertTrue(MediaRules.canReconnect("제목 [abc_123].mp4", "https://youtube.com/watch?v=abc_123"))
        assertTrue(MediaRules.canReconnect("사진 [123456].jpg", "https://tiktok.com/@user/photo/123456"))
        assertTrue(MediaRules.canReconnect("사진 [64].jpg", "https://instagram.com/p/BA/"))
        assertTrue(MediaRules.canReconnect("Video by deerdeer0303 [Dc_KWEXyzXP].mp4", "https://www.instagram.com/reel/Dc_KWEXyzXP/?stkn=test"))
        assertTrue(MediaRules.canReconnect("사진 [123456].jpg", "https://instagram.com/p/BA/#seal-media=123456"))
        assertTrue(MediaRules.canReconnect("제목 [abc_123]-av1.mp4", "https://youtube.com/watch?v=abc_123"))
        assertFalse(MediaRules.canReconnect("제목.mp4", "https://youtube.com/watch?v=abc_123"))
        assertFalse(MediaRules.canReconnect("제목 [0-30].mp4", "https://youtube.com/watch?v=abc_123"))
        assertFalse(MediaRules.canReconnect("제목 [123].mp4", "https://example.com/video/1234"))
        assertFalse(MediaRules.canReconnect("제목 [abc_123].mp4", "/local/abc_123"))
    }
    /** 원본 백업의 개별 ID·묶음 요청 URL과 폴더별 중복 이력을 검증합니다. */
    @Test
    fun legacyBackupSelectsStoredFilenameAndUnambiguousHistory() {
        val name = "Video by deerdeer0303 [Dc_KWEXyzXP].mp4"
        val direct = com.junkfood.seal.database.objects.DownloadedVideoInfo(1, "제목", "작성자",
            "https://www.instagram.com/reel/Dc_KWEXyzXP/?stkn=test", "", "/old/$name", "Instagram")
        val batch = direct.copy(id = 2, videoPath = "/old/Instagram/$name",
            videoUrl = "https://www.instagram.com/reel/DdTcD9VSGVY/?stkn=test")
        assertEquals(batch.videoPath, MediaRules.reconnectionSource(name, listOf(batch)))
        assertEquals(direct.videoPath, MediaRules.reconnectionSource(name, listOf(batch, direct)))
        assertEquals(direct.videoPath, MediaRules.reconnectionSource(name, listOf(direct, direct.copy(id = 3))))
        assertNull(MediaRules.reconnectionSource(name, listOf(batch, batch.copy(videoPath = "/other/$name"))))
        assertNull(MediaRules.reconnectionSource("제목.mp4", listOf(direct)))
        assertNull(MediaRules.reconnectionSource(name, listOf(direct.copy(videoUrl = "/local/file"))))
    }
    /** 플랫폼·사용자 폴더와 숫자로 된 루트를 유지하고 화질 폴더 한 단계만 제거합니다. */
    @Test
    fun reclassificationPreservesParentAndRoot() {
        val root = java.io.File("build/folder-check/Seal").canonicalFile
        for (name in listOf("Instagram", "Instagram/여행", "수동분류")) {
            val parent = root.resolve(name)
            assertEquals(parent, MediaRules.classificationBase(parent.resolve("1920x1080"), root))
            assertEquals(parent, MediaRules.classificationBase(parent.resolve("720p"), root))
            assertEquals(parent, MediaRules.classificationBase(parent, root))
        }
        val numericRoot = root.resolve("1080p")
        assertEquals(numericRoot, MediaRules.classificationBase(numericRoot, numericRoot))
        try {
            MediaRules.classificationBase(root.resolve("../밖"), root)
            fail("루트 밖 경로를 허용했습니다.")
        } catch (_: IllegalArgumentException) { /* 경계 검증의 예상 결과입니다. */ }
    }

    /** 요청한 48개 코덱·FPS 경계의 직전과 정확한 경계를 검사합니다. */
    @Test
    fun codecAndFpsTablesUseExactBoundaries() {
        val codecs = listOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01")
        val rows = listOf(
            360 to listOf(.040, .030, .030, .020, .060, .045, .045, .030),
            480 to listOf(.070, .050, .055, .035, .105, .075, .083, .053),
            720 to listOf(.140, .095, .110, .080, .210, .143, .165, .120),
            1080 to listOf(.250, .170, .190, .140, .375, .255, .285, .210),
            1440 to listOf(.600, .400, .450, .330, .900, .600, .675, .495),
            2160 to listOf(1.300, .850, 1.000, .720, 1.950, 1.275, 1.500, 1.080))
        for ((height, values) in rows) for ((index, codec) in codecs.withIndex()) for (high in listOf(false, true)) {
            val threshold = values[index + if (high) 4 else 0]
            val bytes = Math.round(threshold * 1_000_000 * 100)
            val quality = VideoQuality(height * 2, height, if (high) 60.0 else 30.0, 100.0, bytes, codec)
            assertEquals("${height}p", MediaRules.classificationFolder(quality))
            assertNotEquals("${height}p", MediaRules.classificationFolder(quality.copy(bytes = bytes - 100)))
        }
        assertNull(MediaRules.classificationFolder(VideoQuality(1920, 1080, 30.0, 100.0, 99_000_000, "unknown")))
        assertEquals(MediaRules.DEFAULT_VIDEO_TIERS + ";disabled=720p",
            MediaRules.currentVideoTiers(MediaRules.LEGACY_VIDEO_TIERS + ";disabled=720p"))
        assertEquals("720:0.2:0.3", MediaRules.currentVideoTiers("720:0.2:0.3"))
    }

    /** 비활성 구간은 낮은 활성 구간으로 대체하며 이미지·영상 선택은 서로 독립적입니다. */
    @Test
    fun disabledTiersDoNotFallBackOrAffectOtherMedia() {
        val image = VideoQuality(1920, 1080, 0.0, 0.0, 1_000_000, "image/jpeg")
        val video = VideoQuality(1920, 1080, 30.0, 60.0, 120_000_000, "video/avc")
        val images = MediaRules.withDisabledTiers(MediaRules.DEFAULT_IMAGE_TIERS, "1080x1920")
        val videos = MediaRules.withDisabledTiers(MediaRules.DEFAULT_VIDEO_TIERS, "1080p")
        assertEquals("1080x1920", MediaRules.classificationFolder(image))
        assertEquals("720x1280", MediaRules.classificationFolder(image, images = images))
        assertEquals("1080p", MediaRules.classificationFolder(video, images = images))
        assertEquals("720p", MediaRules.classificationFolder(video, videos = videos))
        val original = DownloadPreferences.EMPTY.copyPluginOptions(imageTiers = images, videoTiers = videos)
        assertEquals(setOf("1080x1920"), MediaRules.disabledTiers(original.imageTiers))
        assertEquals(setOf("1080p"), MediaRules.disabledTiers(original.videoTiers))
        assertEquals(MediaRules.imageTiers(MediaRules.DEFAULT_IMAGE_TIERS), MediaRules.imageTiers(images))
        assertNotNull(MediaRules.parseClassificationJson(MediaRules.classificationJson(images, videos)))
    }

    /** 빈 폴더명은 생략하고 경로 이동 이름은 기본값으로 복구합니다. */
    @Test
    fun emptyFolderAndDisabledLowerTiersAreHandled() {
        assertEquals("", MediaRules.optionalFolderName("", "Images"))
        assertEquals("Images", MediaRules.optionalFolderName("../Images", "Images"))
        val video = VideoQuality(1280, 720, 30.0, 60.0, 120_000_000, "video/avc")
        val disabled = MediaRules.withDisabledTiers(MediaRules.DEFAULT_VIDEO_TIERS, "720p,360p")
        assertEquals("480p", MediaRules.classificationFolder(video, videos = disabled))
        assertEquals(2.0, MediaRules.bitrateMbps(120_000_000, 60.0)!! / 8, 0.0001)
    }

    /** 파일 관리만 켜도 분류를 실행하며 인코딩과 파일 재사용은 활성화하지 않습니다. */
    @Test
    fun folderPluginRunsWithoutEncodingPlugin() {
        val saved = DownloadPreferences.EMPTY.copyPluginOptions(classifyMedia = true, transcodeAv1 = true)
        val library = PluginPolicy.preferences(saved, setOf(PluginId.LIBRARY))
        assertTrue(library.classifyMedia && library.mediaProcessing)
        assertFalse(library.transcodeAv1 || library.reuseExisting)
        assertEquals("Images", library.imageFolderName)
        val encoding = PluginPolicy.preferences(saved, setOf(PluginId.MEDIA))
        assertTrue(encoding.transcodeAv1 && encoding.reuseExisting)
        assertFalse(encoding.classifyMedia || encoding.platformFolders)
        assertEquals("", encoding.imageFolderName)
        assertEquals("", encoding.videoFolderName)
    }
}
