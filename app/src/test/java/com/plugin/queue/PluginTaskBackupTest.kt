package com.plugin.queue

import com.junkfood.seal.download.Task
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.InstagramMedia
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 저장소와 네트워크 없이 전용 백업 및 기존 대기열 이전을 검증합니다. */
class PluginTaskBackupTest {
    /** 소셜 페이지의 원래 수·삭제 상태·모든 파일·정렬 시간을 왕복 보존합니다. */
    @Test
    fun nativeBackupKeepsDeletedPageMetadata() {
        val media = InstagramMedia("page", "https://example.com/post", "", "", true,
            "제목", "작성자", pageIndex = 3, pageCount = 14)
        val task = PluginTask(media.postUrl, PluginTask.TypeInfo.SocialMedia(media),
            DownloadPreferences.EMPTY, "stable-id", timeCreated = 123L)
        val state = PluginTask.State(
            PluginTask.DownloadState.Completed("/a.jpg", listOf("/a.jpg", "/b.jpg"), missing = true),
            null, PluginTask.ViewState(isImage = true, mediaCount = 14, codec = "av1"))
        val tasks = mapOf(task to state)
        assertEquals(tasks, PluginTaskBackup.decode(PluginTaskBackup.encode(tasks)))
        val restored = PluginTaskBackup.decode(PluginTaskBackup.encode(tasks)).keys.single()
        assertEquals(123L, restored.timeCreated)
        assertEquals(14, (restored.type as PluginTask.TypeInfo.SocialMedia).media.pageCount)
        assertEquals(task, task.copy(timeCreated = 456L))
        assertEquals(task.hashCode(), task.copy(timeCreated = 456L).hashCode())
    }

    /** 기존 기준 모델의 작업 ID·실행 진행률·본문은 이전 중에 변경하지 않습니다. */
    @Test
    fun legacyBackupChangesOnlyTypeDiscriminators() {
        val task = Task("com.junkfood.seal.download.Task.TypeInfo.Instagram", preferences = DownloadPreferences.EMPTY,
            id = "existing-id")
        val state = Task.State(Task.DownloadState.Canceled(Task.RestartableAction.Download, .42f),
            null, Task.ViewState(title = task.url))
        val legacy = Json { allowStructuredMapKeys = true }.encodeToString(mapOf(task to state))
        val restored = PluginTaskBackup.decodeLegacy(legacy).entries.single()
        assertEquals(task.id, restored.key.id)
        assertEquals(task.timeCreated, restored.key.timeCreated)
        assertEquals(task.url, restored.value.viewState.title)
        assertEquals(.42f, (restored.value.downloadState as PluginTask.DownloadState.Canceled).progress!!, .0001f)
    }

    /** 손상된 백업을 정상적인 빈 백업으로 오인하지 않습니다. */
    @Test
    fun corruptedBackupIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PluginTaskBackup.decode("broken") }
    }
}
