package com.plugin.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
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
import com.plugin.compatibility.MediaDirectoryOptions

/** 미디어 폴더·해상도 분류와 파일 관리·내장 뷰어를 함께 활성화합니다. */
object LibraryPlugin : FeaturePlugin() {
    override val id = PluginId.LIBRARY
    override val route = "plugin_library"
    override val titleResource = R.string.plugin_library
    override val icon get() = Icons.Rounded.Folder

    /** 인코딩 기능 없이 폴더 설정과 기존 파일 관리를 제공합니다. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        var opened by remember { mutableStateOf(false) }
        var preferences by remember { com.plugin.compatibility.pluginPreferencesState(com.plugin.compatibility.createPluginPreferences()) }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(titleResource)) }, navigationIcon = { BackButton(onBack) })
        }) { padding ->
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                TextButton(onClick = { opened = true }, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(stringResource(R.string.loc_media_file_manager))
                }
                MediaDirectoryOptions(preferences, onUpdate = { preferences = it })
            }
        }
        if (opened) MediaFileManagerDialog { opened = false }
    }
}
