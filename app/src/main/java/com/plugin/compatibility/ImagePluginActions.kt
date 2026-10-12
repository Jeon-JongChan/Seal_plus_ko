package com.plugin.compatibility

import androidx.compose.runtime.Composable
import com.plugin.PluginState
import com.plugin.featurePlugins

/** 뷰어가 특정 선택 플러그인에 의존하지 않고 활성 이미지 동작을 표시합니다. */
@Composable
fun ImagePluginActions(path: String, onSaved: (String, Boolean) -> Unit) {
    featurePlugins.filter { PluginState.isEnabled(it.id) }.forEach { it.ImageAction(path, onSaved) }
}
