package com.plugin

import androidx.compose.runtime.mutableStateMapOf
import androidx.test.platform.app.InstrumentationRegistry
import com.plugin.compatibility.removeTaskState
import com.plugin.runtime.BaselineTaskWorker
import com.plugin.queue.PluginTask as Task
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

/** 분리한 기본 작업자가 주입된 상태를 공유하는지 네트워크 없이 검사합니다. */
class SharedDownloaderStateTest {
    /** 외부 프로세스가 없는 조회 작업도 삭제 전 취소하고 반복 취소·재개는 무시합니다. */
    @Test
    fun cancelBeforeProcessStartsAndIgnoreDeletedTask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val states = mutableStateMapOf<Task, Task.State>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val worker = BaselineTaskWorker(context, scope, states)
            val task = Task("https://example.invalid/no-process", preferences = DownloadPreferences.EMPTY)
            val job = kotlinx.coroutines.Job()
            states[task] = Task.State(Task.DownloadState.FetchingInfo(job, task.id), null, Task.ViewState())
            assertTrue(worker.cancel(task))
            assertTrue(job.isCancelled)
            states.removeTaskState(task)
            assertFalse(worker.cancel(task))
            worker.restart(task)
            assertTrue(states.isEmpty())
        } finally { scope.cancel() }
    }
    /** 기본 작업자의 취소·재시작이 주입한 저장소에 적용되는지 확인합니다. */
    @Test
    fun originalEngineUsesSharedStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val states = mutableStateMapOf<Task, Task.State>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val original = BaselineTaskWorker(context, scope, states)
            val task = Task("https://example.invalid/shared-state",
                preferences = PluginPolicy.preferences(DownloadPreferences.EMPTY, emptySet()))
            states[task] = Task.State(Task.DownloadState.Idle, null, Task.ViewState())
            assertEquals(Task.DownloadState.Idle, states[task]?.downloadState)
            assertTrue(original.cancel(task))
            assertTrue(states[task]?.downloadState is Task.DownloadState.Canceled)
            original.restart(task)
            assertEquals(Task.DownloadState.Idle, states[task]?.downloadState)
        } finally {
            scope.cancel()
        }
    }
}
