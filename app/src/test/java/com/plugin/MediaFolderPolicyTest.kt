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
    /** 비활성 구간은 낮은 구간으로 대체하지 않고 종류별 기본 폴더에 남깁니다. */
    @Test
    fun disabledTiersDoNotFallBackOrAffectOtherMedia() {
        val image = VideoQuality(1920, 1080, 0.0, 0.0, 1_000_000, "image/jpeg")
        val video = VideoQuality(1920, 1080, 30.0, 60.0, 120_000_000, "video/avc")
        val images = MediaRules.withDisabledTiers(MediaRules.DEFAULT_IMAGE_TIERS, "1080x1920")
        val videos = MediaRules.withDisabledTiers(MediaRules.DEFAULT_VIDEO_TIERS, "1080p")
        assertEquals("1080x1920", MediaRules.classificationFolder(image))
        assertNull(MediaRules.classificationFolder(image, images = images))
        assertEquals("1080p", MediaRules.classificationFolder(video, images = images))
        assertNull(MediaRules.classificationFolder(video, videos = videos))
        val original = DownloadPreferences.EMPTY.copyPluginOptions(imageTiers = images, videoTiers = videos)
        assertEquals(setOf("1080x1920"), MediaRules.disabledTiers(original.imageTiers))
        assertEquals(setOf("1080p"), MediaRules.disabledTiers(original.videoTiers))
        assertEquals(MediaRules.imageTiers(MediaRules.DEFAULT_IMAGE_TIERS), MediaRules.imageTiers(images))
        assertNotNull(MediaRules.parseClassificationJson(MediaRules.classificationJson(images, videos)))
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
