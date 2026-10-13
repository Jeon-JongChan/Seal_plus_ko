package com.plugin.folders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import com.plugin.compatibility.*
import com.plugin.ui.DownloadFolderPicker

/** 기본 디렉터리 안의 기존 폴더를 다운로드창의 선택 목록에 등록합니다. */
object DownloadFoldersPlugin : FeaturePlugin() {
    override val id = PluginId.FOLDERS
    override val route = "download_folders"
    override val titleResource = R.string.plugin_download_folders
    override val icon = Icons.Outlined.Folder

    /** 등록 목록만 관리하고 실제 폴더·파일은 생성하거나 삭제하지 않습니다. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        val loaded = remember { runCatching { DownloadFolders.registered() } }
        var folders by remember { mutableStateOf(loaded.getOrDefault(emptyList())) }
        var error by remember { mutableStateOf(loaded.exceptionOrNull()?.message.orEmpty()) }
        var quickKey by remember { mutableStateOf(runCatching { DownloadFolders.quick()?.key }.getOrNull()) }
        var opened by remember { mutableStateOf(false) }
        val preferences = remember { createPluginPreferences() }
        /** 저장에 성공한 경우에만 화면의 등록 목록을 교체합니다. */
        fun save(value: List<DownloadFolder>) {
            runCatching { DownloadFolders.save(value) }.onSuccess { folders = value; quickKey = DownloadFolders.quick()?.key; error = "" }.onFailure { error = it.message.orEmpty() }
        }
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(titleResource)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.plugin_download_folders_desc)) }
                item { Text(stringResource(R.string.download_folder_quick_hint), style = MaterialTheme.typography.bodySmall) }
                item { FilledTonalButton(enabled = loaded.isSuccess, onClick = { opened = true }) { Text(stringResource(R.string.download_folder_add)) } }
                if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
                if (folders.isEmpty()) item { Text(stringResource(R.string.download_folder_empty)) }
                items(folders, key = { it.key }) { folder ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(folder.label, style = MaterialTheme.typography.titleSmall)
                        Text(folder.rootLabel, style = MaterialTheme.typography.bodySmall)
                        if (folder.root !in DownloadFolders.roots(preferences)) Text(stringResource(R.string.download_folder_root_changed), color = MaterialTheme.colorScheme.error)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            FilterChip(selected = quickKey == folder.key, onClick = {
                                runCatching { DownloadFolders.setQuick(if (quickKey == folder.key) null else folder) }
                                    .onSuccess { quickKey = DownloadFolders.quick()?.key; error = "" }
                                    .onFailure { error = it.message.orEmpty() }
                            }, label = { Text(stringResource(R.string.download_folder_quick)) })
                            TextButton(onClick = { save(folders - folder) }) { Text(stringResource(R.string.download_folder_remove)) }
                        }
                    } }
                }
            }
        }
        if (opened) DownloadFolderPicker(preferences, onDismiss = { opened = false }, onSelect = {
            save((folders + it).distinct()); opened = false
        })
    }
}
