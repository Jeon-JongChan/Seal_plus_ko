package com.plugin.interaction

import com.plugin.compatibility.copyPreservingPluginOptions
import com.plugin.compatibility.*

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.DrawerSheetSubtitle
import com.junkfood.seal.ui.page.downloadv2.configure.*
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.DownloadType.*
import com.junkfood.seal.util.DownloadUtil
import com.plugin.PluginId
import com.plugin.PluginState

/** 앱 입력과 공유 입력에 같은 유형·쿠키·미디어 설정을 제공하며 실행 버튼은 고정합니다. */
@Composable
fun UnifiedInputPage(
    config: Config,
    preferences: DownloadUtil.DownloadPreferences,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
    onPresetEdit: (DownloadType?) -> Unit,
    onActionPost: (Action) -> Unit,
    initialUrls: List<String> = emptyList(),
) {
                var inputType by remember { mutableStateOf(config.downloadType ?: Video) }
                val inputFormatSelection = false
                EnhancedInputUrlPage(
                    initialUrls = initialUrls,
                    config = config,
                    onConfigUpdate = { Config.updatePreferences(newValue = it, oldValue = config) },
                    settings = {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            DrawerSheetSubtitle(text = stringResource(R.string.download_type))
                            DownloadTypeSelectionGroup(typeEntries = config.typeEntries, selectedType = inputType,
                                onSelect = { inputType = it })
                            DrawerSheetSubtitle(text = stringResource(R.string.format_selection))
                            DownloadFormatButton(inputType, preferences, onPreferencesUpdate)

                        }
                        AdditionalSettings(Modifier.padding(horizontal = 16.dp), inputType,
                            preferences, onPreferencesUpdate, instagram = PluginState.isEnabled(PluginId.SOCIAL))
                    },
                    onActionPost = { action ->
                        if (action is Action.ProceedWithURLs) {
                            // 기본 다운로드는 입력 화면에서 시작하고 명시적인 형식·명령 선택만 기존 설정을 엽니다.
                            Config.updatePreferences(config.copy(downloadType = inputType, useFormatSelection = inputFormatSelection), config)
                            val social = PluginState.isEnabled(PluginId.SOCIAL) && action.urlList.all { com.plugin.compatibility.MediaRules.usesSocialMediaApi(it, inputType == Audio) }
                            when {
                                PluginState.isEnabled(PluginId.SOCIAL) && action.urlList.any { com.plugin.compatibility.MediaRules.instagramAccount(it) != null } ->
                                    onActionPost(Action.FetchPlaylist(action.urlList.first(), preferences.copyPreservingPluginOptions { copy(extractAudio = inputType == Audio) }
                                        .copyPluginOptions(mediaSelection = when (inputType) { Video, Audio -> 1; else -> 0 })))
                                inputType == Command -> onActionPost(action)
                                (inputType == Playlist || com.plugin.compatibility.MediaRules.isPlaylistUrl(action.urlList.first())) && action.urlList.size == 1 ->
                                    onActionPost(Action.FetchPlaylist(action.urlList.first(), preferences))
                                inputFormatSelection && !social && action.urlList.size == 1 ->
                                    onActionPost(Action.FetchFormats(action.urlList.first(), inputType == Audio, preferences))
                                else -> onActionPost(Action.DownloadWithPreset(action.urlList,
                                    preferences.copyPreservingPluginOptions { copy(extractAudio = inputType == Audio) }
                                        .copyPluginOptions(mediaSelection = when (inputType) { Video, Audio -> 1; else -> 0 })))
                            }
                        } else onActionPost(action)
                    },
                )

}
