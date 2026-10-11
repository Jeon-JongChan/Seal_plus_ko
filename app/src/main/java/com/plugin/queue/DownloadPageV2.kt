package com.plugin.queue

import com.plugin.compatibility.refreshCompletedFiles

import com.junkfood.seal.ui.page.downloadv2.*
import com.plugin.queue.PostMediaViewer
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.R
import com.junkfood.seal.download.DownloaderV2
import com.plugin.queue.PluginTaskDownloader
import com.plugin.runtime.pluginQueue
import com.plugin.queue.QueueMedia
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.SmallFloatingActionButton
import com.junkfood.seal.util.DatabaseUtil
import androidx.compose.material3.TextButton
import com.plugin.queue.PluginTask as Task
import com.plugin.queue.PluginTask.DownloadState.Canceled
import com.plugin.queue.PluginTask.DownloadState.Completed
import com.plugin.queue.PluginTask.DownloadState.Error
import com.plugin.queue.PluginTask.DownloadState.FetchingInfo
import com.plugin.queue.PluginTask.DownloadState.Idle
import com.plugin.queue.PluginTask.DownloadState.ReadyWithInfo
import com.plugin.queue.PluginTask.DownloadState.Running
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.LocalFixedColorRoles
import com.junkfood.seal.ui.common.LocalWindowWidthState
import com.junkfood.seal.ui.component.SealModalBottomSheet
import com.junkfood.seal.ui.component.SelectionGroupDefaults
import com.junkfood.seal.ui.component.SelectionGroupItem
import com.junkfood.seal.ui.component.SelectionGroupRow
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.Config
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.FormatPage
import com.junkfood.seal.ui.page.downloadv2.configure.PlaylistSelectionPage
import com.junkfood.seal.ui.page.downloadv2.configure.PreferencesMock
import com.junkfood.seal.ui.svg.DynamicColorImageVectors
import com.junkfood.seal.ui.svg.drawablevectors.download
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.getErrorReport
import com.junkfood.seal.util.makeToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val TAG = "DownloadPageV2"

/** Filter의 대기열 표시와 사용자 동작을 처리합니다. */
enum class Filter {
    All, Downloading, Canceled, Finished, Deleted;

    /** label의 대기열 표시와 사용자 동작을 처리합니다. */
    @Composable
    fun label(): String = stringResource(when (this) {
        All -> R.string.all
        Downloading -> R.string.status_downloading
        Canceled -> R.string.status_canceled
        Finished -> R.string.status_completed
        Deleted -> R.string.loc_deleted
    })

    /** predict의 대기열 표시와 사용자 동작을 처리합니다. */
    fun predict(entry: Pair<Task, Task.State>): Boolean = when (this) {
        All -> true
        Downloading -> entry.second.downloadState.let { it is FetchingInfo || it == Idle || it == ReadyWithInfo || it is Running }
        Canceled -> entry.second.downloadState.let { it is Error || it is Task.DownloadState.Canceled }
        Finished -> (entry.second.downloadState as? Completed)?.missing == false
        Deleted -> (entry.second.downloadState as? Completed)?.missing == true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/** EnhancedDownloadPageV2의 대기열 표시와 사용자 동작을 처리합니다. */
fun EnhancedDownloadPageV2(
    modifier: Modifier = Modifier,
    onMenuOpen: (() -> Unit) = {},
    dialogViewModel: com.plugin.runtime.PluginDownloadDialogViewModel,
    downloader: DownloaderV2 = koinInject(),
) {
    val queue = downloader.pluginQueue()
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var openedPost by remember { mutableStateOf<Pair<List<String>, String?>?>(null) }
    openedPost?.let { (paths, initial) -> PostMediaViewer(paths, initial) { openedPost = null } }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { queue.refreshCompletedFiles() }
            delay(10_000)
        }
    }

