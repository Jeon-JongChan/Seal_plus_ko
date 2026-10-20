package com.plugin.compatibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 두 목록에서 복제·교체 결과와 승인 대기 중인 원본을 같은 기준으로 찾습니다. */
object AiMediaFilter {
    val pending = MutableStateFlow<Set<String>>(emptySet())

    /** 처리 시작부터 결과 승인·취소까지 원본을 필터에 포함합니다. */
    fun begin(paths: Collection<String>) { pending.update { it + paths } }

    /** 취소·오류·승인 완료된 원본만 대기 목록에서 해제합니다. */
    fun end(paths: Collection<String>) { pending.update { it - paths.toSet() } }

    /** 카드의 페이지 중 하나라도 해당하면 게시물 전체를 표시합니다. */
    fun matches(paths: Collection<String>, waiting: Set<String>): Boolean = paths.any { path ->
        path in waiting || PluginMediaMetadataStore.isAiDuplicate(path) ||
            PluginMediaMetadataStore.get(path)?.let { it.aiReplaced || it.sourceQuality != null } == true
    }
}
