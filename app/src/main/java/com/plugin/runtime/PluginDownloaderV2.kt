package com.plugin.runtime

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
import com.plugin.queue.PluginTask
import com.plugin.queue.PluginTaskAdapter
import com.plugin.queue.PluginTaskDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 기존 Seal API를 전용 엔진으로 연결하며 원본 화면에는 표시용 복사본을 제공합니다. */
class PluginDownloaderV2(appContext: Context) : DownloaderV2 {
    val queue: PluginTaskDownloader = PluginTaskEngine(appContext)
    private val displayed = mutableStateMapOf<Task, Task.State>()
    private val requests = mutableMapOf<String, Task>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            snapshotFlow { queue.getTaskStateMap().toMap() }.collect { tasks ->
                Snapshot.withMutableSnapshot {
                    val ids = tasks.keys.map { it.id }.toSet()
                    requests.keys.retainAll(ids)
                    displayed.keys.filter { it.id !in ids }.forEach { displayed.remove(it) }
                    tasks.forEach { (task, state) ->
                        // 표시용 객체를 유지해 원본 화면의 생성 순서가 갱신마다 바뀌지 않습니다.
                        val request = requests.getOrPut(task.id) { PluginTaskAdapter.toSeal(task) }
                        displayed[request] = PluginTaskAdapter.toSeal(state)
                    }
                }
            }
        }
    }

    /** 원본 화면과 서비스가 관찰하는 기본 상태 복사본을 반환합니다. */
    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> = displayed

    /** 원본 요청을 전용 요청으로 복사해 등록합니다. */
    override fun enqueue(task: Task) = queue.enqueue(PluginTaskAdapter.fromSeal(task))

    /** 원본 요청의 조회 결과를 전용 상태로 복사해 등록합니다. */
    override fun enqueue(task: Task, state: Task.State) =
        queue.enqueue(PluginTaskAdapter.fromSeal(task), PluginTaskAdapter.fromSeal(state))

    /** 표시용 객체 대신 ID에 대응하는 실제 전용 작업을 취소합니다. */
    override fun cancel(task: Task): Boolean = find(task.id)?.let { queue.cancel(it) } ?: false

    /** 알림 취소를 전용 엔진의 게시물 묶음 취소로 전달합니다. */
    override fun cancel(taskId: String): Boolean = queue.cancel(taskId)

    /** 원본 화면에서 재개한 작업을 실제 대기열에서 찾습니다. */
    override fun restart(task: Task) { find(task.id)?.let { queue.restart(it) } }

    /** 원본 화면에서 선택한 실제 작업을 제거합니다. */
    override fun remove(task: Task): Boolean = find(task.id)?.let { queue.remove(it) } ?: false

    /** 원본 객체와 전용 객체 사이의 연결은 안정적인 작업 ID만 사용합니다. */
    private fun find(id: String): PluginTask? = queue.getTaskStateMap().keys.find { it.id == id }
}

/** 플러그인 화면과 컨트롤러가 실제 대기열에 접근합니다. */
fun DownloaderV2.pluginQueue(): PluginTaskDownloader =
    (this as? PluginDownloaderV2)?.queue
        ?: error("플러그인 전용 대기열이 연결되지 않았습니다.")
