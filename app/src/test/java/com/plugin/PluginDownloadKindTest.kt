package com.plugin

import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.*
import org.junit.Assert.*
import org.junit.Test

/** 다섯 가지 입력 유형의 순서와 실제 요청 옵션 변환을 검증합니다. */
class PluginDownloadKindTest {
    /** 프로필 입력은 계정만 허용하고 일반 재생목록·개별 게시물 주소를 거부합니다. */
    @Test
    fun profileInputsExcludePlaylists() {
        assertEquals("sample_user", MediaRules.instagramAccount("@sample_user"))
        assertEquals("sample_user", MediaRules.instagramAccount("https://www.instagram.com/sample_user/"))
        assertNull(MediaRules.instagramAccount("https://www.youtube.com/playlist?list=sample"))
        assertNull(MediaRules.instagramAccount("https://www.instagram.com/p/sample/"))
        assertNull(MediaRules.instagramAccount("https://instagram.com.evil.example/sample_user/"))
    }

    /** 자동은 혼합 미디어를 허용하고 명시적인 유형만 필터링합니다. */
    @Test
    fun kindsProduceIndependentRequestOptions() {
        assertEquals(listOf("Auto", "Video", "Image", "Playlist", "Audio"), PluginDownloadKind.entries.map { it.name })
        val initial = DownloadPreferences.EMPTY.copyPluginOptions(imageResolution = 720, downloadAllMedia = true)
        for ((kind, selection) in listOf(PluginDownloadKind.Auto to 0, PluginDownloadKind.Video to 1,
            PluginDownloadKind.Image to 2, PluginDownloadKind.Playlist to 0, PluginDownloadKind.Audio to 1)) {
            val result = kind.preferences(initial)
            assertEquals(selection, result.mediaSelection)
            assertEquals(kind == PluginDownloadKind.Audio, result.extractAudio)
            assertEquals(kind == PluginDownloadKind.Playlist, result.downloadPlaylist)
            assertEquals(720, result.imageResolution)
            assertTrue(result.downloadAllMedia)
        }
        assertTrue(PluginDownloadKind.Auto.preferences(initial, playlistUrl = true).downloadPlaylist)
        assertFalse(PluginDownloadKind.Video.preferences(initial, playlistUrl = true).downloadPlaylist)
    }
}
