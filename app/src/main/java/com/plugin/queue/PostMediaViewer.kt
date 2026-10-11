package com.plugin.queue

import com.plugin.compatibility.*
import com.plugin.compatibility.PluginPreferences.MEDIA_INFO_OVERLAY

import com.plugin.compatibility.MediaRules
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.junkfood.seal.util.toFileSizeText
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
    var volume by remember { mutableFloatStateOf(1f) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${pager.currentPage + 1} / $totalPages", Modifier.weight(1f))
                    FilterChip(showInfo, onClick = { showInfo = !showInfo; MEDIA_INFO_OVERLAY.updateBoolean(showInfo) },
                        label = { Text(stringResource(R.string.loc_media_info_overlay)) })
                    TextButton(enabled = !busy, onClick = { deleting = pages.getOrNull(pager.currentPage) }) {
                        Text(stringResource(R.string.loc_delete_page))
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                }
                HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { page ->
                    val originalPath = pages[page]
                    val info by produceState<com.junkfood.seal.database.objects.DownloadedVideoInfo?>(null, originalPath) {
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
                    var player by remember(path) { mutableStateOf<android.media.MediaPlayer?>(null) }
                    Box(Modifier.fillMaxSize()) {
                        if (image) AsyncImage(uri, stringResource(R.string.loc_image_preview),
                            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else if (page == pager.currentPage && uri != null) {
                            val video = remember(uri) { VideoView(context).apply {
                                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                                setAudioAttributes(android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                                setVideoURI(uri)
                                setOnPreparedListener { mediaPlayer ->
                                    player = mediaPlayer
                                    mediaPlayer.setVolume(volume, volume)
                                    start()
                                }
                            } }
                            SideEffect { runCatching { player?.setVolume(volume, volume) } }
                            DisposableEffect(video) { onDispose { player = null; video.stopPlayback() } }
                            AndroidView(factory = { video }, modifier = Modifier.fillMaxSize())
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
                                    quality.averageMbps?.let { add("%.2f Mbps".format(java.util.Locale.ROOT, it)) }
                                    if (quality.codec.isNotBlank()) add(com.plugin.compatibility.MediaRules.codecLabel(quality.codec))
                                } else add(MediaLibrary.fileBytes(path).toDouble().toFileSizeText())
                                saved.sourceQuality?.let { source -> add(context.getString(R.string.loc_original_codec,
                                    com.plugin.compatibility.MediaRules.codecLabel(source.codec))) }
                                add(context.getString(R.string.loc_saved_path, path as Any))
                            }.joinToString("\n")
                            Surface(Modifier.align(Alignment.TopStart).padding(12.dp), color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White) {
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { volume = if (volume > 0) 0f else 1f }) {
                            Text(stringResource(if (volume == 0f) R.string.loc_sound_off else R.string.loc_sound_on))
                        }
                        Slider(volume, onValueChange = { volume = it }, modifier = Modifier.weight(1f))
                        Text("${(volume * 100).toInt()}%")
                    }
                }
            }
        }
    }
    if (deleting != null) AlertDialog(onDismissRequest = { if (!busy) deleting = null },
        title = { Text(stringResource(R.string.loc_delete_page)) },
        text = { Text(error.ifBlank { context.getString(R.string.loc_delete_page_hint) }) },
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
