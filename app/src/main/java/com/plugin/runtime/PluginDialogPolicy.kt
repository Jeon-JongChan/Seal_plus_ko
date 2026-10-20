package com.plugin.runtime

import com.plugin.PluginId
import com.plugin.PluginState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.InstagramSelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.InspectInstagramAction

/** 활성 입력 기능 또는 이미 열린 확장 상태만 확장 UI에 전달합니다. */
fun usePluginDialog(
    state: Any,
    enabled: Set<PluginId> = PluginId.entries.filter(PluginState::isEnabled).toSet(),
): Boolean =
    listOf(PluginId.SOCIAL, PluginId.MEDIA, PluginId.LIBRARY, PluginId.INTERACTION, PluginId.FOLDERS).any { it in enabled } ||
        state is InstagramSelectionState || state is DownloadDialogViewModel.SheetState.Error
