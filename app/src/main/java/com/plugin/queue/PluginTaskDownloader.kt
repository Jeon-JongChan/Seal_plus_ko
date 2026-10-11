package com.plugin.queue

import androidx.compose.runtime.snapshots.SnapshotStateMap

/** 플러그인 엔진과 미리보기가 사용하는 전용 작업 API입니다. */
interface PluginTaskDownloader {
    /** 전용 작업과 상태를 보관하는 대기열을 반환합니다. */
    fun getTaskStateMap(): SnapshotStateMap<PluginTask, PluginTask.State>

    /** 작업을 취소합니다. */
    fun cancel(task: PluginTask): Boolean

    /** 알림에서 전달된 작업 ID를 취소합니다. */
    fun cancel(taskId: String): Boolean =
        getTaskStateMap().keys.find { it.id == taskId }?.let { cancel(it) } ?: false

    /** 취소 또는 실패한 작업을 다시 시작합니다. */
    fun restart(task: PluginTask)

    /** 정보 조회가 필요한 작업을 등록합니다. */
    fun enqueue(task: PluginTask)

    /** 조회된 정보와 함께 작업을 등록합니다. */
    fun enqueue(task: PluginTask, state: PluginTask.State)

    /** 호출자가 중단한 작업을 대기열에서 제거합니다. */
    fun remove(task: PluginTask): Boolean
}
