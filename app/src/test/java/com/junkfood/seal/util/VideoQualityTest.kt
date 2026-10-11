package com.junkfood.seal.util

import com.plugin.compatibility.*
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.database.objects.VideoQuality
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 과거 이력 호환성과 원본·AV1 화질의 독립적인 저장을 검증합니다. */
class VideoQualityTest {
    /** 화질이 없는 과거 백업을 읽고 변환 전·후 값을 구분해 다시 저장합니다. */
    @Test
    fun legacyHistoryAndQualityRoundTrip() {
        val legacy = Json.decodeFromString<DownloadedVideoInfo>(
            """{"id":1,"videoTitle":"test","videoAuthor":"test","videoUrl":"https://example.invalid/1","thumbnailUrl":"","videoPath":"/test.mp4"}"""
        )
        assertNull(legacy.quality)
        assertNull(legacy.sourceQuality)
        val source = VideoQuality(1920, 1080, 30.0, 60.0, 60_000_000, "video/avc")
        val output = source.copy(bytes = 15_000_000, codec = "video/av01")
        val saved = legacy.withMetadata(quality = output, sourceQuality = source)
        assertEquals(legacy, Json.decodeFromString<DownloadedVideoInfo>(Json.encodeToString(legacy)))
        assertEquals(8.0, source.averageMbps!!, 0.0001)
        assertEquals(2.0, output.averageMbps!!, 0.0001)
        assertEquals("고화질", MediaRules.qualityFolder(source.bytes, source.duration, 4.0))
        assertEquals("저화질", MediaRules.qualityFolder(output.bytes, output.duration, 4.0))
        assertNull(VideoQuality().averageMbps)
    }
}
