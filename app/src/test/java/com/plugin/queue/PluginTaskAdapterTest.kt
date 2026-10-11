package com.plugin.queue

import com.junkfood.seal.download.Task
import com.junkfood.seal.util.DownloadUtil
import org.junit.Assert.assertEquals
import org.junit.Test

/** 원본 작업 어댑터가 기본 요청과 상태 정보를 보존하는지 검사합니다. */
class PluginTaskAdapterTest {
    /** URL·재생목록·사용자 명령을 기존 진입점과 플러그인 모델 사이에서 변환합니다. */
    @Test
    fun convertsOriginalTaskWithoutChangingIdentity() {
        val original = Task(
            url = "https://example.com/video",
            type = Task.TypeInfo.Playlist(index = 3),
            preferences = DownloadUtil.DownloadPreferences.EMPTY,
        )

        val plugin = PluginTaskAdapter.fromSeal(original)
        val adapted = PluginTaskAdapter.toSeal(plugin)

        assertEquals(original.id, plugin.id)
        assertEquals(original.url, adapted.url)
        assertEquals(original.id, adapted.id)
        assertEquals(3, (plugin.type as PluginTask.TypeInfo.Playlist).index)
        assertEquals(3, (adapted.type as Task.TypeInfo.Playlist).index)
    }

    /** 진행 상태·재개 동작·화면 기본 메타데이터를 어댑터 양쪽에서 보존합니다. */
    @Test
    fun convertsTaskStateAcrossBoundary() {
        val original = Task.State(
            downloadState = Task.DownloadState.Running(taskId = "task-id", progress = .42f, progressText = "42%"),
            videoInfo = null,
            viewState = Task.ViewState(url = "https://example.com/video", title = "title"),
        )

        val plugin = PluginTaskAdapter.fromSeal(original)
        val adapted = PluginTaskAdapter.toSeal(plugin)

        assertEquals(.42f, (adapted.downloadState as Task.DownloadState.Running).progress)
        assertEquals("42%", (adapted.downloadState as Task.DownloadState.Running).progressText)
        assertEquals("title", adapted.viewState.title)
        assertEquals("https://example.com/video", adapted.viewState.url)
    }
}
