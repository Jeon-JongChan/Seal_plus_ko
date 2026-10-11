package com.plugin.library

import com.junkfood.seal.ui.page.videolist.*
import com.plugin.compatibility.*
import com.plugin.library.MediaFileManagerDialog
import com.plugin.library.PostMediaViewer
import com.plugin.library.PostMediaDetails
import com.plugin.queue.PluginTask as Task
import com.plugin.compatibility.MediaRules
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.database.backup.BackupUtil
import com.junkfood.seal.database.backup.BackupUtil.BackupDestination.Clipboard
import com.junkfood.seal.database.backup.BackupUtil.BackupDestination.File
import com.junkfood.seal.database.backup.BackupUtil.toJsonString
import com.junkfood.seal.database.backup.BackupUtil.toURLListString
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.common.LocalWindowWidthState
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.CheckBoxItem
import com.junkfood.seal.ui.component.ConfirmButton
import com.junkfood.seal.ui.component.DismissButton
import com.plugin.library.MediaListItem
import com.junkfood.seal.ui.component.SealDialog
import com.junkfood.seal.ui.component.SealSearchBar
import com.junkfood.seal.ui.component.VideoFilterChip
import com.junkfood.seal.ui.svg.DynamicColorImageVectors
import com.junkfood.seal.ui.svg.drawablevectors.videoSteaming
import com.junkfood.seal.util.AUDIO_REGEX
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.toFileSizeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/** filterByType에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun DownloadedVideoInfo.filterByType(
    videoFilter: Boolean = false,
    audioFilter: Boolean = true,
): Boolean {
    return if (!(videoFilter || audioFilter)) true
    else if (audioFilter) this.videoPath.contains(Regex(AUDIO_REGEX))
    else !this.videoPath.contains(Regex(AUDIO_REGEX))
}

/** filterSort에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun DownloadedVideoInfo.filterSort(
    mediaFilter: Int,
    folderFilter: String?,
): Boolean {
    return (mediaFilter == 0 || MediaRules.mediaKind(videoPath, quality?.codec.orEmpty()) == mediaFilter) &&
        (folderFilter == null || MediaRules.parentFolder(videoPath) == folderFilter)
}

/** filterByExtractor에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun DownloadedVideoInfo.filterByExtractor(extractor: String?): Boolean {
    return extractor.isNullOrEmpty() || (this.extractor == extractor)
}

private const val TAG = "VideoListPage"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/** EnhancedVideoListPage에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun EnhancedVideoListPage(viewModel: VideoListViewModel = koinViewModel(), onNavigateBack: () -> Unit) {
    var showFileManager by remember { mutableStateOf(false) }
    if (showFileManager) MediaFileManagerDialog { showFileManager = false }
    val viewState by viewModel.stateFlow.collectAsStateWithLifecycle()
    var mediaFilter by remember { mutableIntStateOf(0) }
    var folderFilter by remember { mutableStateOf<String?>(null) }
    val fullVideoList by viewModel.videoListFlow.collectAsStateWithLifecycle(emptyList())
    val searchedVideoList by
        viewModel.searchedVideoListFlow.collectAsStateWithLifecycle(emptyList())

    val videoList = if (viewState.isSearching) searchedVideoList else fullVideoList
    val posts = videoList.filter { it.filterSort(mediaFilter, folderFilter) }.groupBy {
        if (it.extractor.lowercase() in setOf("instagram", "tiktok")) MediaRules.identity(it.videoUrl.substringBefore('#'))
        else it.videoPath
    }.values.map { it.sortedBy { page -> page.postPageIndex } }
    var openedPost by remember { mutableStateOf<Pair<List<String>, String?>?>(null) }
    openedPost?.let { (paths, initial) -> PostMediaViewer(paths, initial) { openedPost = null } }
    val filterSet by viewModel.filterSetFlow.collectAsState(mutableSetOf())

    val scrollBehavior =
        if (fullVideoList.isNotEmpty())
            TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
                rememberTopAppBarState(),
                canScroll = { true },
            )
        else TopAppBarDefaults.pinnedScrollBehavior()

    val scope = rememberCoroutineScope()
    val softKeyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val fileSizeMap by
        viewModel.fileSizeMapFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    val sheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)
    val hostState = remember { SnackbarHostState() }

    var currentVideoInfo by remember { mutableStateOf(DownloadedVideoInfo()) }

    var isSelectEnabled by remember { mutableStateOf(false) }
    var showRemoveMultipleItemsDialog by remember { mutableStateOf(false) }

    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()

    @Composable
    /** FilterChips에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
    fun FilterChips(modifier: Modifier = Modifier) {
        Column(modifier) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(0 to R.string.all, 1 to R.string.video, 2 to R.string.loc_media_images,
                    3 to R.string.audio).forEach { (kind, title) ->
                    VideoFilterChip(selected = mediaFilter == kind,
                        onClick = { mediaFilter = kind }, label = stringResource(title))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                VideoFilterChip(selected = folderFilter == null,
                    onClick = { folderFilter = null }, label = stringResource(R.string.loc_all_folders))
                videoList.map { MediaRules.parentFolder(it.videoPath) }.distinct().sorted().forEach { folder ->
                    VideoFilterChip(selected = folderFilter == folder,
                        onClick = { folderFilter = folder },
                        label = folder.substringAfterLast('/').ifBlank { folder })
                }
            }
        }
    }

    val selectedItemIds = remember(videoList, viewState) { mutableStateListOf<Int>() }

    LaunchedEffect(isSelectEnabled) {
        if (!isSelectEnabled) {
            delay(200)
            selectedItemIds.clear()
        }
    }

    val selectedVideoCount =
        remember(selectedItemIds.size) {
            mutableIntStateOf(
                videoList.count { info ->
                    selectedItemIds.contains(info.id) &&
                        info.filterByType(videoFilter = true, audioFilter = false)
                }
            )
        }
    val selectedAudioCount =
        remember(selectedItemIds.size) {
            mutableIntStateOf(
                videoList.count { info ->
                    selectedItemIds.contains(info.id) &&
                        info.filterByType(videoFilter = false, audioFilter = true)
                }
            )
        }

    val selectedFileSizeSum by
        remember(selectedItemIds.size) {
            derivedStateOf {
                selectedItemIds.fold(0L) { acc: Long, id: Int ->
                    acc + fileSizeMap.getOrElse(id) { 0L }
                }
            }
        }

    val visibleItemCount =
        remember(videoList, viewState) {
            mutableIntStateOf(videoList.count { it.filterSort(mediaFilter, folderFilter) })
        }

    val checkBoxState by
        remember(selectedItemIds, visibleItemCount) {
            derivedStateOf {
                if (selectedItemIds.isEmpty()) ToggleableState.Off
                else if (
                    selectedItemIds.size == visibleItemCount.intValue &&
                        selectedItemIds.isNotEmpty()
                )
                    ToggleableState.On
                else ToggleableState.Indeterminate
            }
        }

    var showRemoveDialog by remember { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }

    BackHandler(isSelectEnabled || viewState.isSearching) {
        if (isSelectEnabled) {
            isSelectEnabled = false
        } else {
            viewModel.toggleSearch(false)
        }
    }

    LaunchedEffect(sheetState.targetValue, isSelectEnabled) {
        if (showBottomSheet || isSelectEnabled) {
            softKeyboardController?.hide()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(modifier = Modifier, text = stringResource(R.string.downloads_history))
                },
                navigationIcon = { BackButton { onNavigateBack() } },
                actions = {
                    Row {
                        if (fullVideoList.isNotEmpty()) {
                            IconToggleButton(
                                modifier = Modifier,
                                onCheckedChange = {
                                    view.slightHapticFeedback()
                                    viewModel.toggleSearch(it)
                                    if (it) {
                                        scope.launch {
                                            delay(50)
                                            lazyListState.animateScrollToItem(0)
                                        }
                                    }
                                },
                                checked = viewState.isSearching,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Search,
                                    contentDescription = stringResource(R.string.search),
                                )
                            }
                        }
                        var expanded by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                            IconButton(onClick = { expanded = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription =
                                        stringResource(id = R.string.show_more_actions),
                                )
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.loc_media_file_manager)) },
                                    onClick = {
                                        showFileManager = true
                                        expanded = false
                                    },
                                )
                                if (visibleItemCount.intValue > 0) {
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                imageVector =
                                                    Icons.AutoMirrored.Outlined.DriveFileMove,
                                                contentDescription = null,
                                            )
                                        },
                                        text = {
                                            Text(text = stringResource(id = R.string.export_backup))
                                        },
                                        onClick = {
                                            showExportDialog = true
                                            expanded = false
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Restore,
                                            contentDescription = null,
                                        )
                                    },
                                    text = {
                                        Text(text = stringResource(id = R.string.import_backup))
                                    },
                                    onClick = {
                                        showImportDialog = true
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            AnimatedVisibility(
                isSelectEnabled,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                BottomAppBar(modifier = Modifier) {
                    val selectAllText = stringResource(R.string.select_all)
                    TriStateCheckbox(
                        modifier = Modifier.semantics { this.contentDescription = selectAllText },
                        state = checkBoxState,
                        onClick = {
                            view.slightHapticFeedback()
                            when (checkBoxState) {
                                ToggleableState.On -> selectedItemIds.clear()
                                else -> {
                                    for (item in videoList) {
                                        if (
                                            !selectedItemIds.contains(item.id) &&
                                                item.filterSort(mediaFilter, folderFilter)
                                        ) {
                                            selectedItemIds.add(item.id)
                                        }
                                    }
                                }
                            }
                        },
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text =
                            stringResource(R.string.multiselect_item_count)
                                .format(selectedVideoCount.intValue, selectedAudioCount.intValue),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(
                        onClick = {
                            view.slightHapticFeedback()
                            showRemoveMultipleItemsDialog = true
                        },
                        enabled = selectedItemIds.isNotEmpty(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = stringResource(id = R.string.remove),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = hostState) },
    ) { innerPadding ->
        if (fullVideoList.isEmpty())
            Box(modifier = Modifier.fillMaxSize()) {
                val painter =
                    rememberVectorPainter(image = DynamicColorImageVectors.videoSteaming())
                Column(
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 360.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier =
                            Modifier.padding(vertical = 20.dp)
                                .fillMaxWidth(0.5f)
                                .widthIn(max = 240.dp),
                    )
                    Text(
                        text = stringResource(R.string.no_downloaded_media),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        val cellCount =
            when (LocalWindowWidthState.current) {
                WindowWidthSizeClass.Expanded -> 2
                else -> 1
            }
        val span: (LazyGridItemSpanScope) -> GridItemSpan = { GridItemSpan(cellCount) }
        LazyColumn(modifier = Modifier, state = lazyListState, contentPadding = innerPadding) {
            if (fullVideoList.isNotEmpty()) {
                item {
                    Column {
                        AnimatedVisibility(visible = viewState.isSearching) {
                            SealSearchBar(
                                modifier =
                                    Modifier.padding(horizontal = 12.dp).padding(vertical = 8.dp),
                                text = viewState.searchText,
                                placeholderText = stringResource(R.string.search_in_downloads),
                                onValueChange = viewModel::updateSearchText,
                            )
                        }
                        FilterChips(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                }
            }
            for (post in posts) {
                val info = post.first()
                val successful = post.filter { fileSizeMap.getOrElse(it.id) { 0L } > 0 }
                val qualities = post.mapNotNull { it.quality }.sortedBy { it.width.toLong() * it.height }
                val imageCount = successful.filter { it.quality?.codec?.startsWith("image/") == true || it.videoPath.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif") }.distinctBy { it.videoUrl }.size
                val successCount = successful.distinctBy { it.videoUrl }.size
                val count = maxOf(post.maxOf { it.postPageCount }, post.distinctBy { it.videoUrl }.size)
                val details = Task.ViewState(mediaCount = count, completedCount = successCount,
                    isImage = imageCount == successCount, imageCount = imageCount, videoCount = successCount - imageCount,
                    width = qualities.lastOrNull()?.width ?: 0, height = qualities.lastOrNull()?.height ?: 0,
                    minWidth = qualities.firstOrNull()?.width ?: 0, minHeight = qualities.firstOrNull()?.height ?: 0,
                    averageMbps = qualities.mapNotNull { it.averageMbps }.takeIf { it.isNotEmpty() }?.average(),
                    originalCodec = post.mapNotNull { (it.sourceQuality ?: it.quality)?.codec }.distinct().joinToString(" / "),
                    codec = post.mapNotNull { it.quality?.codec }.distinct().joinToString(" / "),
                    av1Mode = if (post.none { it.sourceQuality != null }) 0 else if (post.any { it.sourceQuality == null && it.videoUrl in post.filter { item -> item.sourceQuality != null }.map { item -> item.videoUrl } }) 2 else 1)


                item(key = info.id, contentType = { info.videoPath.contains(AUDIO_REGEX) }) {
                    with(info) {
                        AnimatedVisibility(
                            modifier = Modifier,
                            visible = post.any { it.filterSort(mediaFilter, folderFilter) },
                            exit = shrinkVertically() + fadeOut(),
                            enter = expandVertically() + fadeIn(),
                        ) {
                            MediaListItem(
                                modifier = Modifier,
                                title = videoTitle,
                                author = videoAuthor,
                                thumbnailUrl = thumbnailUrl,
                                videoPath = videoPath,
                                videoFileSize = post.sumOf { fileSizeMap.getOrElse(it.id) { 0L } },
                                postIndicator = { PostMediaDetails(details, overlay = true) },
                                videoUrl = videoUrl,
                                isSelectEnabled = { isSelectEnabled },
                                isSelected = { post.all { selectedItemIds.contains(it.id) } },
                                onSelect = {
                                    if (post.all { it.id in selectedItemIds }) selectedItemIds.removeAll(post.map { it.id }.toSet())
                                    else post.forEach { if (it.id !in selectedItemIds) selectedItemIds.add(it.id) }
                                },
                                onClick = {
                                    if (successful.isNotEmpty()) openedPost = successful.map { it.videoPath } to videoPath
                                    else scope.launch { ToastUtil.makeToastSuspend(App.context.getString(R.string.file_unavailable)) }
                                },
                                onLongClick = {
                                    isSelectEnabled = true
                                    post.forEach { if (it.id !in selectedItemIds) selectedItemIds.add(it.id) }
                                },
                                onShowContextMenu = {
                                    view.slightHapticFeedback()
                                    currentVideoInfo = info
                                    scope.launch {
                                        showBottomSheet = true
                                        delay(50)
                                        sheetState.show()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBottomSheet) {
        val isFileAvailable = fileSizeMap[currentVideoInfo.id] != 0L
        VideoDetailDrawer(
            sheetState = sheetState,
            info = currentVideoInfo,
            isFileAvailable = isFileAvailable,
            onDismissRequest = {
                scope.launch { sheetState.hide() }.invokeOnCompletion { showBottomSheet = false }
            },
            onDelete = { showRemoveDialog = true },
        )
    }

    var deleteFile by remember { mutableStateOf(false) }

    if (showRemoveDialog) {
        RemoveItemDialog(
            info = currentVideoInfo,
            deleteFile = deleteFile,
            onDeleteFileToggled = { deleteFile = it },
            onRemoveConfirm = {
                viewModel.deleteDownloadHistory(listOf(currentVideoInfo), deleteFile = deleteFile)
            },
            onDismissRequest = { showRemoveDialog = false },
        )
    }

    if (showRemoveMultipleItemsDialog) {
        SealDialog(
            onDismissRequest = { showRemoveMultipleItemsDialog = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, null) },
            title = { Text(stringResource(R.string.delete_info)) },
            text = {
                Column {
                    Text(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        text =
                            stringResource(R.string.delete_multiple_items_msg)
                                .format(selectedItemIds.size),
                    )
                    CheckBoxItem(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        text =
                            stringResource(R.string.delete_file) +
                                " (${selectedFileSizeSum.toFileSizeText()})",
                        checked = deleteFile,
                    ) {
                        deleteFile = !deleteFile
                    }
                }
            },
            confirmButton = {
                ConfirmButton {
                    viewModel.deleteDownloadHistory(
                        infoList = videoList.filter { selectedItemIds.contains(it.id) },
                        deleteFile = deleteFile,
                    )
                    showRemoveMultipleItemsDialog = false
                    isSelectEnabled = false
                }
            },
            dismissButton = { DismissButton { showRemoveMultipleItemsDialog = false } },
        )
    }

    var backupString by remember { mutableStateOf("") }

    val exportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("text/plain")
        ) { uri ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(backupString.toByteArray())
                    }
                    withContext(Dispatchers.Main) { showExportDialog = false }
                }
            }
        }

    if (showExportDialog) {
        val list =
            if (selectedItemIds.isNotEmpty()) {
                videoList.filter { selectedItemIds.contains(it.id) }
            } else {
                videoList.filter { it.filterSort(mediaFilter, folderFilter) }
            }

        ExportDialog(onDismissRequest = { showExportDialog = false }, itemCount = list.size) {
            type,
            destination ->
            list.backupToString(type).let {
                when (destination) {
                    Clipboard -> clipboardManager.setText(AnnotatedString(it))
                    File -> {
                        backupString = it
                        exportLauncher.launch(
                            BackupUtil.getDownloadHistoryExportFilename(context = context)
                        )
                    }
                }
                view.slightHapticFeedback()
                showExportDialog = false
            }
        }
    }

    val importLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri ->
            uri?.let {
                viewModel.importBackupFromUri(context, uri) {
                    viewModel.showImportedSnackbar(hostState, context, it)
                }
            }
        }

    if (showImportDialog) {

        ImportDialog(onDismissRequest = { showImportDialog = false }) { destination ->
            scope.launch {
                when (destination) {
                    Clipboard -> {
                        clipboardManager.getText()?.text?.let { str ->
                            viewModel.importBackupFromText(str) {
                                viewModel.showImportedSnackbar(hostState, context, it)
                            }
                        }
                    }

                    File -> {
                        importLauncher.launch("text/plain")
                    }
                }
            }
            view.slightHapticFeedback()
            showImportDialog = false
        }
    }
}

/** backupToString에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun List<DownloadedVideoInfo>.backupToString(type: BackupUtil.BackupType): String {
    return when (type) {
        BackupUtil.BackupType.DownloadHistory -> reversed().toJsonString()
        BackupUtil.BackupType.URLList -> toURLListString()
        else -> throw IllegalArgumentException()
    }
}