    DownloadPageImplV2(
        modifier = modifier,
        taskDownloadStateMap = queue.getTaskStateMap(),
        downloadCallback = {
            view.slightHapticFeedback()
            dialogViewModel.postAction(Action.ShowSheet())
        },
        onMenuOpen = onMenuOpen,
    ) { task, action ->
        view.slightHapticFeedback()
        when (action) {
            UiAction.Cancel -> QueueMedia.members(task, queue.getTaskStateMap().toMap()).forEach { queue.cancel(it) }
            UiAction.Delete, UiAction.DeleteFiles -> {
                val snapshot = queue.getTaskStateMap().toMap()
                val deleted = (snapshot[task]?.downloadState as? Completed)?.missing == true
                scope.launch(Dispatchers.IO) {
                    QueueMedia.members(task, snapshot).forEach { member ->
                        val completed = snapshot[member]?.downloadState as? Completed
                        if (!deleted || completed?.missing == true) {
                            queue.cancel(member)
                            if (action == UiAction.DeleteFiles && completed != null && !completed.missing) {
                                val results = completed.filePaths.map { path ->
                                    com.plugin.compatibility.CompatibilityFiles.deleteFile(path).onSuccess { com.plugin.compatibility.PluginDatabaseUtil.deleteInfoByPath(path) }
                                }
                                if (results.any { it.isFailure }) {
                                    context.makeToast(R.string.loc_file_delete_failed)
                                    return@forEach
                                }
                            }
                            if (completed?.missing == true) completed.filePaths.forEach { com.plugin.compatibility.PluginDatabaseUtil.deleteInfoByPath(it) }
                            queue.remove(member)
                        }
                    }
                }
            }
            UiAction.Resume -> QueueMedia.members(task, queue.getTaskStateMap().toMap()).filter {
                queue.getTaskStateMap()[it]?.downloadState is Task.DownloadState.Restartable
            }.forEach { queue.restart(it) }
            is UiAction.CopyErrorReport -> {
                clipboardManager.setText(
                    AnnotatedString(getErrorReport(action.throwable, task.url))
                )
                context.makeToast(R.string.error_copied)
            }
            UiAction.CopyVideoURL -> {
                clipboardManager.setText(AnnotatedString(task.url))
                context.makeToast(R.string.link_copied)
            }
            is UiAction.OpenFile -> {
                val paths = QueueMedia.completedPages(task, queue.getTaskStateMap().toMap())
                if (task.preferences.extractAudio) FileUtil.openFile(action.filePath ?: paths.firstOrNull().orEmpty()) { context.makeToast(R.string.file_unavailable) }
                else if (paths.isNotEmpty()) openedPost = paths to action.filePath
                else context.makeToast(R.string.file_unavailable)
            }
            is UiAction.OpenThumbnailURL -> {
                uriHandler.openUri(action.url)
            }
            is UiAction.OpenVideoURL -> {
                uriHandler.openUri(action.url)
            }
            is UiAction.ShareFile -> {
                val shareTitle = context.getString(R.string.share)
                FileUtil.createIntentForSharingFile(action.filePath)?.let {
                    context.startActivity(Intent.createChooser(it, shareTitle))
                }
            }
        }
    }

}

@Composable
/** PaddingValues의 대기열 표시와 사용자 동작을 처리합니다. */
private operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        top = calculateTopPadding() + other.calculateTopPadding(),
        bottom = calculateBottomPadding() + other.calculateBottomPadding(),
        start =
            calculateStartPadding(layoutDirection) + other.calculateStartPadding(layoutDirection),
        end = calculateEndPadding(layoutDirection) + other.calculateEndPadding(layoutDirection),
    )
}

private const val HeaderSpacingDp = 28

