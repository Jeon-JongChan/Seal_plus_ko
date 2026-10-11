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

/** 인스타그램 프로필의 게시물을 조회·선택 다운로드 흐름으로 연결합니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListDownloadPage(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var preferences by remember { pluginPreferencesState(createPluginPreferences()
        .copyPreservingPluginOptions { copy(extractAudio = false, downloadPlaylist = false) }
        .copyPluginOptions(mediaSelection = 0)) }
    val account = MediaRules.instagramAccount(url)
    val target = account?.let { "https://www.instagram.com/$it/" }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.loc_list_downloads)) }, navigationIcon = { BackButton(onBack) })
    }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.loc_profile_download_hint))
            Text(stringResource(R.string.format_selection))
            com.plugin.compatibility.DownloadFormatButton(com.junkfood.seal.util.DownloadType.Video, preferences) { preferences = it }
            Text(stringResource(R.string.additional_settings))
            com.plugin.compatibility.AdditionalSettings(selectedType = com.junkfood.seal.util.DownloadType.Video,
                preference = preferences, onPreferenceUpdate = { preferences = it }, instagram = true, showSubtitles = false)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(url, onValueChange = { url = it }, modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text(stringResource(R.string.loc_list_input)) },
                    isError = url.isNotBlank() && target == null)
                Button(modifier = Modifier.padding(start = 8.dp), enabled = target != null, onClick = {
                    if (account != null) PluginPreferences.updateInstagramAccounts(
                        PluginPreferences.getInstagramAccounts() + account)
                    onAction(DownloadDialogViewModel.Action.FetchPlaylist(target!!,
                        preferences.copyPreservingPluginOptions { copy(extractAudio = false, downloadPlaylist = false) }
                            .copyPluginOptions(listProcessing = true, mediaSelection = 0)))
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

/** 일반 재생목록과 분리된 인스타그램 프로필 전용 내장 플러그인입니다. */
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
