package com.plugin.runtime

import com.plugin.queue.PluginTask as Task
import com.plugin.compatibility.*

/** 실행 중 스위치 변경에도 작업의 저장된 처리 경로를 유지합니다. */
object PluginExecutionPolicy {
    /** 소셜 작업 또는 등록 시 켜진 대기열·미디어 처리는 확장 엔진이 담당합니다. */
    fun usesEnhancedEngine(task: Task): Boolean = task.type is Task.TypeInfo.SocialMedia ||
        task.preferences.cookies || task.preferences.selectedFolder != null || task.preferences.listProcessing || task.preferences.socialProcessing || task.preferences.queueEnhancements || task.preferences.mediaProcessing || task.preferences.reuseExisting
}
