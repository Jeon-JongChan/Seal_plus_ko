package com.plugin.interaction

import com.plugin.compatibility.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.page.downloadv2.configure.*
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.DownloadType.*
import com.junkfood.seal.util.DownloadUtil
import com.plugin.PluginId
import androidx.compose.runtime.saveable.rememberSaveable
import com.plugin.PluginState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateString

/** 앱 입력과 공유 입력에 같은 유형·쿠키·미디어 설정을 제공하며 실행 버튼은 고정합니다. */
@Composable
fun UnifiedInputPage(
    config: Config,
    preferences: DownloadUtil.DownloadPreferences,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
    onPresetEdit: (DownloadType?) -> Unit,
    onActionPost: (Any) -> Unit,
    initialUrls: List<String> = emptyList(),
) {
    var kind by rememberSaveable { mutableStateOf(runCatching {
        PluginDownloadKind.valueOf("plugin_download_kind".getString("Auto"))
    }.getOrDefault(PluginDownloadKind.Auto)) }
    val imageAvailable = PluginState.isEnabled(PluginId.SOCIAL)
    val activeKind = if (kind == PluginDownloadKind.Image && !imageAvailable) PluginDownloadKind.Auto else kind
    val inputType = activeKind.formatType
    EnhancedInputUrlPage(
        initialUrls = initialUrls,
        config = config,
        onConfigUpdate = { Config.updatePreferences(it, config) },
        settings = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.download_type), Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PluginDownloadKind.entries.forEach { item ->
                        val label = when (item) {
                            PluginDownloadKind.Auto -> R.string.revision5_auto
                            PluginDownloadKind.Video -> R.string.revision5_video
                            PluginDownloadKind.Image -> R.string.revision5_image
                            PluginDownloadKind.Playlist -> R.string.revision5_playlist
                            PluginDownloadKind.Audio -> R.string.revision5_audio
                        }
                        FilterChip(selected = activeKind == item, onClick = {
                            kind = item
                            "plugin_download_kind".updateString(item.name)
                        }, enabled = item != PluginDownloadKind.Image || imageAvailable,
                            label = { Text(stringResource(label)) })
                    }
                }
                Text(stringResource(R.string.format_selection), Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                DownloadFormatButton(inputType, activeKind.preferences(preferences), onPreferencesUpdate)
                Text(stringResource(R.string.additional_settings), Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            }
            AdditionalSettings(Modifier.padding(horizontal = 16.dp), inputType,
                activeKind.preferences(preferences), onPreferencesUpdate,
                instagram = PluginState.isEnabled(PluginId.SOCIAL))
        },
        onActionPost = { action ->
            if (action is Action.ProceedWithURLs && action.urlList.isNotEmpty()) {
                val requested = activeKind.preferences(preferences, MediaRules.isPlaylistUrl(action.urlList.first()))
                Config.updatePreferences(config.copy(downloadType = inputType, useFormatSelection = false), config)
                if (activeKind == PluginDownloadKind.Image) {
                    onActionPost(InspectInstagramAction(action.urlList.first(), requested, action.urlList))
                } else if ((activeKind == PluginDownloadKind.Playlist && action.urlList.size == 1) ||
                    (activeKind == PluginDownloadKind.Auto && MediaRules.isPlaylistUrl(action.urlList.first())) ||
                    (PluginState.isEnabled(PluginId.SOCIAL) && action.urlList.any { MediaRules.instagramAccount(it) != null })) {
                    onActionPost(Action.FetchPlaylist(action.urlList.first(), requested))
                } else onActionPost(Action.DownloadWithPreset(action.urlList, requested))
            } else onActionPost(action)
        },
    )
}
