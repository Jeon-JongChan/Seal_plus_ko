package com.plugin

import com.plugin.queue.PluginTask as Task
import com.plugin.queue.PluginTaskDownloader as DownloaderV2
import androidx.compose.runtime.mutableStateMapOf
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.InstagramMedia
import com.plugin.compatibility.withoutCompletedFile
import com.plugin.compatibility.removeCompletedFile
import com.plugin.compatibility.postPageCount
import com.plugin.queue.QueueMedia
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 페이지 삭제 후 대기열 전체 수·파일 없음 표시·백업 복원을 검증합니다. */
class QueuePageCountTest {
    /** 전체 14페이지인 게시물의 독립 작업을 만듭니다. */
    private fun task(index: Int): Task {
        val media = InstagramMedia(index.toString(), "https://www.instagram.com/p/BA/",
            "", "", false, "post", "author", pageIndex = index, pageCount = 14)
        return Task(media.postUrl, Task.TypeInfo.SocialMedia(media), DownloadPreferences.EMPTY)
    }

    /** 파일을 만들지 않고 완료 상태만 구성합니다. */
    private fun completed(index: Int) = Task.State(Task.DownloadState.Completed("/$index.mp4"),
        null, Task.ViewState(completedCount = 1))

    /** 한 페이지 삭제 후에도 14를 유지하고 넘김 목록에서만 제외합니다. */
    @Test
    fun deletionKeepsOriginalTotalAndSurvivesBackup() {
        val states = (0 until 14).associate { task(it) to completed(it) }.toMutableMap()
        val page = states.keys.first()
        states[page] = states.getValue(page).withoutCompletedFile("/0.mp4")
        val restored = states.map { (task, state) ->
            Json.decodeFromString<Task>(Json.encodeToString(task)) to
                Json.decodeFromString<Task.State>(Json.encodeToString(state))
        }.toMap()
        val view = QueueMedia.grouped(restored).values.single().viewState
        assertEquals(14, restored.size)
        assertEquals(14, view.mediaCount)
        assertEquals(13, view.completedCount)
        assertEquals(1, QueueMedia.missingPages(page, restored))
        assertEquals(13, QueueMedia.completedPages(page, restored).size)
        assertFalse(QueueMedia.completedPages(page, restored).contains("/0.mp4"))
    }

    /** 선택 제외·진행 전·실패 페이지를 삭제된 페이지로 잘못 표시하지 않습니다. */
    @Test
    fun originalTotalIncludesUnselectedPagesWithoutCallingThemDeleted() {
        val page = task(0)
        val states = mapOf(page to completed(0), task(1) to completed(1).copy(
            downloadState = Task.DownloadState.Error(action = Task.RestartableAction.Download)),
            task(2) to completed(2).copy(downloadState = Task.DownloadState.Idle))
        val view = QueueMedia.grouped(states).values.single().viewState
        assertEquals(14, view.mediaCount)
        assertEquals(1, view.completedCount)
        assertEquals(0, QueueMedia.missingPages(page, states))
    }

    /** 복제 파일을 순서대로 삭제해도 기존 파일 수와 삭제 수를 보존합니다. */
    @Test
    fun deletingCopiesDoesNotShrinkCount() {
        val page = Task("https://www.instagram.com/p/BA/", preferences = DownloadPreferences.EMPTY)
        val initial = completed(0).copy(downloadState = Task.DownloadState.Completed(
            "/0.mp4", listOf("/0.mp4", "/0-av1.mp4")))
        val partial = initial.withoutCompletedFile("/0.mp4")
        val states = mapOf(page to partial)
        val view = QueueMedia.grouped(states).values.single().viewState
        assertEquals(2, view.mediaCount)
        assertEquals(1, view.completedCount)
        assertEquals(1, QueueMedia.missingPages(page, states))
        val empty = partial.withoutCompletedFile("/0-av1.mp4")
        assertTrue((empty.downloadState as Task.DownloadState.Completed).missing)
        assertEquals(2, QueueMedia.grouped(mapOf(page to empty)).values.single().viewState.mediaCount)
        assertEquals(2, QueueMedia.missingPages(page, mapOf(page to empty)))
        assertTrue(QueueMedia.completedPages(page, mapOf(page to empty)).isEmpty())
    }

