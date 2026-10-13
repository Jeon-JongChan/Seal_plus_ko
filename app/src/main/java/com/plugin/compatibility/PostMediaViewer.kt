package com.plugin.compatibility

import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Wallpaper

import com.plugin.compatibility.*
import com.plugin.compatibility.PluginPreferences
import com.plugin.compatibility.PluginPreferences.MEDIA_INFO_OVERLAY

import com.plugin.compatibility.MediaRules
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.junkfood.seal.util.toFileSizeText
import com.junkfood.seal.util.toDurationText
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.junkfood.seal.R
import com.junkfood.seal.util.FileUtil
import com.plugin.compatibility.MediaLibrary
import com.junkfood.seal.util.DatabaseUtil
import com.plugin.compatibility.PluginPreferences.MEDIA_INFO_OVERLAY
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 저장된 게시물의 이미지와 영상을 좌우로 넘겨 보고 현재 영상만 재생합니다. */
@Composable
fun PostMediaViewer(paths: List<String>, initialPath: String?, onDismiss: () -> Unit) {
    var pages by remember(paths) { mutableStateOf(paths.distinct()) }
    var revision by remember(paths) { mutableIntStateOf(0) }
    var totalPages by remember(paths) { mutableIntStateOf(paths.distinct().size) }
    LaunchedEffect(paths) {
        val savedTotal = withContext(Dispatchers.IO) {
            com.plugin.compatibility.PluginDatabaseUtil.getDownloadHistory().filter { it.videoPath in paths }
                .maxOfOrNull { it.postPageCount } ?: 0
        }
        totalPages = maxOf(totalPages, savedTotal)
    }
    if (pages.isEmpty()) return
    val pager = rememberPagerState(initialPage = pages.indexOf(initialPath).coerceAtLeast(0), pageCount = { pages.size })
    var showInfo by remember { mutableStateOf(MEDIA_INFO_OVERLAY.getBoolean()) }
    var volume by remember { mutableFloatStateOf(PluginPreferences.mediaVolume) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var zoomed by remember { mutableStateOf(false) }
    var currentVideo by remember { mutableStateOf<MediaPlayer?>(null) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false; error = "" }
    LaunchedEffect(currentVideo) {
        position = 0; duration = 0; playing = false
        while (currentVideo != null) {
            currentVideo?.let { video -> runCatching {
                duration = video.duration.coerceAtLeast(0); playing = video.isPlaying
                if (!seeking) position = video.currentPosition.coerceIn(0, duration)
            } }
            delay(250)
        }
    }
    SideEffect { runCatching { currentVideo?.setVolume(volume, volume) } }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var showWallpaperDialog by remember { mutableStateOf(false) }
    if (showWallpaperDialog) com.plugin.ui.WallpaperPreviewDialog(pages.mapIndexed { index, path -> com.plugin.ui.WallpaperPageItem(path, index) }, pager.currentPage) { showWallpaperDialog = false }
    val currentPostUrl by produceState<String?>(null, pages, pager.currentPage) {
        value = withContext(Dispatchers.IO) { com.plugin.compatibility.PluginDatabaseUtil.getDownloadHistory().firstOrNull { it.videoPath == pages.getOrNull(pager.currentPage) }?.videoUrl }
    }
    // 창 자체를 시스템 바 뒤까지 확장하고 조작부에만 여백을 줘 뒤 게시글이 비치지 않게 합니다.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().clipToBounds(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${pager.currentPage + 1} / $totalPages", Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = { showInfo = !showInfo; MEDIA_INFO_OVERLAY.updateBoolean(showInfo) }) {
                        Icon(Icons.Outlined.Info, stringResource(R.string.loc_media_info_overlay))
                    }
                    IconButton(enabled = !busy, onClick = { deleting = pages.getOrNull(pager.currentPage) }) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.loc_delete_page))
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                }
                HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f).clipToBounds(), userScrollEnabled = !zoomed) { page ->
                    val originalPath = pages[page]
                    val info by produceState<com.junkfood.seal.database.objects.DownloadedVideoInfo?>(null, originalPath, revision) {
                        value = withContext(Dispatchers.IO) {
                            val preferences = com.plugin.compatibility.createPluginPreferences()
                            val path = MediaLibrary.resolveDownloadedPath(originalPath, preferences) ?: originalPath
                            if (path != originalPath) com.plugin.compatibility.PluginDatabaseUtil.updateMediaPath(originalPath, path)
                            val saved = com.plugin.compatibility.PluginDatabaseUtil.getDownloadHistory().firstOrNull { it.videoPath == path }
                            val quality = saved?.quality ?: MediaLibrary.readMediaQuality(path)
                            if (saved != null && saved.quality == null && quality != null)
                                com.plugin.compatibility.PluginMediaMetadataStore.save(saved.videoPath, quality = quality)
                            (saved ?: com.junkfood.seal.database.objects.DownloadedVideoInfo()).copy(videoPath = path).withMetadata(quality = quality)
                        }
                    }
                    val path = info?.videoPath ?: originalPath
                    val uri = remember(path) { FileUtil.createIntentForOpeningFile(path)?.data }
                    val image = com.plugin.compatibility.MediaRules.mediaKind(path, info?.quality?.codec.orEmpty()) == 2
                    var scale by remember(path) { mutableFloatStateOf(1f) }
                    var offset by remember(path) { mutableStateOf(Offset.Zero) }
                    var size by remember(path) { mutableStateOf(IntSize.Zero) }
                    val transform = rememberTransformableState { zoom, pan, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        // 확대된 영역의 끝까지만 이동하고 기본 배율에서는 게시글 넘기기를 유지합니다.
                        val limitX = size.width * (scale - 1f) / 2f
                        val limitY = size.height * (scale - 1f) / 2f
                        offset = Offset((offset.x + pan.x).coerceIn(-limitX, limitX), (offset.y + pan.y).coerceIn(-limitY, limitY))
                        if (page == pager.currentPage) zoomed = scale > 1f
                    }
                    Box(Modifier.fillMaxSize().clipToBounds().background(MaterialTheme.colorScheme.surface)) {
                        Box(Modifier.fillMaxSize().onSizeChanged { size = it }
                            .transformable(transform, canPan = { scale > 1f })
                            .pointerInput(path) { detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1f) 1f else 2f; offset = Offset.Zero
                                if (page == pager.currentPage) zoomed = scale > 1f
                            }) }
                            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }) {
                        if (image) AsyncImage(coil.request.ImageRequest.Builder(context).data(uri)
                            .memoryCacheKey("$path#$revision").diskCacheKey("$path#$revision").build(), stringResource(R.string.loc_image_preview),
                            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else if (page == pager.currentPage && uri != null) {
                            val ownedPlayer = remember(uri) { arrayOfNulls<MediaPlayer>(1) }
                            val video = remember(uri) { TextureView(context).apply {
                                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                    /** TextureView를 사용해 실제 영상 표면에도 Compose 확대·이동을 적용합니다. */
                                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                        val player = MediaPlayer(); ownedPlayer[0] = player
                                        try {
                                            android.view.Surface(texture).let { surface -> try { player.setSurface(surface) } finally { surface.release() } }
                                            player.setAudioAttributes(android.media.AudioAttributes.Builder()
                                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                                            player.setDataSource(context, uri)
                                            player.setOnPreparedListener {
                                                currentVideo = it; it.setVolume(volume, volume)
                                                fitVideoTexture(this@apply, it); it.start()
                                            }
                                            player.setOnVideoSizeChangedListener { ready, _, _ -> fitVideoTexture(this@apply, ready) }
                                            player.setOnErrorListener { failed, _, _ ->
                                                if (currentVideo === failed) currentVideo = null
                                                error = "영상을 재생할 수 없습니다."; true
                                            }
                                            player.prepareAsync()
                                        } catch (failure: Exception) { error = failure.message.orEmpty(); player.release(); ownedPlayer[0] = null }
                                    }
                                    /** 회전·창 크기 변경 시 영상 비율을 유지합니다. */
                                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                                        ownedPlayer[0]?.let { fitVideoTexture(this@apply, it) }
                                    }
                                    /** 페이지에서 제거한 표면은 해당 플레이어만 닫습니다. */
                                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                        ownedPlayer[0]?.let { if (currentVideo === it) currentVideo = null; it.release() }
                                        ownedPlayer[0] = null; return true
                                    }
                                    /** 새 프레임은 TextureView가 직접 그립니다. */
                                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                                }
                            } }
                            DisposableEffect(video) { onDispose {
                                ownedPlayer[0]?.let { if (currentVideo === it) currentVideo = null; it.release() }
                                ownedPlayer[0] = null
                            } }
                            AndroidView(factory = { video }, modifier = Modifier.fillMaxSize())
                        }
                        }
                        // 배율은 미디어 오른쪽 중앙에 반투명으로 표시해 상단 작업 버튼과 하단 정보·재생 조작부를 피합니다.
                        if (page == pager.currentPage) FilledTonalButton(
                            modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            onClick = {
                                scale = if (scale < 2f) 2f else if (scale < 4f) 4f else 1f
                                offset = Offset.Zero; zoomed = scale > 1f
                            }) { Text("확대 ${(scale * 100).toInt()}%") }
                        if (image && page == pager.currentPage) Box(Modifier.align(Alignment.TopStart).padding(8.dp).then(Modifier.zIndex(1f))) {
                            com.plugin.compatibility.ImagePluginActions(path, pages) { source, output, replace ->
                                pages = if (replace) pages.map { if (it == source || (source == path && it == originalPath)) output else it }
                                    else (pages + output).distinct()
                                totalPages = maxOf(totalPages, pages.size)
                                revision++
                            }
                        }
                        com.plugin.ui.MediaQuickActions(Modifier.align(Alignment.TopEnd).padding(8.dp),
                            onWallpaper = { showWallpaperDialog = true },
                            onInstagram = if (com.plugin.compatibility.WallpaperHelper.isInstagramUrl(currentPostUrl.orEmpty()))
                                ({ com.plugin.compatibility.WallpaperHelper.openInstagramUrl(context, currentPostUrl.orEmpty()) }) else null)
                        if (MediaRules.mediaKind(path, info?.quality?.codec.orEmpty()) == 1 && page == pager.currentPage) Box(Modifier.align(Alignment.TopStart).padding(8.dp).zIndex(1f)) {
                            VideoPluginActions(path) { output, replace ->
                                pages = if (replace) pages.map { if (it == path || it == originalPath) output else it }
                                    else (pages + output).distinct()
                                totalPages = maxOf(totalPages, pages.size); revision++
                            }
                        }
                        if (showInfo && page == pager.currentPage) info?.let { saved ->
                            val quality = saved.quality
                            val text = buildList<String> {
                                add(saved.videoTitle.takeIf { saved.id != 0 } ?: path.substringAfterLast('/'))
                                if (quality != null) {
                                    if (quality.width > 0) add("${quality.width} x ${quality.height}")
                                    if (quality.frameRate > 0) add("%.1f fps".format(java.util.Locale.ROOT, quality.frameRate))
                                    if (quality.duration > 0) add("%.2f s".format(java.util.Locale.ROOT, quality.duration))
                                    add(quality.bytes.toDouble().toFileSizeText())
                                    quality.averageMbps?.let { add("%.2f MB/s".format(java.util.Locale.ROOT, it / 8)) }
                                    if (quality.codec.isNotBlank()) add(com.plugin.compatibility.MediaRules.codecLabel(quality.codec))
                                } else add(MediaLibrary.fileBytes(path).toDouble().toFileSizeText())
                                saved.sourceQuality?.let { source -> add(context.getString(R.string.loc_original_codec,
                                    com.plugin.compatibility.MediaRules.codecLabel(source.codec))) }
                                add(context.getString(R.string.loc_saved_path, path as Any))
                            }.joinToString("\n")
                            Surface(Modifier.align(Alignment.BottomStart).padding(12.dp).padding(end = 100.dp), color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(text, Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(8.dp),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                val current = pages.getOrNull(pager.currentPage)
                if (current != null && com.plugin.compatibility.MediaRules.mediaKind(current) != 2) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(enabled = currentVideo != null, onClick = {
                            currentVideo?.let { video ->
                                if (video.isPlaying) video.pause() else { if (position >= duration) video.seekTo(0); video.start() }
                                playing = video.isPlaying
                            }
                        }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "일시정지" else "재생") }
                        Text((position / 1000).toDurationText(), style = MaterialTheme.typography.labelSmall)
                        Slider(position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                            enabled = duration > 0, valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                            onValueChange = { seeking = true; position = it.toInt() }, onValueChangeFinished = {
                                currentVideo?.seekTo(position); seeking = false
                            }, modifier = Modifier.weight(1f).semantics { contentDescription = "재생 위치" })
                        Text((duration / 1000).toDurationText(), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { volume = if (volume > 0) 0f else 1f; PluginPreferences.mediaVolume = volume }) {
                            Text(stringResource(if (volume == 0f) R.string.loc_sound_off else R.string.loc_sound_on))
                        }
                        // 드래그 중에는 재생 음량만 갱신하고 손을 뗄 때 마지막 값을 저장합니다.
                        Slider(volume, onValueChange = { volume = it }, onValueChangeFinished = { PluginPreferences.mediaVolume = volume }, modifier = Modifier.weight(1f).semantics { contentDescription = "음량" })
                        Text("${(volume * 100).toInt()}%")
                    }
                }
                if (error.isNotBlank() && deleting == null) Text(error, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (deleting != null) AlertDialog(onDismissRequest = { if (!busy) deleting = null },
        title = { Text(stringResource(R.string.loc_delete_page)) },
        text = { Text(error.ifBlank { context.getString(R.string.revision5_delete_file_hint) }) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val selected = deleting ?: return@TextButton
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val path = MediaLibrary.resolveDownloadedPath(selected,
                            com.plugin.compatibility.createPluginPreferences()) ?: selected
                        com.plugin.compatibility.CompatibilityFiles.deleteFile(path).onSuccess { com.plugin.compatibility.PluginDatabaseUtil.deleteInfoByPath(path) }
                    }
                    result.onSuccess {
                        pages = pages - selected
                        deleting = null; error = ""
                        if (pages.isEmpty()) onDismiss()
                    }.onFailure { error = it.message.orEmpty() }
                    busy = false
                }
            }) { Text(stringResource(R.string.loc_delete_page)) }
        }, dismissButton = {
            TextButton(enabled = !busy, onClick = { deleting = null; error = "" }) { Text(stringResource(R.string.cancel)) }
        })
}

/** 기본 크기에서 영상을 늘이지 않고 가운데 맞춘 뒤 바깥 레이어의 확대를 적용합니다. */
private fun fitVideoTexture(view: TextureView, player: MediaPlayer) {
    if (view.width <= 0 || view.height <= 0 || player.videoWidth <= 0 || player.videoHeight <= 0) return
    val ratio = minOf(view.width.toFloat() / player.videoWidth, view.height.toFloat() / player.videoHeight)
    view.setTransform(android.graphics.Matrix().apply {
        setScale(player.videoWidth * ratio / view.width, player.videoHeight * ratio / view.height, view.width / 2f, view.height / 2f)
    })
}
