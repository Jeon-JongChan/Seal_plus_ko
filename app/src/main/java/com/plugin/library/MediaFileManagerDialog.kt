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
    var replaceDuplicates by remember { mutableStateOf(PluginPreferences.mediaReplaceDuplicates) }
    var cleanup by remember { mutableStateOf("plugin_cleanup_empty_folders".getBoolean(false)) }
    var permissionRequested by remember { mutableStateOf(false) }
    var pendingScan by remember { mutableStateOf<Boolean?>(null) }
    val scan by MediaScanService.state.collectAsStateWithLifecycle()
    val permissionSettings = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        preferences = createPluginPreferences()
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) pendingScan = null
        refresh++
    }
    val legacyPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        if (!it) pendingScan = null
        refresh++
    }
    // 읽기 오류가 없어도 다른 앱이 저장한 파일의 이동에는 별도의 쓰기 접근이 필요합니다.
    val needsStoragePermission = !preferences.sdcard && !preferences.privateDirectory &&
        if (android.os.Build.VERSION.SDK_INT >= 30) !android.os.Environment.isExternalStorageManager()
        else androidx.core.content.ContextCompat.checkSelfPermission(context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
    /** 명시적 버튼 또는 첫 접근 실패에서 시스템 권한 화면을 열고 자동 반복은 막습니다. */
    fun requestStoragePermission() {
        permissionRequested = true
        if (android.os.Build.VERSION.SDK_INT < 30) {
            legacyPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            // 일부 기기에 앱별 설정 화면이 없으면 전체 파일 접근 설정으로 안내합니다.
            runCatching {
                permissionSettings.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")))
            }.recoverCatching {
                permissionSettings.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure { error = it.message.orEmpty(); pendingScan = null }
        }
    }
    LaunchedEffect(needsStoragePermission) {
        // 폴더가 일부 읽히거나 비어 있어도 파일 관리자 진입 즉시 필요한 권한을 요청합니다.
        if (needsStoragePermission && !permissionRequested) requestStoragePermission()
    }
    LaunchedEffect(needsStoragePermission, loading, pendingScan, scan.running, error) {
        if (!needsStoragePermission && !loading && !scan.running && error.isBlank()) pendingScan?.let { all ->
            pendingScan = null
            MediaScanService.start(context, preferences, all, cleanup)
        }
    }
    LaunchedEffect(preferences, scan.running, refresh, needsStoragePermission) {
        if (!scan.running) {
            // 권한 요청 전에 수천 개의 파일 정보를 읽느라 버튼이 잠기지 않게 합니다.
            if (needsStoragePermission) { loading = false; count = 0; return@LaunchedEffect }
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
                if (!preferences.sdcard && !preferences.privateDirectory) {
                    Text(stringResource(if (needsStoragePermission) R.string.revision6_storage_permission_hint
                        else R.string.revision6_storage_permission_granted), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !scan.running, onClick = { requestStoragePermission() }) {
                        Text(stringResource(if (needsStoragePermission) R.string.revision6_storage_permission
                            else R.string.revision6_storage_settings))
                    }
                }
                Text("같은 이름의 파일", style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !replaceDuplicates, enabled = !scan.running, onClick = {
                        replaceDuplicates = false; PluginPreferences.mediaReplaceDuplicates = false
                    }, label = { Text("이름에 (1), (2) 추가") })
                    FilterChip(selected = replaceDuplicates, enabled = !scan.running, onClick = {
                        replaceDuplicates = true; PluginPreferences.mediaReplaceDuplicates = true
                    }, label = { Text("기존 파일 대체") })
                }
                Text(if (replaceDuplicates) "재스캔 시 기존 파일의 내용과 연결 이력을 새 파일로 바꿉니다."
                    else "중복 이름에 번호를 붙여 두 파일을 모두 보존합니다.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), enabled = !loading && !scan.running && (needsStoragePermission || error.isBlank()),
                        onClick = {
                            if (needsStoragePermission) { pendingScan = false; requestStoragePermission() }
                            else MediaScanService.start(context, preferences, false, cleanup)
                        }) {
                        Text(stringResource(R.string.revision5_scan))
                    }
                    OutlinedButton(modifier = Modifier.weight(1f), enabled = !loading && !scan.running && (needsStoragePermission || error.isBlank()),
                        onClick = {
                            if (needsStoragePermission) { pendingScan = true; requestStoragePermission() }
                            else MediaScanService.start(context, preferences, true, cleanup)
                        }) {
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
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
