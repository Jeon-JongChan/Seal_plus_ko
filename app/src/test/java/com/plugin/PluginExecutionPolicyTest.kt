package com.plugin

import com.plugin.queue.PluginTask as Task
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.*
import com.plugin.runtime.PluginExecutionPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** 개별 플러그인과 기존 작업의 실행 경로를 검증합니다. */
class PluginExecutionPolicyTest {
    /** 모두 끈 새 작업은 원본 엔진으로, 선택 작업은 해당 확장 경로로 갑니다. */
    @Test
    fun selectsEngineFromTaskSnapshot() {
        assertFalse(DownloadPreferences.EMPTY.mediaProcessing)
        assertFalse(DownloadPreferences.EMPTY.reuseExisting)
        val original = PluginPolicy.preferences(DownloadPreferences.EMPTY, emptySet())
        assertFalse(PluginExecutionPolicy.usesEnhancedEngine(Task("original", preferences = original)))
        listOf(PluginId.MEDIA, PluginId.LIBRARY, PluginId.QUEUE, PluginId.SOCIAL).forEach { id ->
            val saved = PluginPolicy.preferences(original, setOf(id))
            val task = Task(id.name, preferences = saved)
            assertTrue(PluginExecutionPolicy.usesEnhancedEngine(task))
            assertFalse(PluginExecutionPolicy.usesEnhancedEngine(task.copy(
                preferences = PluginPolicy.preferences(saved, emptySet()))))
            // 다음 요청을 제한해도 이미 등록한 작업의 사본은 그대로입니다.
            assertTrue(PluginExecutionPolicy.usesEnhancedEngine(task))
        }
        assertTrue(CompatibilityPlugin.enabled)
    }

    /** 목록 조회는 소셜 기능 없이도 이미지 선택과 전용 실행 경로를 유지합니다. */
    @Test
    fun listRequestsWorkWithoutSocialPlugin() {
        val saved = DownloadPreferences.EMPTY.copyPluginOptions(listProcessing = true, mediaSelection = 2)
        val list = PluginPolicy.preferences(saved, setOf(PluginId.LIST))
        assertTrue(list.listProcessing)
        assertFalse(list.socialProcessing || list.mediaProcessing || list.queueEnhancements)
        assertEquals(2, list.mediaSelection)
        assertTrue(PluginExecutionPolicy.usesEnhancedEngine(Task("list", preferences = list)))
        val disabled = PluginPolicy.preferences(saved, emptySet())
        assertFalse(disabled.listProcessing)
        assertEquals(0, disabled.mediaSelection)
    }

    /** 플러그인 옵션 필드가 없던 백업은 추가 기능 경로를 사용하지 않습니다. */
    @Test
    fun legacyTaskSettingsUseOriginalEngine() {
        val oldJson = Json.parseToJsonElement(Json.encodeToString(DownloadPreferences.EMPTY))
            .jsonObject.toMutableMap().apply {
                remove("mediaProcessing")
                remove("reuseExisting")
            }
        val preferences = Json.decodeFromJsonElement<DownloadPreferences>(JsonObject(oldJson))
        val task = Task("legacy", preferences = preferences)

        assertFalse(preferences.mediaProcessing)
        assertFalse(preferences.reuseExisting)
        assertFalse(PluginExecutionPolicy.usesEnhancedEngine(task))
    }
}