/** DownloadPageImplV2의 대기열 표시와 사용자 동작을 처리합니다. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadPageImplV2(
    modifier: Modifier = Modifier,
    taskDownloadStateMap: SnapshotStateMap<Task, Task.State>,
    downloadCallback: () -> Unit = {},
    onMenuOpen: (() -> Unit) = {},
    onActionPost: (Task, UiAction) -> Unit,
) {
    var activeFilter by remember { mutableStateOf(Filter.All) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var selecting by remember { mutableStateOf(false) }
    var deletionTargets by remember { mutableStateOf<List<Task>>(emptyList()) }
    var deleteFiles by remember { mutableStateOf(false) }
    val filteredMap by
        remember(activeFilter) {
            derivedStateOf { QueueMedia.grouped(taskDownloadStateMap.toMap()).filter { activeFilter.predict(it.toPair()) } }
        }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var selectedTask by remember { mutableStateOf<Task?>(null) }
    val view = LocalView.current

    /** showActionSheet의 대기열 표시와 사용자 동작을 처리합니다. */
    fun showActionSheet(task: Task) {
        view.slightHapticFeedback()
        scope.launch {
            selectedTask = task
            delay(50)
            sheetState.show()
        }
    }

    /** openItem의 대기열 표시와 사용자 동작을 처리합니다. */
    fun openItem(task: Task) {
        if (selecting) {
            if (task.id in selectedIds) selectedIds.remove(task.id) else selectedIds.add(task.id)
        } else {
            val pages = QueueMedia.completedPages(task, taskDownloadStateMap.toMap())
            if (pages.isNotEmpty()) onActionPost(task, UiAction.OpenFile(pages.first()))
            else showActionSheet(task)
        }
    }


    LaunchedEffect(selectedTask, taskDownloadStateMap.size) {
        if (!taskDownloadStateMap.contains(selectedTask)) {
            selectedTask = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        containerColor = MaterialTheme.colorScheme.surface,
        floatingActionButton = { FABs(modifier = Modifier, downloadCallback = downloadCallback) },
    ) { windowInsetsPadding ->
        val lazyListState = rememberLazyGridState()
        val windowWidthSizeClass = LocalWindowWidthState.current
        val spacerHeight =
            with(LocalDensity.current) {
                if (windowWidthSizeClass != WindowWidthSizeClass.Compact) 0f
                else HeaderSpacingDp.dp.toPx()
            }
        var headerOffset by remember { mutableFloatStateOf(spacerHeight) }
        var isGridView by rememberSaveable { mutableStateOf(true) }

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .then(
                        if (windowWidthSizeClass != WindowWidthSizeClass.Compact) Modifier
                        else
                            Modifier.nestedScroll(
                                connection =
                                    TopBarNestedScrollConnection(
                                        maxOffset = spacerHeight,
                                        flingAnimationSpec = rememberSplineBasedDecay(),
                                        offset = { headerOffset },
                                        onOffsetUpdate = { headerOffset = it },
                                    )
                            )
                    )
        ) {
            CompositionLocalProvider(LocalOverscrollFactory provides null) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(with(LocalDensity.current) { headerOffset.toDp() }))
                    Header(onMenuOpen = onMenuOpen, modifier = Modifier.padding(horizontal = 16.dp))
                    SelectionGroupRow(
                        modifier =
                            Modifier.horizontalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp)
                    ) {
                        Filter.entries.forEach { filter ->
                            SelectionGroupItem(
                                colors =
                                    SelectionGroupDefaults.colors(
                                        activeContainerColor =
                                            LocalFixedColorRoles.current.tertiaryFixed,
                                        activeContentColor =
                                            LocalFixedColorRoles.current.onTertiaryFixed,
                                    ),
                                selected = activeFilter == filter,
                                onClick = {
                                    if (activeFilter == filter) {
                                        scope.launch { lazyListState.animateScrollToItem(0) }
                                        scope.launch {
                                            val initialValue = headerOffset
                                            AnimationState(initialValue = initialValue).animateTo(
                                                spacerHeight
                                            ) {
                                                headerOffset = value
                                            }
                                        }
                                    } else {
                                        activeFilter = filter
                                    }
                                },
                            ) {
                                Text(filter.label())
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { selecting = !selecting; selectedIds.clear() }) {
                            Text(stringResource(if (selecting) R.string.cancel else R.string.loc_queue_select))
                        }
                        if (selecting) {
                            TextButton(onClick = { selectedIds.clear(); selectedIds.addAll(filteredMap.keys.map { it.id }) }) {
                                Text(stringResource(R.string.select_all))
                            }
                            TextButton(enabled = selectedIds.isNotEmpty(), onClick = {
                                deletionTargets = filteredMap.keys.filter { it.id in selectedIds }
                            }) { Text(stringResource(R.string.loc_queue_delete_selected)) }
                        } else TextButton(enabled = taskDownloadStateMap.isNotEmpty(), onClick = {
                            deletionTargets = QueueMedia.grouped(taskDownloadStateMap.toMap()).keys.toList()
                        }) { Text(stringResource(R.string.loc_queue_delete_all)) }
                    }
                    if (activeFilter == Filter.Deleted && filteredMap.isNotEmpty()) {
                        TextButton(onClick = { filteredMap.keys.toList().forEach { onActionPost(it, UiAction.Delete) } }) {
                            Text(stringResource(R.string.loc_clean_deleted))
                        }
                    }
                    if (headerOffset <= 0.1f && spacerHeight > 0f) {
                        HorizontalDivider(thickness = Dp.Hairline)
                    }
                }

                LazyVerticalGrid(
                    modifier = Modifier,
                    state = lazyListState,
                    columns = GridCells.Adaptive(240.dp),
                    contentPadding =
                        windowInsetsPadding +
                            PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    if (filteredMap.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            val videoCount =
                                filteredMap.entries.sumOf { if (it.key.preferences.extractAudio) 0 else it.value.viewState.videoCount }
                            SubHeader(
                                modifier = Modifier,
                                videoCount = videoCount,
                                imageCount = filteredMap.values.sumOf { it.viewState.imageCount },
                                audioCount = filteredMap.entries.sumOf { if (it.key.preferences.extractAudio) it.value.viewState.mediaCount else 0 },
                                isGridView = isGridView,
                                onToggleView = { isGridView = !isGridView },
                                onShowMenu = { selecting = !selecting; selectedIds.clear() },
                            )
                        }
                    }

                    if (isGridView) {
                        items(
                            items =
                                filteredMap.toList().sortedBy { (_, state) -> state.downloadState },
                            key = { (task, _) -> task.id },
                        ) { (task, state) ->
                            with(state.viewState) {
                                VideoCardV2(
                                    selected = selecting && task.id in selectedIds,
                                    modifier = Modifier.padding(bottom = 20.dp).combinedClickable(onClick = { openItem(task) }, onLongClick = { selecting = true; if (task.id !in selectedIds) selectedIds.add(task.id) }),
                                    viewState = if (isImage && state.downloadState is Completed)
                                        copy(thumbnailUrl = FileUtil.createIntentForOpeningFile(state.downloadState.filePath)?.data?.toString() ?: thumbnailUrl)
                                        else this,
                                    actionButton = {
                                        if (selecting) Checkbox(task.id in selectedIds, onCheckedChange = { openItem(task) })
                                        else ActionButton(
                                            modifier = Modifier,
                                            downloadState = state.downloadState,
                                            isImage = state.viewState.isImage,
                                        ) {
                                            onActionPost(task, it)
                                        }
                                    },
                                    stateIndicator = {
                                        Column {
                                            PostMediaDetails(state.viewState, overlay = true,
                                                missingPages = QueueMedia.missingPages(task, taskDownloadStateMap.toMap()))
                                            CardStateIndicator(modifier = Modifier, downloadState = state.downloadState)
                                            if (QueueMedia.members(task, taskDownloadStateMap.toMap()).any { taskDownloadStateMap[it]?.downloadState is Task.DownloadState.Restartable })
                                                TextButton(onClick = { onActionPost(task, UiAction.Resume) }) { Text(stringResource(R.string.loc_retry_failed_pages)) }
                                        }
                                    },
                                    onButtonClick = { if (selecting) openItem(task) else showActionSheet(task) },
                                )
                            }
                        }
                    } else {
                        items(
                            items =
                                filteredMap.toList().sortedBy { (_, state) -> state.downloadState },
                            key = { (task, _) -> task.id },
                            span = { GridItemSpan(maxLineSpan) },
                        ) { (task, state) ->
                            VideoListItem(
                                selected = selecting && task.id in selectedIds,
                                modifier = Modifier.padding(bottom = 16.dp).combinedClickable(onClick = { openItem(task) }, onLongClick = { selecting = true; if (task.id !in selectedIds) selectedIds.add(task.id) }),
                                viewState = state.viewState,
                                stateIndicator = {
                                    Column {
                                        if (selecting) Checkbox(task.id in selectedIds, onCheckedChange = { openItem(task) })
                                        PostMediaDetails(state.viewState,
                                            missingPages = QueueMedia.missingPages(task, taskDownloadStateMap.toMap()))
                                        ListItemStateText(modifier = Modifier.padding(top = 3.dp), downloadState = state.downloadState)
                                        if (QueueMedia.members(task, taskDownloadStateMap.toMap()).any { taskDownloadStateMap[it]?.downloadState is Task.DownloadState.Restartable })
                                            TextButton(onClick = { onActionPost(task, UiAction.Resume) }) { Text(stringResource(R.string.loc_retry_failed_pages)) }
                                    }
                                },
                                onButtonClick = { if (selecting) openItem(task) else showActionSheet(task) },
                            )
                        }
                    }
                }
            }
        }
        if (filteredMap.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize()) {
                DownloadQueuePlaceholder(
                    modifier =
                        Modifier.fillMaxHeight(0.4f).widthIn(max = 360.dp).align(Alignment.Center)
                )
            }
        }
    }
    if (deletionTargets.isNotEmpty()) AlertDialog(
        onDismissRequest = { deletionTargets = emptyList() },
        title = { Text(stringResource(R.string.loc_queue_delete_selected)) },
        text = { Column {
            Text(stringResource(R.string.loc_queue_delete_hint))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(deleteFiles, onCheckedChange = { deleteFiles = it })
                Text(stringResource(R.string.loc_delete_files_and_history))
            }
        } },
        confirmButton = { Button(onClick = {
            deletionTargets.forEach { onActionPost(it, if (deleteFiles) UiAction.DeleteFiles else UiAction.Delete) }
            deletionTargets = emptyList(); selectedIds.clear(); selecting = false
        }) { Text(stringResource(R.string.loc_queue_delete_selected)) } },
        dismissButton = { TextButton(onClick = { deletionTargets = emptyList() }) { Text(stringResource(R.string.cancel)) } },
    )
    if (selectedTask != null) {
        val task = selectedTask!!
        val (downloadState, _, viewState) = filteredMap[task] ?: return
        SealModalBottomSheet(
            sheetState = sheetState,
            contentPadding = PaddingValues(),
            onDismissRequest = {
                scope.launch { sheetState.hide() }.invokeOnCompletion { selectedTask = null }
            },
        ) {
            SheetContent(
                task = task,
                downloadState = downloadState,
                viewState = viewState,
                onDismissRequest = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { selectedTask = null }
                },
                onActionPost = onActionPost,
            )
        }
    }
}

