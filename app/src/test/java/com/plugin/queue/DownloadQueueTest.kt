package com.plugin.queue

import com.plugin.compatibility.requireTaskState
import com.plugin.compatibility.updateTaskState
import com.plugin.compatibility.removeTaskState
import com.plugin.queue.PluginTask as Task
import com.plugin.queue.PluginTaskDownloader as DownloaderV2

import com.plugin.compatibility.removeCompletedFile

import androidx.compose.runtime.mutableStateMapOf
import com.junkfood.seal.util.DownloadUtil
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 파일 삭제 연동이 다른 파일·진행 중인 작업에 영향을 주지 않는지 검증합니다. */
class DownloadQueueTest {
    /** 삭제 후 진행·완료 콜백이 상태를 읽거나 갱신해도 NPE와 작업 부활이 없는지 검사합니다. */
    @Test
    fun lateCallbacksCannotRestoreRemovedTasks() {
        val task = Task("https://example.invalid/deleted", preferences = DownloadUtil.DownloadPreferences.EMPTY)
        val states = mutableStateMapOf(task to Task.State(Task.DownloadState.Idle, null, Task.ViewState()))
        assertTrue(states.updateTaskState(task) { it.copy(downloadState = Task.DownloadState.Completed(null)) })
        assertNotNull(states.removeTaskState(task))
        assertFalse(states.updateTaskState(task) { it.copy(downloadState = Task.DownloadState.Idle) })
        assertNull(states.removeTaskState(task))
        val failure = runCatching { states.requireTaskState(task) }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(states.isEmpty())
    }
    /** 경로와 file URI가 같은 완료 파일만 제거하고 진행 중인 작업은 보존합니다. */
    @Test
    fun removeCompletedFileOnlyRemovesMatchingCompletedTasks() {
        val states = mutableStateMapOf<Task, Task.State>()
        val downloader = object : DownloaderV2 {
            /** 테스트 대기열을 반환합니다. */
            override fun getTaskStateMap() = states
            /** 테스트에서는 실제 다운로드를 시작하지 않습니다. */
            override fun enqueue(task: Task) = Unit
            /** 제공된 상태만 등록합니다. */
            override fun enqueue(task: Task, state: Task.State) { states[task] = state }
            /** 테스트에서는 취소를 실행하지 않습니다. */
            override fun cancel(task: Task) = false
            /** 테스트에서는 재시작하지 않습니다. */
            override fun restart(task: Task) = Unit
            /** 지정된 테스트 항목만 제거합니다. */
            override fun remove(task: Task) = states.remove(task) != null
        }
        val file = File("queue-check.mp4").absoluteFile
        val completed = Task("completed", preferences = DownloadUtil.DownloadPreferences.EMPTY)
        val other = Task("other", preferences = DownloadUtil.DownloadPreferences.EMPTY)
        val pending = Task("pending", preferences = DownloadUtil.DownloadPreferences.EMPTY)
        states[completed] = Task.State(Task.DownloadState.Completed(file.path), null, Task.ViewState())
        states[other] = Task.State(Task.DownloadState.Completed(file.resolveSibling("other.mp4").path), null, Task.ViewState())
        states[pending] = Task.State(Task.DownloadState.Idle, null, Task.ViewState())
        downloader.removeCompletedFile(file.toURI().toString())
        assertFalse(states.containsKey(completed))
        assertTrue(states.containsKey(other))
        assertTrue(states.containsKey(pending))
        // 다른 URI나 일치하지 않는 파일에서는 대기열을 보존합니다.
        downloader.removeCompletedFile("content://example/document/queue-check.mp4")
        assertEquals(2, states.size)
    }
}
