package com.plugin.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.PluginId
import com.plugin.PluginState
import com.plugin.compatibility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 등록한 폴더만 한 줄 메뉴로 제공하고 선택하지 않으면 원래 저장 규칙을 사용합니다. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadFolderSelector(preferences: DownloadPreferences, onUpdate: (DownloadPreferences) -> Unit) {
    if (!PluginState.isEnabled(PluginId.FOLDERS)) return
    var opened by remember { mutableStateOf(false) }
    val configuredQuick = runCatching { DownloadFolders.quick() }.getOrNull()
    val result by produceState<Result<List<DownloadFolder>>?>(null, preferences, opened, configuredQuick) {
        value = withContext(Dispatchers.IO) { runCatching { DownloadFolders.registered().filter {
            runCatching { DownloadFolders.validate(it, preferences) }.isSuccess
        } } }
    }
    val quick = result?.getOrNull()?.firstOrNull { it == configuredQuick }
    val viewConfiguration = LocalViewConfiguration.current
    val folderConfiguration = remember(viewConfiguration) {
        /** 이 저장폴더 버튼만 정확히 2초 길게 누르기를 사용합니다. */
        object : ViewConfiguration by viewConfiguration {
            override val longPressTimeoutMillis: Long = 2_000L
        }
    }
    val selectFolder = {
        // 현재 루트·권한 검증을 통과한 퀵 폴더만 즉시 적용하고 다시 누르면 기본 규칙으로 돌아갑니다.
        if (quick != null) onUpdate(preferences.copyPluginOptions(
            selectedFolder = if (preferences.selectedFolder == quick) null else quick))
        else opened = true
    }
    Box {
        CompositionLocalProvider(LocalViewConfiguration provides folderConfiguration) {
            val selected = if (quick != null) preferences.selectedFolder == quick else preferences.selectedFolder != null
            // 한 입력 처리기만 사용해 내부 칩 클릭이 2초 길게 누르기를 가로채지 않게 합니다.
            Surface(modifier = Modifier.combinedClickable(enabled = configuredQuick == null || result != null,
                role = Role.Button, onClick = selectFolder, onLongClick = { opened = true },
                onLongClickLabel = stringResource(R.string.plugin_download_folders)).semantics { this.selected = selected },
                shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                    Text(if (quick != null) stringResource(R.string.download_folder_quick_label, quick.label)
                        else preferences.selectedFolder?.label ?: stringResource(R.string.plugin_download_folders),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        DropdownMenu(opened, onDismissRequest = { opened = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.download_folder_default)) }, onClick = {
                onUpdate(preferences.copyPluginOptions(selectedFolder = null)); opened = false
            })
            result?.getOrNull().orEmpty().forEach { folder ->
                DropdownMenuItem(text = { Column { Text(folder.label); Text(folder.rootLabel, style = MaterialTheme.typography.labelSmall) } }, onClick = {
                    onUpdate(preferences.copyPluginOptions(selectedFolder = folder)); opened = false
                })
            }
            if (result?.isFailure == true) DropdownMenuItem(text = { Text(result?.exceptionOrNull()?.message.orEmpty()) }, enabled = false, onClick = {})
            if (result?.getOrNull()?.isEmpty() == true) DropdownMenuItem(text = { Text(stringResource(R.string.download_folder_empty)) }, enabled = false, onClick = {})
        }
    }
}

/** 기본 루트 안을 한 단계씩 탐색하고 화질 폴더는 목록과 선택에서 함께 제외합니다. */
@Composable
fun DownloadFolderPicker(preferences: DownloadPreferences, onDismiss: () -> Unit, onSelect: (DownloadFolder) -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf<DownloadFolder?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var permissionRequested by remember { mutableStateOf(false) }
    var launchError by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    /** 파일 접근 설정을 한 번 자동 요청하고 거절 이후에는 사용자 버튼으로 재요청합니다. */
    fun requestPermission() {
        permissionRequested = true
        if (Build.VERSION.SDK_INT < 30) permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else runCatching { settings.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) }
            .recoverCatching { settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            .onFailure { launchError = it.message.orEmpty() }
    }
    val needsPermission = !preferences.sdcard && !preferences.privateDirectory &&
        if (Build.VERSION.SDK_INT >= 30) !Environment.isExternalStorageManager()
        else androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED
    LaunchedEffect(needsPermission) { if (needsPermission && !permissionRequested) requestPermission() }
    val result by produceState<Result<List<DownloadFolder>>?>(null, current, refresh) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching {
            current?.let { DownloadFolders.children(it, preferences) } ?: DownloadFolders.roots(preferences).map { DownloadFolder(it) }
        } }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_folder_add)) },
        text = { Column(Modifier.fillMaxWidth()) {
            Text(current?.relative?.ifEmpty { current?.rootLabel.orEmpty() } ?: stringResource(R.string.download_folder_roots), style = MaterialTheme.typography.bodySmall)
            if (current != null) TextButton(onClick = {
                current = current?.takeIf { it.relative.isNotEmpty() }?.copy(relative = current!!.relative.substringBeforeLast('/', ""))
            }) { Text(stringResource(R.string.download_folder_parent)) }
            if (needsPermission) TextButton(onClick = ::requestPermission) { Text(stringResource(R.string.revision6_storage_permission)) }
            if (result == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (result?.isFailure == true || launchError.isNotBlank()) Text(result?.exceptionOrNull()?.message ?: launchError, color = MaterialTheme.colorScheme.error)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(result?.getOrNull().orEmpty(), key = { it.key }) { folder ->
                    Row(Modifier.fillMaxWidth().clickable { current = folder }.padding(vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null); Text(folder.relative.substringAfterLast('/').ifEmpty { folder.rootLabel }, Modifier.padding(start = 12.dp))
                    }
                }
            }
        } }, confirmButton = { TextButton(enabled = current != null && result?.isSuccess == true && !needsPermission, onClick = { current?.let(onSelect) }) {
            Text(stringResource(R.string.download_folder_select_current))
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
