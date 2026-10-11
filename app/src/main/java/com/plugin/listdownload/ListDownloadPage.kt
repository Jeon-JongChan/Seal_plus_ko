package com.plugin.listdownload

import com.plugin.compatibility.copyPreservingPluginOptions
import com.plugin.compatibility.PluginPreferences
import com.plugin.PluginRoutes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.plugin.FeaturePlugin
import com.plugin.compatibility.CookieOptions
import com.plugin.compatibility.MediaDownloadOptions
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.util.DownloadUtil
import com.plugin.compatibility.MediaRules

import com.plugin.compatibility.*

/** 계정 목록과 일반 재생목록을 기존 조회·선택 다운로드 흐름으로 연결합니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDownloadPage(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(com.junkfood.seal.util.DownloadType.Video) }
    var preferences by remember { com.plugin.compatibility.pluginPreferencesState(com.plugin.compatibility.createPluginPreferences()) }
    val account = MediaRules.instagramAccount(url)
    val target = if (account != null) "https://www.instagram.com/$account/" else MediaRules.playlistUrl(url)
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.loc_list_downloads)) }, navigationIcon = { BackButton(onBack) })
    }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (account != null) R.string.loc_instagram_accounts else R.string.loc_list_auto_detect))
            com.plugin.compatibility.DownloadTypeSelectionGroup(
                typeEntries = listOf(com.junkfood.seal.util.DownloadType.Video, com.junkfood.seal.util.DownloadType.Audio),
                selectedType = type, onSelect = { type = it })
            Text(stringResource(R.string.format_selection))
            com.plugin.compatibility.DownloadFormatButton(type, preferences) { preferences = it }
            com.plugin.compatibility.AdditionalSettings(selectedType = type,
                preference = preferences, onPreferenceUpdate = { preferences = it }, instagram = true, showSubtitles = false)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(url, onValueChange = { url = it }, modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text(stringResource(R.string.loc_list_input)) },
                    isError = url.isNotBlank() && target == null)
                Button(modifier = Modifier.padding(start = 8.dp), enabled = target != null, onClick = {
                    if (account != null) PluginPreferences.updateInstagramAccounts(
                        PluginPreferences.getInstagramAccounts() + account)
                    onAction(DownloadDialogViewModel.Action.FetchPlaylist(target!!,
                        preferences.copyPreservingPluginOptions { copy(extractAudio = type == com.junkfood.seal.util.DownloadType.Audio) }
                            .copyPluginOptions(listProcessing = true,
                                mediaSelection = if (type == com.junkfood.seal.util.DownloadType.Audio) 1 else preferences.mediaSelection)))
                }) { Text(stringResource(R.string.loc_scan)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                PluginPreferences.getInstagramAccounts().sorted().forEach { saved ->
                    SuggestionChip(onClick = { url = saved }, label = { Text("@$saved") })
                }
            }
        }
    }
}

/** 인스타그램 계정과 yt-dlp 지원 재생목록을 함께 제공하는 내장 플러그인입니다. */
object ListDownloadPlugin : FeaturePlugin {
    // 기존 계정 화면 경로를 재사용해 저장된 탐색 경로와의 호환성을 유지합니다.
    override val id = com.plugin.PluginId.LIST
    override val route = PluginRoutes.INSTAGRAM
    override val titleResource = R.string.loc_list_downloads
    override val icon get() = Icons.Outlined.Subscriptions

    /** 선택한 목록 조회 요청을 호스트의 다운로드 흐름으로 전달합니다. */
    @Composable
    override fun Content(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        ListDownloadPage(onBack, onAction)
    }
}