@Composable
/** Header의 대기열 표시와 사용자 동작을 처리합니다. */
fun Header(modifier: Modifier = Modifier, onMenuOpen: () -> Unit = {}) {
    val windowWidthSizeClass = LocalWindowWidthState.current
    when (windowWidthSizeClass) {
        WindowWidthSizeClass.Expanded -> {
            HeaderExpanded(modifier = modifier)
        }
        else -> {
            HeaderCompact(modifier = modifier, onMenuOpen = onMenuOpen)
        }
    }
}

@Composable
/** HeaderCompact의 대기열 표시와 사용자 동작을 처리합니다. */
private fun HeaderCompact(modifier: Modifier = Modifier, onMenuOpen: () -> Unit) {

    Row(modifier = modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenuOpen, modifier = Modifier) {
            Icon(
                imageVector = Icons.Outlined.Menu,
                contentDescription = stringResource(R.string.show_navigation_drawer),
                modifier = Modifier,
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            stringResource(R.string.download_queue),
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
        )
    }
}

@Composable
/** HeaderExpanded의 대기열 표시와 사용자 동작을 처리합니다. */
private fun HeaderExpanded(modifier: Modifier = Modifier) {
    Row(modifier = modifier.height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            stringResource(R.string.download_queue),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium),
        )
    }
    Spacer(Modifier.height(4.dp))
}

