package com.plugin.library

import com.junkfood.seal.ui.page.videolist.*
import com.plugin.compatibility.*
import com.plugin.library.MediaFileManagerDialog
import com.plugin.compatibility.PostMediaViewer
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.produceState
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItem
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.runtime.snapshotFlow
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
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.makeToast
import com.junkfood.seal.util.toFileSizeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

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
fun EnhancedVideoListPage(viewModel: VideoListViewModel = koinViewModel(), enhanced: Boolean = true, onNavigateBack: () -> Unit) {
    var showFileManager by remember { mutableStateOf(false) }
    if (showFileManager) MediaFileManagerDialog { showFileManager = false }
    val viewState by viewModel.stateFlow.collectAsStateWithLifecycle()
    var mediaFilter by remember { mutableIntStateOf(0) }
    var aiOnly by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val pendingAi by AiMediaFilter.pending.collectAsStateWithLifecycle()
    val metadataRevision by PluginMediaMetadataStore.revision.collectAsStateWithLifecycle()
    var sortByName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(enhanced && PluginPreferences.historySortByName) }
    var folderMode by remember { mutableStateOf(false) }
    var folderFilter by remember { mutableStateOf<String?>(null) }
    // 이 화면이 자체 정렬하므로 원본 뷰모델의 중복 정렬을 거치지 않습니다.
    val historyFlow = remember { com.junkfood.seal.util.DatabaseUtil.getDownloadHistoryFlow().flowOn(Dispatchers.IO) }
    val observedVideoList by historyFlow.collectAsStateWithLifecycle(emptyList())
    var refreshedVideoList by remember { mutableStateOf<List<DownloadedVideoInfo>?>(null) }
    val scan by MediaScanService.state.collectAsStateWithLifecycle()
    LaunchedEffect(observedVideoList) { refreshedVideoList = null }
    LaunchedEffect(scan.running) {
        if (!scan.running && scan.done > 0) refreshedVideoList = PluginDatabaseUtil.getDownloadHistory()
    }
    val fullVideoList = refreshedVideoList ?: observedVideoList
    val preferredFolder = run {
        val preferences = createPluginPreferences()
        if (preferences.sdcard) {
            runCatching {
                val tree = android.net.Uri.parse(preferences.sdcardUri)
                val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
                    android.provider.DocumentsContract.getTreeDocumentId(tree))
                MediaRules.parentFolder(document.toString() + "/_")
            }.getOrDefault(MediaRules.parentFolder(preferences.sdcardUri + "/_"))
        } else MediaLibrary.rootPath(preferences)
    }
    val historyFolders = remember(fullVideoList) { fullVideoList.map { MediaRules.parentFolder(it.videoPath) }.filter { it.isNotBlank() }.distinct() }
    val folderRoot = remember(historyFolders, preferredFolder) { MediaRules.historyFolderRoot(preferredFolder, historyFolders) }
    LaunchedEffect(folderRoot) {
        val currentFolder = folderFilter
        if (folderMode && (currentFolder == null || folderRoot.isNotEmpty() && currentFolder != folderRoot && !currentFolder.startsWith(folderRoot.trimEnd('/') + "/"))) folderFilter = folderRoot
    }
    // 직접 경로를 갱신한 이력도 검색 결과에서는 최신 파일 경로를 사용합니다.
    val searchedIds = if (viewState.isSearching) {
        val searchFlow = remember(viewModel) { viewModel.searchedVideoListFlow.flowOn(Dispatchers.Default) }
        val searchedVideoList by searchFlow.collectAsStateWithLifecycle(emptyList())
        remember(searchedVideoList) { searchedVideoList.map { it.id }.toSet() }
    } else emptySet()
    val videoList = remember(fullVideoList, viewState.isSearching, searchedIds) {
        if (viewState.isSearching) fullVideoList.filter { it.id in searchedIds } else fullVideoList
    }
    val posts by produceState<List<List<DownloadedVideoInfo>>>(emptyList(), videoList,
        listOf(mediaFilter, folderFilter, sortByName, enhanced, aiOnly, pendingAi, metadataRevision, if (aiOnly) fullVideoList else null)) {
        // 대량 이력의 필터·그룹·정렬은 UI 스레드 밖에서 한 번만 계산합니다.
        value = withContext(Dispatchers.Default) {
        val visible = videoList.filter { it.filterSort(mediaFilter, folderFilter) }
        /** 원본 이력의 게시글 묶음 키입니다. */
        fun keyOf(info: DownloadedVideoInfo) =
            if (enhanced && info.extractor.lowercase() in setOf("instagram", "tiktok")) MediaRules.identity(info.videoUrl.substringBefore('#'))
            else info.videoPath
        val basis = if (aiOnly) fullVideoList else visible
        val (duplicates, originals) = basis.partition { PluginMediaMetadataStore.isAiDuplicate(it.videoPath) }
        val byUrl = originals.associateBy { it.videoUrl }
        // AI 복제는 원본과 같은 키를 써야 한 게시글로 묶입니다.
        // 같은 폴더의 원본 파일 이름(정확한 원본 페이지)을 먼저 보고, 이름이 바뀐 경우에만 URL로 찾습니다.
        val byName = if (duplicates.isEmpty()) null else MediaRules.duplicateOrigins(originals) { it.videoPath }
        val duplicateKeys = duplicates.associate { dup ->
            dup.id to ((byName?.invoke(dup.videoPath) ?: byUrl[dup.videoUrl])?.let(::keyOf) ?: keyOf(dup))
        }
        // 종류·폴더 필터에 가려진 페이지도 게시물의 복제·교체 자격 판정에는 포함합니다.
        val aiPosts = if (aiOnly) basis.asSequence()
            .filter { AiMediaFilter.matches(listOf(it.videoPath), pendingAi) }
            .map { duplicateKeys[it.id] ?: keyOf(it) }.toSet() else emptySet()
        visible.groupBy { duplicateKeys[it.id] ?: keyOf(it) }
            .filterKeys { !aiOnly || it in aiPosts }.values
            .map { it.sortedBy { page -> page.postPageIndex } }
        // 원본 이력에 시각 필드가 없어 증가하는 등록 ID로 최신 등록 순서를 유지합니다.
        .sortMedia(sortByName, { post -> post.maxOf { it.id }.toLong() }, { post -> post.first().videoTitle }) }
    }
    val filteredIds = remember(posts) { posts.flatten().map { it.id }.toSet() }
    var visibleCount by remember(mediaFilter, folderFilter, sortByName, viewState.isSearching, searchedIds, aiOnly) { mutableIntStateOf(30) }
    var openedPost by remember { mutableStateOf<Pair<List<String>, String?>?>(null) }
    openedPost?.let { (paths, initial) -> PostMediaViewer(paths, initial) { openedPost = null } }
    var wallpaperPages by remember { mutableStateOf<List<com.plugin.ui.WallpaperPageItem>?>(null) }
    wallpaperPages?.let { pages ->
        com.plugin.ui.WallpaperPreviewDialog(pages) { wallpaperPages = null }
    }

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

    val sheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)
    val hostState = remember { SnackbarHostState() }

    // 이력 알림마다 디스크 전체를 재검색하지 않고 진입·스캔 완료 때만 외부 파일을 연결합니다.
    LaunchedEffect(scan.running) {
        if (enhanced && !scan.running) runCatching {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                MediaLibrary.registerExternalFiles(MediaLibrary.listVideos(createPluginPreferences()))
            }
            refreshedVideoList = PluginDatabaseUtil.getDownloadHistory()
        }.onFailure { hostState.showSnackbar(it.message.orEmpty()) }
    }

    var currentVideoInfo by remember { mutableStateOf(DownloadedVideoInfo()) }

    var isSelectEnabled by remember { mutableStateOf(false) }
    var showRemoveMultipleItemsDialog by remember { mutableStateOf(false) }

    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showCleanup by remember { mutableStateOf(false) }
    if (showCleanup) HistoryCleanupDialog(onDismiss = { showCleanup = false }, onPermissionNeeded = {
        showCleanup = false; showFileManager = true
    }, onFinished = { count ->
        showCleanup = false
        scope.launch {
            refreshedVideoList = PluginDatabaseUtil.getDownloadHistory()
            hostState.showSnackbar("이력 ${count}개 정리 완료")
        }
    })

    val lazyListState = rememberLazyListState()
    LaunchedEffect(lazyListState, visibleCount, posts.size) {
        if (visibleCount < posts.size) snapshotFlow {
            lazyListState.isScrollInProgress &&
                lazyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.key == "history-more"
        }.collect { reachedEnd -> if (reachedEnd) visibleCount = minOf(visibleCount + 30, posts.size) }
    }
    // 정렬·폴더·유형이 바뀌면 이전 항목의 위치 대신 새 목록의 처음을 표시합니다.
    LaunchedEffect(folderFilter, mediaFilter, aiOnly) { lazyListState.requestScrollToItem(0) }

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
                if (enhanced) {
                    VideoFilterChip(selected = aiOnly, onClick = { aiOnly = !aiOnly }, label = "복제·교체")
                    VideoFilterChip(selected = !folderMode, onClick = { folderMode = false; folderFilter = null }, label = "전체보기")
                    VideoFilterChip(selected = folderMode, onClick = { folderMode = true; folderFilter = folderRoot }, label = "폴더별")
                    VideoFilterChip(selected = !sortByName, onClick = { sortByName = false; PluginPreferences.historySortByName = false; lazyListState.requestScrollToItem(0) }, label = "시간순")
                    VideoFilterChip(selected = sortByName, onClick = { sortByName = true; PluginPreferences.historySortByName = true; lazyListState.requestScrollToItem(0) }, label = "이름순")
                }
            }
            if (folderMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = folderFilter != folderRoot, onClick = {
                        folderFilter = MediaRules.historyFolderParent(folderFilter.orEmpty(), folderRoot)
                    }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "상위 폴더"); Text("상위") }
                    Text(folderFilter?.ifBlank { "모든 위치" } ?: folderRoot, modifier = Modifier.weight(1f).padding(end = 12.dp),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }

    val selectedItemIds = remember(videoList, viewState, mediaFilter, folderFilter, aiOnly) { mutableStateListOf<Int>() }
    LaunchedEffect(filteredIds) { selectedItemIds.retainAll(filteredIds) }
    val selectedIds by remember(selectedItemIds) { derivedStateOf { selectedItemIds.toSet() } }

    val visiblePostIds by remember(lazyListState) { derivedStateOf {
        lazyListState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int }.toSet()
    } }
    val sizeCache = remember(videoList, scan.running) { mutableMapOf<Int, Long>() }
    // 실제 화면의 게시글과 선택 항목만 조회하며 스크롤할 때 앞쪽 파일을 다시 열지 않습니다.
    val fileSizeMap by androidx.compose.runtime.produceState<Map<Int, Long>>(emptyMap(), posts,
        listOf(visiblePostIds, selectedItemIds.toList(), scan.running)) {
        val selected = selectedItemIds.toSet()
        val visible = if (visiblePostIds.isEmpty()) posts.take(8) else posts.filter { it.first().id in visiblePostIds }
        val needed = (visible.flatten() + videoList.filter { it.id in selected }).distinctBy { it.id }
        // 작업 스레드는 UI가 갱신하는 가변 캐시 대신 필요한 항목의 불변 사본만 읽습니다.
        val cached = needed.associate { it.id to sizeCache[it.id] }
        val measured = withContext(Dispatchers.IO) {
            needed.associate { it.id to (cached[it.id] ?: MediaLibrary.fileBytes(it.videoPath)) }
        }
        sizeCache.putAll(measured)
        value = sizeCache.toMap()
    }

    LaunchedEffect(isSelectEnabled) {
        if (!isSelectEnabled) {
            delay(200)
            selectedItemIds.clear()
        }
    }

    // 이미지·영상·음원을 한 번만 분류해 선택한 이미지를 영상 수로 표시하지 않습니다.
    val selectedCounts = remember(selectedIds, videoList) {
        videoList.filter { it.id in selectedIds }
            .groupingBy { MediaRules.mediaKind(it.videoPath, it.quality?.codec.orEmpty()) }.eachCount()
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
        remember(filteredIds) {
            mutableIntStateOf(filteredIds.size)
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

    BackHandler(isSelectEnabled || viewState.isSearching || folderMode && folderFilter != folderRoot) {
        if (isSelectEnabled) {
            isSelectEnabled = false
        } else if (viewState.isSearching) {
            viewModel.toggleSearch(false)
        } else {
            folderFilter = MediaRules.historyFolderParent(folderFilter.orEmpty(), folderRoot)
        }
    }

    LaunchedEffect(sheetState.targetValue, isSelectEnabled) {
        if (showBottomSheet || isSelectEnabled) {
            softKeyboardController?.hide()
        }
    }

    val childFolders = remember(videoList, mediaFilter, folderFilter, folderMode, scan.running) {
        if (!folderMode) emptyList() else {
            val folders = videoList.filter { mediaFilter == 0 || MediaRules.mediaKind(it.videoPath, it.quality?.codec.orEmpty()) == mediaFilter }
                .map { MediaRules.parentFolder(it.videoPath) }.filter { it.isNotBlank() }.distinct()
            MediaRules.historyFolderChildren(folderFilter.orEmpty(), folders)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(modifier = Modifier, text = stringResource(R.string.downloads_history))
                        Text("표시 ${minOf(visibleCount, posts.size)} / ${posts.size}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { BackButton {
                    if (folderMode && folderFilter != folderRoot) folderFilter = MediaRules.historyFolderParent(folderFilter.orEmpty(), folderRoot)
                    else onNavigateBack()
                } },
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
                                if (enhanced) DropdownMenuItem(
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
                                if (enhanced) DropdownMenuItem(text = { Text("정리하기") }, enabled = !scan.running,
                                    leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                                    onClick = { showCleanup = true; expanded = false })
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
                                    // 선택 여부를 집합으로 조회해 대량 전체 선택의 반복 선형 탐색을 없앱니다.
                                    selectedItemIds.addAll(videoList.filter {
                                        it.id !in selectedIds && it.id in filteredIds
                                    }.map { it.id })
                                }
                            }
                        },
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text =
                            stringResource(R.string.multiselect_item_count)
                                .format(selectedCounts[1] ?: 0, selectedCounts[3] ?: 0) +
                                if ((selectedCounts[2] ?: 0) > 0) " · " + stringResource(R.string.loc_image_count, selectedCounts[2] ?: 0) else "",
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
            if (folderMode) {
                for (folder in childFolders) {
                    item(key = "folder:$folder") {
                        ListItem(modifier = Modifier.clickable(enabled = !isSelectEnabled) { folderFilter = folder },
                            headlineContent = { Text(folder.substringAfterLast('/').ifBlank { "저장소" }) },
                            supportingContent = { Text(folder, style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { Icon(Icons.Outlined.Folder, null) })
                    }
                }
            }
            items(posts.take(visibleCount), key = { it.first().id },
                contentType = { MediaRules.mediaKind(it.first().videoPath, it.first().quality?.codec.orEmpty()) }) { post ->
                val info = post.first()
                val postSizes = post.map { fileSizeMap[it.id] ?: 0L }
                val successful = remember(post, postSizes) { post.filterIndexed { index, _ -> postSizes[index] > 0 } }
                val details = remember(post, postSizes, scan.running) {
                    val qualities = post.associate { it.id to it.quality }
                    val sources = post.associate { it.id to it.sourceQuality }
                    val duplicatePaths = post.filter { PluginMediaMetadataStore.isAiDuplicate(it.videoPath) }.map { it.videoPath }.toSet()
                    val uniqueSuccessful = successful.distinctBy { if (it.videoPath in duplicatePaths) it.videoPath else it.videoUrl }
                    val imageCount = uniqueSuccessful.count { MediaRules.mediaKind(it.videoPath, qualities[it.id]?.codec.orEmpty()) == 2 }
                    val originalCount = post.filterNot { it.videoPath in duplicatePaths }.distinctBy { it.videoUrl }.size
                    val count = maxOf(post.maxOf { it.postPageCount }, originalCount) + post.count { it.videoPath in duplicatePaths }
                    val sizes = qualities.values.filterNotNull().filter { it.width > 0 && it.height > 0 }
                    val smallest = sizes.minByOrNull { it.width.toLong() * it.height }
                    val largest = sizes.asReversed().maxByOrNull { it.width.toLong() * it.height }
                    // 변환된 URL을 한 번 색인해 원본·변환본 혼합 여부의 중첩 순회를 없앱니다.
                    val sourceUrls = post.filter { sources[it.id] != null }.map { it.videoUrl }.toSet()
                    Task.ViewState(mediaCount = count, completedCount = uniqueSuccessful.size,
                        isImage = imageCount == uniqueSuccessful.size, imageCount = imageCount, videoCount = uniqueSuccessful.size - imageCount,
                        width = largest?.width ?: 0, height = largest?.height ?: 0,
                        minWidth = smallest?.width ?: 0, minHeight = smallest?.height ?: 0,
                        averageMbps = qualities.values.filterNotNull().mapNotNull { it.averageMbps }.takeIf { it.isNotEmpty() }?.average(),
                        originalCodec = post.mapNotNull { (sources[it.id] ?: qualities[it.id])?.codec }.distinct().joinToString(" / "),
                        codec = qualities.values.filterNotNull().map { it.codec }.distinct().joinToString(" / "),
                        av1Mode = if (sourceUrls.isEmpty()) 0 else if (post.any { sources[it.id] == null && it.videoUrl in sourceUrls }) 2 else 1)
                }

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
                                postIndicator = {
                                    Column {
                                        if (extractor == "External") Text(stringResource(R.string.revision5_external),
                                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                        if (enhanced) PostMediaDetails(details, overlay = true, compact = true)
                                    }
                                },
                                videoUrl = videoUrl,
                                isSelectEnabled = { isSelectEnabled },
                                isSelected = { post.all { it.id in selectedIds } },
                                onSelect = {
                                    if (post.all { it.id in selectedIds }) selectedItemIds.removeAll(post.map { it.id }.toSet())
                                    else selectedItemIds.addAll(post.filter { it.id !in selectedIds }.map { it.id })
                                },
                                onClick = {
                                    if (!enhanced) FileUtil.openFile(videoPath) { context.makeToast(R.string.file_unavailable) }
                                    else if (successful.isNotEmpty()) openedPost = successful.map { it.videoPath } to videoPath
                                    else scope.launch { ToastUtil.makeToastSuspend(App.context.getString(R.string.file_unavailable)) }
                                },
                                onLongClick = {
                                    isSelectEnabled = true
                                    selectedItemIds.addAll(post.filter { it.id !in selectedIds }.map { it.id })
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
                                onWallpaperClick = if (!enhanced) null else ({
                                    val files = if (successful.isNotEmpty()) successful.map { it.videoPath } else listOf(videoPath)
                                    val targetList = files.mapIndexed { idx, p -> com.plugin.ui.WallpaperPageItem(p, idx) }
                                    if (targetList.isNotEmpty()) wallpaperPages = targetList
                                }),
                                onInstagramClick = if (!enhanced) null else ({
                                    com.plugin.compatibility.WallpaperHelper.openInstagramUrl(context, videoUrl)
                                }),
                            )
                        }
                    }
            }
            if (visibleCount < posts.size) item(key = "history-more") {
                TextButton(onClick = { visibleCount = minOf(visibleCount + 30, posts.size) }, modifier = Modifier.fillMaxWidth()) {
                    Text("30개 더 보기 (${minOf(visibleCount, posts.size)}/${posts.size})")
                }
            }
        }
    }

    if (showBottomSheet) {
        val isFileAvailable = (fileSizeMap[currentVideoInfo.id] ?: 0L) > 0
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
                val removeFiles = deleteFile
                scope.launch { PluginDatabaseUtil.deleteInfoListSafe(listOf(currentVideoInfo), deleteFile = removeFiles) }
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
                    val targets = videoList.filter { it.id in selectedIds }
                    val removeFiles = deleteFile
                    scope.launch { PluginDatabaseUtil.deleteInfoListSafe(targets, deleteFile = removeFiles) }
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
                videoList.filter { it.id in selectedIds }
            } else {
                videoList.filter { it.id in filteredIds }
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
                        // 공급자가 JSON 백업을 text/plain 이외의 MIME로 표시해도 선택할 수 있습니다.
                        importLauncher.launch("*/*")
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
