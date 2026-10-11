package com.plugin.image

import com.plugin.compatibility.MediaRules
import com.plugin.PluginRoutes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
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
import com.plugin.instagram.InstagramUtil
import com.plugin.compatibility.*

/** 분석한 혼합 게시물의 전체·영상·이미지 필터를 해당 선택창에만 적용합니다. */
@Composable
fun ImageMediaSelection(preferences: DownloadUtil.DownloadPreferences, onUpdate: (DownloadUtil.DownloadPreferences) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(R.string.loc_media_all, R.string.loc_media_videos, R.string.loc_media_images).forEachIndexed { index, title ->
            FilterChip(selected = preferences.mediaSelection == index, onClick = {
                onUpdate(preferences.copyPluginOptions(mediaSelection = index))
            }, label = { Text(stringResource(title), maxLines = 1) })
        }
    }
}

/** 미디어 URL과 자동 분석 옵션을 공통 다운로드 대기열에 연결합니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageDownloadPage(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var preferences by remember { com.plugin.compatibility.pluginPreferencesState(com.plugin.compatibility.createPluginPreferences().copy(extractAudio = false).copyPluginOptions(mediaSelection = 0)) }
    val valid = com.plugin.compatibility.MediaRules.playlistUrl(url) != null
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.loc_unified_download)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.loc_auto_media_hint))
            OutlinedTextField(url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.loc_media_url)) }, singleLine = true,
                isError = url.isNotBlank() && !valid,
                supportingText = { if (url.isNotBlank() && !valid) Text(stringResource(R.string.loc_instagram_invalid_url)) })
            CookieOptions(preferences, onUpdate = { preferences = it })
            if (com.plugin.PluginState.isEnabled(com.plugin.PluginId.MEDIA)) MediaDownloadOptions(preferences, onUpdate = { preferences = it }, instagram = InstagramUtil.accepts(url.trim()))
            Button(enabled = valid, onClick = {
                onAction(DownloadDialogViewModel.Action.DownloadWithPreset(listOf(url.trim()), preferences))
            }) { Text(stringResource(R.string.download)) }
        }
    }
}

/** 통합 미디어 다운로드 진입점을 기존 플러그인 메뉴와 탐색 그래프에 등록합니다. */
object ImageDownloadPlugin : FeaturePlugin {
    override val id = com.plugin.PluginId.SOCIAL
    override val route = PluginRoutes.IMAGE_DOWNLOAD
    override val titleResource = R.string.loc_unified_download
    override val icon get() = Icons.Outlined.FileDownload

    /** 호스트의 기존 다운로드 동작을 통합 미디어 화면으로 전달합니다. */
    @Composable
    override fun Content(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        ImageDownloadPage(onBack, onAction)
    }
}