/** FABs의 대기열 표시와 사용자 동작을 처리합니다. */
@Composable
fun FABs(modifier: Modifier = Modifier, downloadCallback: () -> Unit = {}) {
    val expanded = LocalWindowWidthState.current != WindowWidthSizeClass.Compact
    Column(modifier = modifier.padding(6.dp), horizontalAlignment = Alignment.End) {
        FloatingActionButton(
            onClick = downloadCallback,
            content = {
                if (expanded) {
                    Row(
                        modifier = Modifier.widthIn(min = 80.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.download))
                    }
                } else {
                    Icon(
                        Icons.Outlined.FileDownload,
                        contentDescription = stringResource(R.string.download),
                    )
                }
            },
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
@Preview
/** DownloadQueuePlaceholder의 대기열 표시와 사용자 동작을 처리합니다. */
private fun DownloadQueuePlaceholder(modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        ConstraintLayout {
            val (image, text) = createRefs()
            val showImage =
                with(LocalDensity.current) {
                    this@BoxWithConstraints.constraints.maxHeight >= 240.dp.toPx()
                }
            if (showImage) {
                Image(
                    painter = rememberVectorPainter(image = DynamicColorImageVectors.download()),
                    contentDescription = null,
                    modifier =
                        Modifier.fillMaxHeight(0.5f).widthIn(max = 240.dp).constrainAs(image) {
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                        },
                )
            } else {
                Spacer(Modifier.height(72.dp).constrainAs(image) { top.linkTo(parent.top) })
            }
            Column(
                modifier = Modifier.constrainAs(text) { top.linkTo(image.bottom, margin = 36.dp) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.you_ll_find_your_downloads_here),
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.download_hint),
                    modifier = Modifier.padding(top = 4.dp).padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
/** SubHeader의 대기열 표시와 사용자 동작을 처리합니다. */
fun SubHeader(
    modifier: Modifier = Modifier,
    containerColor: Color =
        MaterialTheme.colorScheme.run {
            if (LocalDarkTheme.current.isDarkTheme()) surfaceContainer else surfaceContainerLowest
        },
    videoCount: Int = 0,
    audioCount: Int = 0,
    imageCount: Int = 0,
    isGridView: Boolean = true,
    onToggleView: () -> Unit,
    onShowMenu: () -> Unit,
) {
    val text = buildString {
        if (videoCount > 0) {
            append(pluralStringResource(R.plurals.video_count, videoCount).format(videoCount))
            if (audioCount > 0) {
                append(", ")
            }
        }
        if (audioCount > 0) {
            append(pluralStringResource(R.plurals.audio_count, audioCount).format(audioCount))
        }
        if (imageCount > 0) {
            if (isNotEmpty()) append(", ")
            append(stringResource(R.string.loc_image_count, imageCount))
        }
    }

    Row(
        modifier = modifier.padding(top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        FilledIconButton(
            onClick = onToggleView,
            modifier = Modifier.clearAndSetSemantics {}.size(32.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor),
        ) {
            Icon(
                imageVector =
                    if (isGridView) Icons.AutoMirrored.Outlined.List else Icons.Outlined.GridView,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }

        Spacer(Modifier.width(4.dp))

        FilledIconButton(
            onClick = onShowMenu,
            modifier = Modifier.clearAndSetSemantics {}.size(32.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor),
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** DownloadPageV2Test의 대기열 표시와 사용자 동작을 처리합니다. */
internal class DownloadPageV2Test {
    private val mockDownloader =
        /** 화면 상태의 대기열 표시와 사용자 동작을 처리합니다. */
        object : PluginTaskDownloader {
            private val map = mutableStateMapOf<Task, Task.State>()

            init {
                val viewState =
                    Task.ViewState(title = "Sample title", uploader = "dummy video uploader")
                val list =
                    listOf(
                        Task.State(Idle, null, viewState),
                        Task.State(Canceled(Task.RestartableAction.Download), null, viewState),
                        Task.State(Completed(null), null, viewState),
                    )
                map.run {
                    repeat(9) {
                        put(Task(url = "$it", preferences = PreferencesMock), list[it % 3])
                    }
                }
                val scope = CoroutineScope(SupervisorJob())

                scope.launch(Dispatchers.Default) {
                    while (true) {
                        delay(1000)
                        val newEntries =
                            map.toMap().map { (task, state) ->
                                val newDownloadState =
                                    when (state.downloadState) {
                                        is Canceled -> Idle
                                        is Completed -> Idle
                                        is Error -> Idle
                                        is FetchingInfo -> ReadyWithInfo
                                        Idle -> FetchingInfo(Job(), task.id)
                                        ReadyWithInfo -> Running(Job(), task.id)
                                        is Running -> {
                                            val preState: Running = state.downloadState
                                            if (preState.progress >= 1f) Completed(null)
                                            else preState.copy(progress = preState.progress + 0.1f)
                                        }
                                    }
                                task to state.copy(downloadState = newDownloadState)
                            }
                        Snapshot.withMutableSnapshot {
                            newEntries.forEach { (task, state) ->
                                delay(100)
                                map[task] = state
                            }
                        }
                    }
                }
            }

            /** getTaskStateMap의 대기열 표시와 사용자 동작을 처리합니다. */
            override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
                return map
            }

            /** cancel의 대기열 표시와 사용자 동작을 처리합니다. */
            override fun cancel(task: Task): Boolean {
                return false
            }

            /** restart의 대기열 표시와 사용자 동작을 처리합니다. */
            override fun restart(task: Task) {}

            /** enqueue의 대기열 표시와 사용자 동작을 처리합니다. */
            override fun enqueue(task: Task) {}

            /** enqueue의 대기열 표시와 사용자 동작을 처리합니다. */
            override fun enqueue(task: Task, state: Task.State) {}

            /** remove의 대기열 표시와 사용자 동작을 처리합니다. */
            override fun remove(task: Task): Boolean {
                return true
            }
        }

    @Composable
    @Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
    @Preview(name = "Tablet", device = "spec:width=600dp,height=800dp,dpi=240")
    /** Preview의 대기열 표시와 사용자 동작을 처리합니다. */
    private fun Preview() {

        val queue: PluginTaskDownloader = mockDownloader
        SealTheme {
            Column() {
                DownloadPageImplV2(
                    taskDownloadStateMap = queue.getTaskStateMap(),
                    onActionPost = { task, state -> },
                    onMenuOpen = {},
                )
            }
        }
    }
}
