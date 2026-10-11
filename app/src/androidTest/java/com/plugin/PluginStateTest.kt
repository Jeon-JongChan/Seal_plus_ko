package com.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.plugin.compatibility.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 MMKV 저장소에서 스위치 저장과 새 다운로드 설정을 검사합니다. */
@RunWith(AndroidJUnit4::class)
class PluginStateTest {
    /** 각 스위치를 따로 켜고 끄며 다른 기능과 기존 설정의 독립성을 확인합니다. */
    @Test
    fun switchesPersistAndApplyIndependently() {
        val previous = PluginId.entries.associateWith(PluginState::isEnabled)
        try {
            PluginId.entries.forEach { PluginState.setEnabled(it, false) }
            assertTrue(PluginId.entries.none(PluginState::isEnabled))
            assertTrue(CompatibilityPlugin.enabled)
            val baseline = createPluginPreferences()
            assertFalse(baseline.mediaProcessing || baseline.reuseExisting || baseline.downloadAllMedia)
            PluginId.entries.forEach { id ->
                PluginState.setEnabled(id, true)
                assertTrue(id.preferenceKey.getBoolean(false))
                assertEquals(listOf(id), PluginId.entries.filter(PluginState::isEnabled))
                val actual = createPluginPreferences()
                assertEquals(id == PluginId.MEDIA || id == PluginId.LIBRARY, actual.mediaProcessing)
                assertEquals(id == PluginId.MEDIA, actual.reuseExisting)
                assertEquals(id == PluginId.QUEUE, actual.queueEnhancements)
                assertEquals(id == PluginId.SOCIAL, actual.socialProcessing)
                PluginState.setEnabled(id, false)
                assertFalse(id.preferenceKey.getBoolean(true))
            }
        } finally {
            previous.forEach { (id, value) -> PluginState.setEnabled(id, value) }
        }
    }
}
