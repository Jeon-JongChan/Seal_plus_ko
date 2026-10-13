package com.plugin.compatibility

import com.plugin.compatibility.PluginPreferences.IMAGE_RESOLUTION

import com.junkfood.seal.ui.page.downloadv2.configure.*

import com.plugin.compatibility.MediaRules
import com.plugin.compatibility.CookieOptions
import com.plugin.compatibility.MediaSettingsButton
import com.plugin.compatibility.DownloadAllChip
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NewLabel
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.HapticFeedback.longPressHapticFeedback
import com.junkfood.seal.ui.common.motion.materialSharedAxisX
import com.junkfood.seal.ui.component.ButtonChip
import com.junkfood.seal.ui.component.DrawerSheetSubtitle
import com.junkfood.seal.ui.component.OutlinedButtonWithIcon
import com.junkfood.seal.ui.component.SealModalBottomSheet
import com.junkfood.seal.ui.component.SealModalBottomSheetM2Variant
import com.junkfood.seal.ui.component.SingleChoiceChip
import com.junkfood.seal.ui.component.SingleChoiceSegmentedButton
import com.junkfood.seal.ui.component.VideoFilterChip
import com.junkfood.seal.ui.page.command.TemplatePickerDialog
import com.plugin.compatibility.ActionButton.Download
import com.plugin.compatibility.ActionButton.FetchInfo
import com.plugin.compatibility.ActionButton.StartTask
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState.Configure
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState.Error
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState.InputUrl
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState.Loading
import com.junkfood.seal.ui.page.settings.command.CommandTemplateDialog
import com.junkfood.seal.ui.page.settings.format.AudioQuickSettingsDialog
import com.junkfood.seal.ui.page.settings.format.VideoQuickSettingsDialog
import com.junkfood.seal.ui.page.settings.network.CookiesQuickSettingsDialog
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.AUDIO_CONVERSION_FORMAT
import com.junkfood.seal.util.AUDIO_CONVERT
import com.junkfood.seal.util.AUDIO_FORMAT
import com.junkfood.seal.util.AUDIO_QUALITY
import com.junkfood.seal.util.COOKIES
import com.junkfood.seal.util.CUSTOM_COMMAND
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.DownloadType.Audio
import com.junkfood.seal.util.DownloadType.Command
import com.junkfood.seal.util.DownloadType.Playlist
import com.junkfood.seal.util.DownloadType.Video
import com.junkfood.seal.util.DownloadType.entries
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FORMAT_SELECTION
import com.junkfood.seal.util.PreferenceStrings
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.SUBTITLE
import com.junkfood.seal.util.TEMPLATE_ID
import com.junkfood.seal.util.THUMBNAIL
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.USE_CUSTOM_AUDIO_PRESET
import com.junkfood.seal.util.VIDEO_FORMAT
import com.junkfood.seal.util.VIDEO_QUALITY
import kotlinx.coroutines.launch

@Composable
/** DownloadType.label의 독립 플러그인 처리를 제공합니다. */
private fun DownloadType.label(): String =
    stringResource(
        when (this) {
            Audio -> R.string.audio
            Video -> R.string.video
            Command -> R.string.commands
            Playlist -> R.string.playlist
        }
    )

/** 기존 영상·음원 형식 편집기를 일반·빠른·목록 다운로드에서 공유합니다. */
@Composable
private fun FormatPresetDialog(type: DownloadType?, preferences: DownloadUtil.DownloadPreferences,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit, onDismiss: () -> Unit) {
    if (type != Audio) {
        var res by remember(preferences) { mutableIntStateOf(preferences.videoResolution) }
        var format by remember(preferences) { mutableIntStateOf(preferences.videoFormat) }

        VideoQuickSettingsDialog(
            videoResolution = res,
            videoFormatPreference = format,
            onResolutionSelect = { res = it },
            onFormatSelect = { format = it },
            onDismissRequest = { onDismiss() },
            onSave = {
                VIDEO_FORMAT.updateInt(format)
                VIDEO_QUALITY.updateInt(res)
                onPreferencesUpdate(com.plugin.compatibility.createPluginPreferences().copyPluginOptions(selectedFolder = preferences.selectedFolder))
            },
        )
    }

    if (type == Audio) {
        var quality by remember(preferences) { mutableIntStateOf(preferences.audioQuality) }
        var customPreset by
            remember(preferences) { mutableStateOf(preferences.useCustomAudioPreset) }
        var conversionFmt by
            remember(preferences) { mutableIntStateOf(preferences.audioConvertFormat) }
        var convertAudio by remember(preferences) { mutableStateOf(preferences.convertAudio) }
        var preferredFormat by remember(preferences) { mutableIntStateOf(preferences.audioFormat) }

        AudioQuickSettingsDialog(
            modifier = Modifier,
            preferences = preferences,
            audioQuality = quality,
            onQualitySelect = { quality = it },
            useCustomAudioPreset = customPreset,
            onCustomPresetToggle = { customPreset = it },
            convertAudio = convertAudio,
            onConvertToggled = { convertAudio = it },
            conversionFormat = conversionFmt,
            onConversionSelect = { conversionFmt = it },
            preferredFormat = preferredFormat,
            onPreferredSelect = { preferredFormat = it },
            onDismissRequest = { onDismiss() },
            onSave = {
                AUDIO_QUALITY.updateInt(quality)
                USE_CUSTOM_AUDIO_PRESET.updateBoolean(customPreset)
                AUDIO_CONVERSION_FORMAT.updateInt(conversionFmt)
                AUDIO_CONVERT.updateBoolean(convertAudio)
                AUDIO_FORMAT.updateInt(preferredFormat)
                onPreferencesUpdate(com.plugin.compatibility.createPluginPreferences().copyPluginOptions(selectedFolder = preferences.selectedFolder))
            },
        )
    }
}

