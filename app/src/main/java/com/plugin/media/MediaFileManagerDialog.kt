package com.plugin.media

import com.plugin.compatibility.copyPreservingPluginOptions
import com.plugin.compatibility.*
import com.plugin.compatibility.MediaLibrary
import com.plugin.compatibility.MediaRules

import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junkfood.seal.util.*
import com.junkfood.seal.util.PreferenceUtil.updateInt
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 모든 하위 폴더의 실제 영상 크기·길이·분류를 표시하고 선택 파일을 이동합니다. */
@Composable
fun MediaFileManagerDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var preferences by remember {
        com.plugin.compatibility.pluginPreferencesState(com.plugin.compatibility.createPluginPreferences())
    }
    var files by remember { mutableStateOf(emptyList<MediaLibrary.MediaFile>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<String?>(null) }
    var chooseFolder by remember { mutableStateOf(false) }
    val folders = files.map { it.parentUri ?: java.io.File(it.path).parent.orEmpty() }.distinct().sorted()
    val visible = files.filter { (it.parentUri ?: java.io.File(it.path).parent.orEmpty()) == folder }
    val scope = rememberCoroutineScope()
    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    preferences = preferences.copyPreservingPluginOptions { copy(sdcard = true, sdcardUri = uri.toString()) }
                    withContext(Dispatchers.IO) { MediaLibrary.listVideos(preferences) }
                }.onSuccess { scanned ->
                    files = scanned
                    folder = scanned.firstOrNull { it.parentUri != null }?.parentUri
                    selected = emptySet()
                    message = if (folder == null) context.getString(R.string.loc_no_folder_videos) else ""
                }.onFailure { message = it.message.orEmpty() }
                busy = false
            }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { MediaLibrary.listVideos(preferences) } }
            .onSuccess { files = it; folder = it.firstOrNull()?.let { file -> file.parentUri ?: java.io.File(file.path).parent } }
            .onFailure { message = it.message.orEmpty() }
        busy = false
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.loc_media_file_manager)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { folderPicker.launch(null) }) {
                    Text(stringResource(R.string.loc_choose_storage_folder))
                }
                Box {
                    TextButton(enabled = !busy && folders.isNotEmpty(), onClick = { chooseFolder = true }) {
                        Text(folder ?: stringResource(R.string.loc_choose_folder),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = chooseFolder, onDismissRequest = { chooseFolder = false }) {
                        folders.forEach { path ->
                            DropdownMenuItem(text = { Text(path, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = {
                                folder = path; selected = emptySet(); chooseFolder = false
                            })
                        }
                    }
                }
                Text(stringResource(R.string.loc_media_file_count, visible.size))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(
                    enabled = !busy && visible.isNotEmpty(),
                    onClick = {
                        selected =
                            if (selected.size == visible.size) emptySet()
                            else visible.map { it.path }.toSet()
                    },
                ) {
                    Text(stringResource(R.string.loc_select_all_toggle))
                }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(visible, key = { it.path }) { file ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                file.path in selected,
                                enabled = !busy,
                                onCheckedChange = {
                                    selected =
                                        if (it) selected + file.path else selected - file.path
                                },
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(file.name, style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val classification = file.sourceQuality ?: file.quality
                                val bitrate = classification?.averageMbps ?: MediaRules.bitrateMbps(file.bytes, file.duration)
                                file.quality?.let { quality ->
                                    Text(stringResource(R.string.loc_video_quality_details,
                                        quality.width, quality.height, quality.frameRate, quality.codec),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (file.sourceQuality != null)
                                    Text(stringResource(R.string.loc_quality_source_used), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    String.format(
                                        Locale.getDefault(),
                                        stringResource(R.string.loc_media_file_details),
                                        file.bytes / 1_000_000.0,
                                        file.duration,
                                        bitrate?.let {
                                            String.format(Locale.getDefault(), "%.2f Mbps", it)
                                        } ?: stringResource(R.string.loc_unknown_duration),
                                    )
                                )
                                Text(MediaRules.classificationFolder(classification,
                                    preferences.imageTiers, preferences.videoTiers, preferences.codecRatios)
                                    ?: stringResource(R.string.loc_base_media_folder))

                            }
                        }
                    }
                }
                if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && selected.isNotEmpty(),
                onClick = {
                    busy = true
                    scope.launch {
                        val failures =
                            withContext(Dispatchers.IO) {
                                files
                                    .filter { it.path in selected }
                                    .mapNotNull { file ->
                                        runCatching { MediaLibrary.classify(file, preferences) }
                                            .exceptionOrNull()
                                            ?.let { "${file.name}: ${it.message}" }
                                    }
                            }
                        runCatching {
                                withContext(Dispatchers.IO) { MediaLibrary.listVideos(preferences) }
                            }
                            .onSuccess { files = it }
                            .onFailure { message = it.message.orEmpty() }
                        selected = emptySet()
                        message =
                            if (failures.isEmpty()) context.getString(R.string.loc_classification_complete)
                            else failures.joinToString("\n")
                        busy = false
                    }
                },
            ) {
                Text(stringResource(R.string.loc_classify_selected))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