    /** 진행 중 작업과 다른 파일은 삭제 알림으로 변경하지 않습니다. */
    @Test
    fun unrelatedAndActiveTasksRemainUnchanged() {
        val initial = completed(0)
        assertSame(initial, initial.withoutCompletedFile("/other.mp4"))
        val active = initial.copy(downloadState = Task.DownloadState.Idle)
        assertSame(active, active.withoutCompletedFile("/0.mp4"))
    }

    /** 실제 삭제 알림 연결점도 게시물 작업을 제거하지 않고 파일 없음으로 유지합니다. */
    @Test
    fun deletionCallbackKeepsPostTask() {
        val states = mutableStateMapOf<Task, Task.State>()
        val downloader = object : DownloaderV2 {
            /** 검사 상태를 반환합니다. */
            override fun getTaskStateMap() = states
            /** 네트워크 작업을 시작하지 않습니다. */
            override fun enqueue(task: Task) = Unit
            /** 검사 상태만 등록합니다. */
            override fun enqueue(task: Task, state: Task.State) { states[task] = state }
            /** 검사 중 취소할 작업은 없습니다. */
            override fun cancel(task: Task) = false
            /** 검사 중 재시작하지 않습니다. */
            override fun restart(task: Task) = Unit
            /** 명시적 항목 제거만 실행합니다. */
            override fun remove(task: Task) = states.remove(task) != null
        }
        val page = task(0)
        states[page] = completed(0)
        downloader.removeCompletedFile("/0.mp4")
        assertTrue(states.containsKey(page))
        assertTrue((states.getValue(page).downloadState as Task.DownloadState.Completed).missing)
        assertEquals(14, QueueMedia.grouped(states).values.single().viewState.mediaCount)
        assertEquals(1, QueueMedia.missingPages(page, states))
    }

    /** 게시물의 인코딩 복제는 전체 페이지를 늘리거나 원본 삭제만으로 페이지 삭제가 되지 않습니다. */
    @Test
    fun postCopiesRepresentOnePage() {
        val page = task(0)
        val original = completed(0).copy(downloadState = Task.DownloadState.Completed(
            "/0.mp4", listOf("/0.mp4", "/0-av1.mp4")))
        for (state in listOf(original, original.withoutCompletedFile("/0.mp4"))) {
            val states = mapOf(page to state)
            assertEquals(14, QueueMedia.grouped(states).values.single().viewState.mediaCount)
            assertEquals(1, QueueMedia.grouped(states).values.single().viewState.completedCount)
            assertEquals(0, QueueMedia.missingPages(page, states))
        }
        val deleted = original.withoutCompletedFile("/0.mp4").withoutCompletedFile("/0-av1.mp4")
        assertEquals(1, QueueMedia.missingPages(page, mapOf(page to deleted)))
    }

    /** 이력에서 URL 작업으로 복구해도 게시물 전체 수를 출력 수로 대체하지 않습니다. */
    @Test
    fun restoredUrlTaskKeepsRecordedPostTotal() {
        val page = Task("https://www.instagram.com/p/BA/", preferences = DownloadPreferences.EMPTY)
        val initial = completed(0).copy(videoInfo = com.junkfood.seal.util.VideoInfo().apply { postPageCount = 14 })
        val deleted = initial.withoutCompletedFile("/0.mp4")
        assertEquals(14, QueueMedia.grouped(mapOf(page to deleted)).values.single().viewState.mediaCount)
        assertEquals(1, QueueMedia.missingPages(page, mapOf(page to deleted)))
    }
}