/** 형식 요약을 표시하고 기존 형식 편집기를 엽니다. */
@Composable
internal fun DownloadFormatButton(type: DownloadType?, preferences: DownloadUtil.DownloadPreferences,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    if (preferences.mediaSelection == 2) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(0, 1080, 720).forEach { resolution ->
                FilterChip(selected = preferences.imageResolution == resolution,
                    onClick = {
                        com.plugin.compatibility.PluginPreferences.IMAGE_RESOLUTION.updateInt(resolution)
                        onPreferencesUpdate(preferences.copyPluginOptions(imageResolution = resolution))
                    }, label = { Text(if (resolution == 0) stringResource(R.string.loc_original_resolution) else resolution.toString()) })
            }
        }
    } else {
        Preset(preference = preferences, downloadType = if (type == Playlist) Video else type,
            selected = true, showEditIcon = true, onEdit = { opened = true }, onClick = { opened = true })
    }
    DownloadAllChip(preferences, onPreferencesUpdate)
    if (opened) FormatPresetDialog(type, preferences, onPreferencesUpdate, onDismiss = { opened = false })
}


/** 쿠키·자막·썸네일·미디어 설정을 줄바꿈 없이 가로 스크롤로 제공합니다. */
@Composable
internal fun AdditionalSettings(
    modifier: Modifier = Modifier,
    selectedType: DownloadType?,
    preference: DownloadUtil.DownloadPreferences,
    onPreferenceUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
    instagram: Boolean = false,
    showSubtitles: Boolean = true,
) {
    with(preference) {
        Row(modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedType != Command) com.plugin.ui.DownloadFolderSelector(preference, onPreferenceUpdate)
            CookieOptions(preference, onUpdate = onPreferenceUpdate)
            if (showSubtitles) VideoFilterChip(
                selected = downloadSubtitle,
                enabled = selectedType != Command,
                onClick = {
                    SUBTITLE.updateBoolean(!downloadSubtitle)
                    onPreferenceUpdate(com.plugin.compatibility.createPluginPreferences().copyPluginOptions(selectedFolder = preference.selectedFolder))
                },
                label = stringResource(id = R.string.download_subtitles),
            )
            VideoFilterChip(
                selected = createThumbnail,
                enabled = selectedType != Command,
                onClick = {
                    THUMBNAIL.updateBoolean(!createThumbnail)
                    onPreferenceUpdate(com.plugin.compatibility.createPluginPreferences().copyPluginOptions(selectedFolder = preference.selectedFolder))
                },
                label = stringResource(R.string.create_thumbnail),
            )

            MediaSettingsButton(preference, onPreferenceUpdate, imageOnly = preference.mediaSelection == 2)
        }
    }
}

/** 설명은 접근성 읽기에 유지하고 형식 선택지를 한 줄 칩으로 표시합니다. */
@Composable
private fun SingleChoiceItem(
    modifier: Modifier = Modifier,
    title: String,
    desc: String,
    selected: Boolean,
    icon: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    FilterChip(
        modifier = modifier.padding(horizontal = 4.dp).semantics { contentDescription = if (title == desc) title else "$title, $desc" },
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(title, maxLines = 1) },
        leadingIcon = icon,
        trailingIcon = action,
    )
}

