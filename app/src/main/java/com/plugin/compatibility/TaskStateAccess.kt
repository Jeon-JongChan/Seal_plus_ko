package com.plugin.compatibility

import com.plugin.queue.PluginTask

import kotlinx.coroutines.CancellationException

/** 삭제된 작업의 늦은 콜백은 오류 알림 대신 정상 취소로 종료합니다. */
internal fun Map<PluginTask, PluginTask.State>.requireTaskState(task: PluginTask): PluginTask.State =
    this[task] ?: throw CancellationException("대기열에서 삭제된 작업입니다.")

/** 삭제와 상태 갱신을 함께 잠가 늦은 콜백이 삭제한 작업을 다시 등록하지 않게 합니다. */
internal fun MutableMap<PluginTask, PluginTask.State>.updateTaskState(
    task: PluginTask, update: (PluginTask.State) -> PluginTask.State,
): Boolean = synchronized(this) {
    // ponytail: 대기열별 짧은 상태 갱신만 잠급니다. 높은 동시성이 필요해지면 작업별 잠금으로 변경합니다.
    val previous = this[task] ?: return@synchronized false
    this[task] = update(previous)
    true
}

/** 콜백의 상태 갱신과 같은 잠금으로 작업을 제거합니다. */
internal fun MutableMap<PluginTask, PluginTask.State>.removeTaskState(task: PluginTask): PluginTask.State? =
    synchronized(this) { remove(task) }
