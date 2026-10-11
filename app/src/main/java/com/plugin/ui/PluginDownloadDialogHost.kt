package com.plugin.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.ui.page.downloadv2.configure.Config
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetValue
import com.junkfood.seal.ui.page.downloadv2.configure.FormatPage
import com.junkfood.seal.ui.page.downloadv2.configure.PlaylistSelectionPage
import com.plugin.PluginId
import com.plugin.PluginState
import com.plugin.compatibility.createPluginPreferences
import com.plugin.compatibility.pluginPreferencesState
import com.plugin.runtime.PluginDownloadDialog
import com.plugin.runtime.PluginDownloadDialogViewModel
import com.plugin.runtime.usePluginDialog

/** 앱 내부와 공유 입력에서 같은 설정·확장 선택 화면을 사용합니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginDownloadDialogHost(viewModel: PluginDownloadDialogViewModel, onFinished: () -> Unit = {}) {
    val sheetValue by viewModel.sheetValueFlow.collectAsStateWithLifecycle()
    val state by viewModel.sheetStateFlow.collectAsStateWithLifecycle()
    val selection by viewModel.selectionStateFlow.collectAsStateWithLifecycle()
    var preferences by remember(PluginId.entries.map(PluginState::isEnabled)) {
        pluginPreferencesState(createPluginPreferences())
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showDialog by remember { mutableStateOf(false) }
    val latestFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(sheetValue, selection) {
        if (sheetValue == SheetValue.Expanded) {
            showDialog = true
        } else {
            sheetState.hide()
            showDialog = false
            if (selection == SelectionState.Idle) latestFinished()
        }
    }
    if (showDialog) {
        // 모든 확장을 끄면 기본 UI를 유지하고 활성 기능이 있을 때만 확장 창을 사용합니다.
        if (usePluginDialog(state)) {
            PluginDownloadDialog(config = Config(), sheetState = sheetState, state = state,
                preferences = preferences, onPreferencesUpdate = { preferences = it },
                onActionPost = viewModel::postAction)
        } else {
            DownloadDialog(config = Config(), sheetState = sheetState, state = state as SheetState,
                preferences = preferences, onPreferencesUpdate = { preferences = it },
                onActionPost = viewModel::postAction)
        }
    }
    when (val current = selection) {
        is SelectionState.FormatSelection -> FormatPage(state = current,
            onDismissRequest = { viewModel.postAction(Action.Reset) })
        is SelectionState.PlaylistSelection -> PlaylistSelectionPage(state = current,
            onDismissRequest = { viewModel.postAction(Action.Reset) })
        SelectionState.Idle -> {}
    }
}
