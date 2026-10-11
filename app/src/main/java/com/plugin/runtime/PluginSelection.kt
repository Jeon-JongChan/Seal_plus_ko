package com.plugin.runtime

import androidx.compose.runtime.Composable
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.InstagramSelectionState

import com.plugin.compatibility.listProcessing

/** 요청을 생성한 플러그인의 선택 화면을 연결합니다. */
@Composable
fun PluginSelection(
    state: InstagramSelectionState,
    onAction: (Any) -> Unit,
) {
    if (state.preferences.listProcessing) com.plugin.listdownload.ListSelectionPage(state, onAction)
    else com.plugin.instagram.InstagramSelectionPage(state, onAction)
}
