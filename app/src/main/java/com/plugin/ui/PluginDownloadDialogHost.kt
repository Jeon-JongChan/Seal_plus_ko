package com.plugin.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
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
import com.plugin.compatibility.*
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
            // 메뉴에서 변경한 빠른 쿠키 프로필과 저장 설정을 새 창마다 다시 읽습니다.
            if (!showDialog) preferences = createPluginPreferences()
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
    val originalDownloader: com.junkfood.seal.download.DownloaderV2 = org.koin.compose.koinInject()
    val selectionDownloader = remember(originalDownloader, preferences.selectedFolder) {
        folderAwareDownloader(originalDownloader, preferences.selectedFolder)
    }
    when (val current = selection) {
        is SelectionState.FormatSelection -> if (preferences.selectedFolder == null) FormatPage(state = current,
            onDismissRequest = { viewModel.postAction(Action.Reset) }) else FolderFormatPage(current, selectionDownloader) { viewModel.postAction(Action.Reset) }
        is SelectionState.PlaylistSelection -> PlaylistSelectionPage(state = current, downloader = selectionDownloader,
            onDismissRequest = { viewModel.postAction(Action.Reset) })
        SelectionState.Idle -> {}
    }
}

/** 원본 형식·재생목록 화면이 만든 요청에도 선택 폴더를 붙여 원본 화면 수정 없이 연결합니다. */
internal fun folderAwareDownloader(original: com.junkfood.seal.download.DownloaderV2, folder: DownloadFolder?): com.junkfood.seal.download.DownloaderV2 {
    if (folder == null) return original
    /** 원본이 새로 만든 형식 설정은 보존하고 저장 위치만 요청 당시 선택으로 고정합니다. */
    fun selected(task: com.junkfood.seal.download.Task) = task.copy(preferences = task.preferences.withStoredPluginOptions().copyPluginOptions(selectedFolder = folder))
    return object : com.junkfood.seal.download.DownloaderV2 by original {
        /** URL 작업에 명시적 저장 위치를 전달합니다. */
        override fun enqueue(task: com.junkfood.seal.download.Task) { original.enqueue(selected(task)) }
        /** 형식 분석 결과와 저장 위치를 함께 등록합니다. */
        override fun enqueue(task: com.junkfood.seal.download.Task, state: com.junkfood.seal.download.Task.State) { original.enqueue(selected(task), state) }
        /** 인터페이스 기본 메서드의 위임이 선택 폴더를 우회하지 않도록 같은 경로로 전달합니다. */
        override fun enqueue(taskWithState: com.junkfood.seal.download.TaskFactory.TaskWithState) { enqueue(taskWithState.task, taskWithState.state) }
    }
}

/** 기존 형식 선택 내용과 바텀 시트를 재사용하면서 저장폴더를 유지하는 등록기를 전달합니다. */
@OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
private fun FolderFormatPage(state: SelectionState.FormatSelection, downloader: com.junkfood.seal.download.DownloaderV2, onDismiss: () -> Unit) {
    val sheet = androidx.compose.material.rememberModalBottomSheetState(androidx.compose.material.ModalBottomSheetValue.Hidden, skipHalfExpanded = true)
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { sheet.show() }
    /** 닫기 애니메이션이 끝난 뒤 선택 상태를 초기화합니다. */
    fun close() { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    androidx.activity.compose.BackHandler(onBack = ::close)
    com.junkfood.seal.ui.component.SealModalBottomSheetM2Variant(sheetState = sheet, sheetGesturesEnabled = false) {
        FormatPage(videoInfo = state.info, downloader = downloader, onNavigateBack = ::close)
    }
}
