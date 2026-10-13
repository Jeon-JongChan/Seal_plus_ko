package com.plugin.media

import com.plugin.compatibility.MediaDownloadOptions
import com.plugin.PluginRoutes

import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.util.DownloadUtil
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import com.plugin.FeaturePlugin
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel

/** 영상 인코딩과 컨테이너·저장 파일 재사용 설정을 제공합니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaToolsPage(onBack: () -> Unit) {
    var preferences by remember { com.plugin.compatibility.pluginPreferencesState(com.plugin.compatibility.createPluginPreferences()) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.loc_media_tools)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.loc_media_tools_description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MediaDownloadOptions(preferences, onUpdate = { preferences = it })
        }
    }
}

/** 미디어 설정 화면과 메뉴를 공통 등록부에 제공하는 내장 플러그인입니다. */
object MediaSettingsPlugin : FeaturePlugin() {
    override val id = com.plugin.PluginId.MEDIA
    override val route = PluginRoutes.MEDIA_TOOLS
    override val titleResource = R.string.loc_media_tools
    override val icon get() = Icons.Rounded.Folder

    /** 영상 인코딩 설정 화면을 표시합니다. */
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        MediaToolsPage(onBack)
    }
}