@Composable
/** Header의 독립 플러그인 처리를 제공합니다. */
internal fun Header(modifier: Modifier = Modifier, icon: ImageVector, title: String) {
    Column(modifier = modifier) {
        Icon(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            imageVector = icon,
            contentDescription = null,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier =
                Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
/** DownloadTypeSelectionGroup의 독립 플러그인 처리를 제공합니다. */
internal fun DownloadTypeSelectionGroup(
    modifier: Modifier = Modifier,
    typeEntries: List<DownloadType>,
    selectedType: DownloadType?,
    onSelect: (DownloadType) -> Unit,
) {
    val typeCount = typeEntries.size
    if (typeCount >= 4) {
        LazyRow(modifier = modifier) {
            items(typeEntries) { type ->
                SingleChoiceChip(
                    selected = selectedType == type,
                    label = type.label(),
                    onClick = { onSelect(type) },
                )
            }
        }
    } else {
        SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
            typeEntries.forEachIndexed { index, type ->
                SingleChoiceSegmentedButton(
                    selected = selectedType == type,
                    onClick = { onSelect(type) },
                    shape = SegmentedButtonDefaults.itemShape(index, typeCount),
                ) {
                    Text(text = type.label())
                }
            }
        }
    }
}

@Composable
/** Preset의 독립 플러그인 처리를 제공합니다. */
private fun Preset(
    modifier: Modifier = Modifier,
    preference: DownloadUtil.DownloadPreferences,
    downloadType: DownloadType?,
    selected: Boolean,
    showEditIcon: Boolean,
    onEdit: () -> Unit,
    onClick: () -> Unit,
) {
    val description =
        when (downloadType) {
            Audio -> {
                PreferenceStrings.getAudioPresetText(preference)
            }

            Video -> {
                PreferenceStrings.getVideoPresetText(preference)
            }

            Playlist -> stringResource(R.string.preset_format_selection_desc)
            else -> ""
        }

    SingleChoiceItem(
        modifier = modifier,
        title = description.ifBlank { stringResource(R.string.preset) },
        desc = description,
        icon = {
            Crossfade(selected, animationSpec = spring(stiffness = Spring.StiffnessMedium)) {
                if (it) {
                    Icon(
                        imageVector = Icons.Filled.SettingsSuggest,
                        null,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.SettingsSuggest,
                        null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        selected = selected,
        action = {
            Crossfade(showEditIcon, animationSpec = spring(stiffness = Spring.StiffnessMedium)) {
                if (it) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.edit),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        onClick = {
            if (showEditIcon) {
                onEdit()
            } else {
                onClick()
            }
        },
    )
}

@Composable
/** Custom의 독립 플러그인 처리를 제공합니다. */
private fun Custom(
    modifier: Modifier = Modifier,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    SingleChoiceItem(
        modifier = modifier,
        title = stringResource(R.string.loc_quality_pick),
        desc = stringResource(R.string.custom_format_selection_desc),
        icon = {
            Crossfade(selected, animationSpec = spring(stiffness = Spring.StiffnessMedium)) {
                if (it) {
                    Icon(
                        imageVector = Icons.Filled.VideoFile,
                        null,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.VideoFile,
                        null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        selected = selected,
        enabled = enabled,
        onClick = onClick,
    )
}

private enum class ActionButton {
    FetchInfo,
    Download,
    StartTask,
}

@Composable
/** ActionButton.Icon의 독립 플러그인 처리를 제공합니다. */
private fun ActionButton.Icon() {
    Icon(
        imageVector =
            when (this) {
                FetchInfo -> Icons.AutoMirrored.Filled.ArrowForward
                Download -> Icons.Outlined.FileDownload
                StartTask -> Icons.Filled.DownloadDone
            },
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
/** ActionButton.Label의 독립 플러그인 처리를 제공합니다. */
private fun ActionButton.Label() {
    Text(
        stringResource(
            when (this) {
                FetchInfo -> R.string.proceed
                Download -> R.string.download
                StartTask -> R.string.start
            }
        ),
        modifier = Modifier.padding(start = 8.dp),
    )
}

@Composable
/** ActionButtons의 독립 플러그인 처리를 제공합니다. */
private fun ActionButtons(
    modifier: Modifier = Modifier,
    canProceed: Boolean,
    selectedType: DownloadType?,
    useFormatSelection: Boolean,
    onCancel: () -> Unit,
    onFetchInfo: () -> Unit,
    onDownload: () -> Unit,
    onTaskStart: () -> Unit,
) {
    val action =
        if (selectedType == Command) {
            StartTask
        } else if (selectedType == Playlist || useFormatSelection) {
            FetchInfo
        } else {
            Download
        }

    val state = rememberLazyListState()
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.End,
        state = state,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            OutlinedButtonWithIcon(
                modifier = Modifier.padding(horizontal = 12.dp),
                onClick = onCancel,
                icon = Icons.Outlined.Cancel,
                text = stringResource(R.string.cancel),
            )
        }
        item {
            Button(
                modifier = Modifier,
                onClick = {
                    when (action) {
                        FetchInfo -> onFetchInfo()
                        Download -> onDownload()
                        StartTask -> onTaskStart()
                    }
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                enabled = canProceed,
            ) {
                AnimatedContent(
                    targetState = action,
                    label = "",
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(220, delayMillis = 90))).togetherWith(
                            fadeOut(animationSpec = tween(90))
                        )
                    },
                ) { action ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        action.Icon()
                        action.Label()
                    }
                }
            }
        }
    }
}


/** 추가 설정의 접힌 제목을 표시하는 원본 구성요소입니다. */

/** 이미지 유형과 해상도를 공유 데이터 설정에 반영합니다. */
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
