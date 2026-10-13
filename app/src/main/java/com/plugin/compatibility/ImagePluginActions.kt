package com.plugin.compatibility

import androidx.compose.runtime.Composable
import com.plugin.PluginState
import com.plugin.featurePlugins

/** 뷰어가 특정 선택 플러그인에 의존하지 않고 활성 이미지 동작을 표시합니다. */
@Composable
fun ImagePluginActions(path: String, paths: List<String> = listOf(path), onSaved: (String, String, Boolean) -> Unit) {
    if (PluginMediaMetadataStore.isProcessingExcluded(path)) return
    androidx.compose.foundation.layout.Row {
        com.plugin.PluginFactory.enabled().forEach { plugin ->
            plugin.ImageAction(path) { output, replace -> onSaved(path, output, replace) }
            plugin.PostImageAction(paths, onSaved)
        }
    }
}

/** 공통 뷰어는 등록된 영상 전략을 호출하며 구체 플러그인을 직접 참조하지 않습니다. */
@Composable
fun VideoPluginActions(path: String, onSaved: (String, Boolean) -> Unit) {
    if (PluginMediaMetadataStore.isProcessingExcluded(path)) return
    androidx.compose.foundation.layout.Row {
        com.plugin.PluginFactory.enabled().forEach { it.VideoAction(path, onSaved) }
    }
}
