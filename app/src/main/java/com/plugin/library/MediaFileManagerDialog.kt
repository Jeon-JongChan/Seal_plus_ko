package com.plugin.library

import com.plugin.compatibility.*
import com.plugin.compatibility.MediaLibrary

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean

/** 기본 다운로드 경로와 스캔·재스캔 상태를 표시합니다. */
@Composable
fun MediaFileManagerDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var preferences by remember { pluginPreferencesState(createPluginPreferences()) }
    var loading by remember { mutableStateOf(true) }
    var count by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var cleanup by remember { mutableStateOf("plugin_cleanup_empty_folders".getBoolean(false)) }
    val scan by MediaScanService.state.collectAsStateWithLifecycle()
    val permissionSettings = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        preferences = createPluginPreferences()
        refresh++
    }
    LaunchedEffect(preferences, scan.running, refresh) {
        if (!scan.running) {
            loading = true
            runCatching { withContext(Dispatchers.IO) { MediaLibrary.listVideos(preferences).size } }
                .onSuccess { count = it; error = "" }.onFailure { count = 0; error = it.message.orEmpty() }
            loading = false
        }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.loc_media_file_manager)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.revision6_scan_folder), style = MaterialTheme.typography.titleSmall)
                        Text(MediaLibrary.rootPath(preferences), style = MaterialTheme.typography.bodySmall,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.revision6_file_count, count), style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.revision6_directory_settings_only),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.revision6_scan_hint), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), enabled = !loading && !scan.running && error.isBlank(),
                        onClick = { MediaScanService.start(context, preferences, false, cleanup) }) {
                        Text(stringResource(R.string.revision5_scan))
                    }
                    OutlinedButton(modifier = Modifier.weight(1f), enabled = !loading && !scan.running && error.isBlank(),
                        onClick = { MediaScanService.start(context, preferences, true, cleanup) }) {
                        Text(stringResource(R.string.revision5_rescan))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.revision6_cleanup), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Switch(cleanup && !preferences.sdcard, enabled = !preferences.sdcard && !scan.running,
                        onCheckedChange = { cleanup = it; "plugin_cleanup_empty_folders".updateBoolean(it) })
                }
                if (preferences.sdcard) Text(stringResource(R.string.revision6_saf_cleanup_hint),
                    style = MaterialTheme.typography.bodySmall)
                if (loading || scan.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (scan.total > 0 || scan.failures > 0) Text(
                    stringResource(R.string.revision5_scan_status, scan.done, scan.total, scan.failures) +
                        "\n" + stringResource(R.string.revision6_cleaned, scan.cleaned), style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank() || scan.error.isNotBlank()) Text(error.ifBlank { scan.error },
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank() && android.os.Build.VERSION.SDK_INT >= 30 && !preferences.sdcard &&
                    !preferences.privateDirectory && !android.os.Environment.isExternalStorageManager()) {
                    OutlinedButton(onClick = {
                        permissionSettings.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")))
                    }) { Text(stringResource(R.string.revision6_storage_permission)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
