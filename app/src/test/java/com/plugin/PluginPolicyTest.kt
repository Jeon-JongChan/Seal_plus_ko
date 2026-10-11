package com.plugin

import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.*
import org.junit.Assert.*
import org.junit.Test

/** 비활성화·개별 활성화 시 저장 설정과 요청 설정의 분리를 검증합니다. */
class PluginPolicyTest {
    /** 대기열 표시 기능과 다운로드 창 확장을 독립적으로 선택합니다. */
    @Test
    fun dialogRoutingUsesEnabledInputFeatures() {
        val state = com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState.InputUrl
        assertFalse(com.plugin.runtime.usePluginDialog(state, emptySet()))
        assertFalse(com.plugin.runtime.usePluginDialog(state, setOf(PluginId.QUEUE)))
        (PluginId.entries - PluginId.QUEUE).forEach {
            assertTrue(com.plugin.runtime.usePluginDialog(state, setOf(it)))
        }
    }

    /** 화질·음원 전환 후에도 현재 요청의 확장 선택값을 유지합니다. */
    @Test
    fun changingCoreFieldsPreservesPluginOptions() {
        val initial = DownloadPreferences.EMPTY.copyPluginOptions(imageResolution = 720,
            downloadAllMedia = true, mediaSelection = 2, listProcessing = true)
        val audio = initial.copyPreservingPluginOptions { copy(extractAudio = true, videoResolution = 3) }
        assertTrue(audio.extractAudio)
        assertEquals(3, audio.videoResolution)
        assertEquals(720, audio.imageResolution)
        assertTrue(audio.downloadAllMedia && audio.listProcessing)
        assertEquals(2, audio.mediaSelection)
        assertFalse(initial.extractAudio)
    }

    /** 기본 모델이 동일한 확장 설정 변경도 Compose 상태에 적용합니다. */
    @Test
    fun extensionOnlyChangesUpdateUiState() {
        val initial = DownloadPreferences.EMPTY.copyPluginOptions(classifyMedia = false)
        val state = pluginPreferencesState(initial)
        state.value = initial.copyPluginOptions(classifyMedia = true)
        assertEquals(initial, state.value)
        assertTrue(state.value.classifyMedia)
        assertFalse(initial.classifyMedia)
    }

    /** 모두 끄면 확장 동작을 배제하고 원본 쿠키·형식 설정은 보존합니다. */
    @Test
    fun disabledPluginsPreserveCoreAndSavedSettings() {
        val saved = DownloadPreferences.EMPTY.copy(cookies = true, videoResolution = 3)
            .copyPluginOptions(cookieProfileId = 7,
                mediaSelection = 2, imageResolution = 720,
                downloadAllMedia = true, classifyMedia = true, transcodeAv1 = true,
                keepAv1Original = true, videoContainer = "mkv", queueEnhancements = true, socialProcessing = true)
        val actual = PluginPolicy.preferences(saved, emptySet())
        assertTrue(actual.cookies)
        assertEquals(3, actual.videoResolution)
        assertEquals(-1, actual.cookieProfileId)
        assertEquals(0, actual.mediaSelection)
        assertEquals(0, actual.imageResolution)
        assertFalse(actual.downloadAllMedia || actual.classifyMedia || actual.transcodeAv1 ||
            actual.keepAv1Original || actual.mediaProcessing || actual.reuseExisting || actual.queueEnhancements || actual.socialProcessing)
        assertEquals("mp4", actual.videoContainer)
        assertTrue(saved.classifyMedia && saved.transcodeAv1)
        assertEquals(saved.copyPluginOptions(mediaProcessing = true, reuseExisting = true),
            PluginPolicy.preferences(saved, PluginId.entries.toSet()))
        assertEquals(listOf(DownloadType.Video, DownloadType.Audio, DownloadType.Playlist),
            PluginPolicy.downloadTypes(emptySet(), false))
        assertTrue(DownloadType.Command in PluginPolicy.downloadTypes(emptySet(), true))
    }

    /** 한 기능을 켜도 다른 기능의 저장 설정을 적용하지 않습니다. */
    @Test
    fun enablingEachPluginDoesNotEnableOthers() {
        val saved = DownloadPreferences.EMPTY.copyPluginOptions(mediaSelection = 2, downloadAllMedia = true,
            transcodeAv1 = true, classifyMedia = true, cookieProfileId = 7)
        PluginId.entries.forEach { id ->
            val actual = PluginPolicy.preferences(saved, setOf(id))
            assertEquals(id == PluginId.MEDIA, actual.transcodeAv1)
            assertEquals(id == PluginId.LIBRARY, actual.classifyMedia)
            assertEquals(id == PluginId.MEDIA || id == PluginId.LIBRARY, actual.mediaProcessing)
            assertEquals(id == PluginId.MEDIA, actual.reuseExisting)
            assertEquals(id == PluginId.QUEUE, actual.queueEnhancements)
            assertEquals(id == PluginId.SOCIAL, actual.socialProcessing)
            assertEquals(if (id == PluginId.SOCIAL) 2 else 0, actual.mediaSelection)
            assertEquals(if (id == PluginId.INTERACTION) 7 else -1, actual.cookieProfileId)
            assertEquals(id == PluginId.SOCIAL || id == PluginId.LIST, actual.downloadAllMedia)
            assertEquals(listOf(DownloadType.Video, DownloadType.Audio, DownloadType.Playlist),
                PluginPolicy.downloadTypes(setOf(id), false))
        }
    }
}
