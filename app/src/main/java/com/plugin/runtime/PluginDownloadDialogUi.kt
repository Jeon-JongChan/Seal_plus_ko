package com.plugin.runtime

import com.plugin.compatibility.copyPreservingPluginOptions
import androidx.compose.runtime.referentialEqualityPolicy
import com.plugin.compatibility.*
import com.plugin.compatibility.PluginPreferences.IMAGE_RESOLUTION

import com.junkfood.seal.ui.page.downloadv2.configure.*
import com.junkfood.seal.ui.page.downloadv2.configure.Config

import com.plugin.compatibility.MediaRules
import com.plugin.runtime.PluginSelection
import com.plugin.image.ImageMediaSelection
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
import com.plugin.runtime.ActionButton.Download
import com.plugin.runtime.ActionButton.FetchInfo
import com.plugin.runtime.ActionButton.StartTask
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

val PreferencesMock = DownloadUtil.DownloadPreferences.EMPTY

@OptIn(ExperimentalMaterial3Api::class)
/** 다운로드 설정과 이미지·영상 선택 흐름을 공통 바텀 시트에 표시합니다. */
@Composable
fun PluginDownloadDialog(
    modifier: Modifier = Modifier,
    config: Config,
    sheetState: SheetState,
    preferences: DownloadUtil.DownloadPreferences,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
    state: Any = InputUrl,
    onActionPost: (Any) -> Unit = {},
) {
    var showVideoPresetDialog by remember { mutableStateOf(false) }
    var showAudioPresetDialog by remember { mutableStateOf(false) }

    SealModalBottomSheet(
        sheetState = sheetState,
        contentPadding = PaddingValues(),
        onDismissRequest = { onActionPost(Action.HideSheet) },
    ) {
        DownloadDialogContent(
            modifier = modifier,
            state = state,
            config = config,
            preferences = preferences,
            onPreferencesUpdate = onPreferencesUpdate,
            onPresetEdit = { type ->
                when (type) {
                    Audio -> showAudioPresetDialog = true

                    Video, Playlist -> showVideoPresetDialog = true

                    else -> {}
                }
            },
            onActionPost = onActionPost,
        )
    }

    if (showVideoPresetDialog || showAudioPresetDialog) FormatPresetDialog(
        if (showAudioPresetDialog) Audio else Video, preferences, onPreferencesUpdate,
        onDismiss = { showVideoPresetDialog = false; showAudioPresetDialog = false })
}

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
                onPreferencesUpdate(com.plugin.compatibility.createPluginPreferences())
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
                onPreferencesUpdate(com.plugin.compatibility.createPluginPreferences())
            },
        )
    }
}

/** 형식 요약을 표시하고 기존 형식 편집기를 엽니다. */
@Composable
internal fun DownloadFormatButton(type: DownloadType?, preferences: DownloadUtil.DownloadPreferences,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    Preset(preference = preferences, downloadType = if (type == Playlist) Video else type,
        selected = true, showEditIcon = true, onEdit = { opened = true }, onClick = { opened = true })
    if (opened) FormatPresetDialog(type, preferences, onPreferencesUpdate, onDismiss = { opened = false })
}

/** 오류 원인을 보여주고 변경한 쿠키 설정으로 인스타그램 요청을 재시도합니다. */
@Composable
private fun ErrorPage(modifier: Modifier = Modifier, state: Error, onActionPost: (Any) -> Unit) {
    val view = LocalView.current
    val clipboardManager = LocalClipboardManager.current
    var retryPreferences by remember(state) {
        mutableStateOf((state.action as? InspectInstagramAction)?.preferences, referentialEqualityPolicy())
    }
    val url =
        state.action.run {
            when (this) {
                is Action.FetchFormats -> url
                is Action.FetchPlaylist -> url
                is InspectInstagramAction -> urls.joinToString("\n")
                else -> ""
            }
        }
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.fetch_info_error_msg),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = state.throwable.message.toString(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.padding(vertical = 16.dp, horizontal = 20.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            maxLines = 20,
            overflow = TextOverflow.Clip,
        )

        retryPreferences?.let { preference ->
            Row(Modifier.padding(horizontal = 20.dp)) {
                CookieOptions(preference, onUpdate = { retryPreferences = it })
            }
        }
        Row(modifier = Modifier) {
            FilledTonalButton(onClick = {
                val action = state.action
                onActionPost(if (action is InspectInstagramAction)
                    action.copy(preferences = retryPreferences ?: action.preferences) else action)
            }) { Text(stringResource(R.string.loc_retry_action)) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    view.longPressHapticFeedback()
                    clipboardManager.setText(
                        AnnotatedString(
                            App.getVersionReport() + "\nURL: ${url}\n${state.throwable.message}"
                        )
                    )
                    ToastUtil.makeToast(R.string.error_copied)
                }
            ) {
                Text(stringResource(R.string.copy_error_report))
            }
        }
    }
}

/** 다운로드 상태에 맞는 URL 입력·설정·목록·오류 화면을 표시합니다. */
@Composable
private fun DownloadDialogContent(
    modifier: Modifier = Modifier,
    state: Any,
    config: Config,
    preferences: DownloadUtil.DownloadPreferences,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
    onPresetEdit: (DownloadType?) -> Unit,
    onActionPost: (Any) -> Unit,
) {
    AnimatedContent(
        modifier = modifier,
        targetState = state,
        label = "",
        transitionSpec = {
            materialSharedAxisX(initialOffsetX = { it / 4 }, targetOffsetX = { -it / 4 })
        },
    ) { state ->
        when (state) {
            is Configure -> {
                if ((com.plugin.PluginState.isEnabled(com.plugin.PluginId.INTERACTION) ||
                    com.plugin.PluginState.isEnabled(com.plugin.PluginId.SOCIAL)) && config.downloadType != Command) {
                    com.plugin.interaction.UnifiedInputPage(config, preferences, onPreferencesUpdate, onPresetEdit, onActionPost, state.urlList)
                } else if (state.urlList.size == 1) {
                    ConfigurePage(url = state.urlList.first(), config = config, preferences = preferences,
                        onPresetEdit = onPresetEdit, onPreferencesUpdate = onPreferencesUpdate, onConfigSave = { Config.updatePreferences(it, config) },
                        settingChips = { AdditionalSettings(selectedType = config.downloadType, preference = preferences,
                            onPreferenceUpdate = onPreferencesUpdate) }, onActionPost = { onActionPost(it) })
                } else {
                    ConfigurePagePlaylistVariant(initialDownloadType = config.downloadType ?: Video,
                        preferences = preferences, onPreferencesUpdate = onPreferencesUpdate, onPresetEdit = onPresetEdit,
                        onDismissRequest = { onActionPost(Action.HideSheet) }) { type ->
                        onActionPost(Action.DownloadWithPreset(state.urlList, preferences.copyPreservingPluginOptions { copy(extractAudio = type == Audio) }))
                    }
                }
            }

            is InstagramSelectionState -> {
                PluginSelection(state, onActionPost)
            }

            is Error -> {
                ErrorPage(state = state, onActionPost = { onActionPost(it) })
            }

            is Loading -> {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 120.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }

            InputUrl -> {
                if (com.plugin.PluginState.isEnabled(com.plugin.PluginId.INTERACTION) ||
                    com.plugin.PluginState.isEnabled(com.plugin.PluginId.SOCIAL)) {
                    com.plugin.interaction.UnifiedInputPage(config, preferences, onPreferencesUpdate, onPresetEdit, onActionPost)
                } else InputUrlPage(config = config, onConfigUpdate = { Config.updatePreferences(it, config) }, onActionPost = { onActionPost(it) })
            }

            else -> {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
/** ErrorPreview의 독립 플러그인 처리를 제공합니다. */
private fun ErrorPreview() {
    SealModalBottomSheet(
        onDismissRequest = {},
        sheetState =
            with(LocalDensity.current) {
                SheetState(
                    initialValue = SheetValue.Expanded,
                    skipPartiallyExpanded = true,
                    velocityThreshold = { 56.dp.toPx() },
                    positionalThreshold = { 125.dp.toPx() },
                )
            },
    ) {
        ErrorPage(
            state =
                Error(
                    action =
                        Action.FetchFormats(
                            url = "",
                            audioOnly = true,
                            preferences = PreferencesMock,
                        ),
                    throwable = Exception("Not good"),
                ),
            onActionPost = {},
        )
    }
}

@Composable
/** FormatPage의 독립 플러그인 처리를 제공합니다. */
fun FormatPage(
    modifier: Modifier = Modifier,
    state: SelectionState.FormatSelection,
    onDismissRequest: () -> Unit,
) {
    val sheetState =
        androidx.compose.material.rememberModalBottomSheetState(
            initialValue = ModalBottomSheetValue.Hidden,
            skipHalfExpanded = true,
        )

    LaunchedEffect(state) { sheetState.show() }
    val scope = rememberCoroutineScope()
    BackHandler { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() } }

    SealModalBottomSheetM2Variant(sheetState = sheetState, sheetGesturesEnabled = false) {
        FormatPage(
            modifier = modifier,
            videoInfo = state.info,
            onNavigateBack = {
                scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/*@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Light", uiMode = Configuration.UI_MODE_NIGHT_NO)*/
@Composable
/** ConfigurePagePreview의 독립 플러그인 처리를 제공합니다. */
private fun ConfigurePagePreview() {
    SealTheme() {
        SealModalBottomSheet(
            sheetState =
                with(LocalDensity.current) {
                    SheetState(
                        initialValue = SheetValue.Expanded,
                        skipPartiallyExpanded = true,
                        velocityThreshold = { 56.dp.toPx() },
                        positionalThreshold = { 125.dp.toPx() },
                    )
                },
            onDismissRequest = {},
            contentPadding = PaddingValues(),
        ) {
            ConfigurePage(
                config =
                    Config(
                        downloadType = Audio,
                        useFormatSelection = true,
                        typeEntries = entries - Command,
                    ),
                preferences = PreferencesMock,
                onConfigSave = {},
                settingChips = {},
            ) {}
        }
    }
}

/** 원본의 유형·형식 직접 선택과 접을 수 있는 추가 설정을 제공합니다. */
@Composable
private fun ConfigurePage(
    modifier: Modifier = Modifier,
    url: String = "",
    config: Config,
    preferences: DownloadUtil.DownloadPreferences,
    settingChips: @Composable () -> Unit,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit = {},
    onPresetEdit: (DownloadType?) -> Unit = {},
    onConfigSave: (Config) -> Unit,
    onActionPost: (Action) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedType by remember(config) { mutableStateOf(config.downloadType) }
    var useFormatSelection by remember(config) { mutableStateOf(config.useFormatSelection) }
    val canProceed = selectedType in config.typeEntries

    var showTemplateSelectionDialog by remember { mutableStateOf(false) }
    var showTemplateCreatorDialog by remember { mutableStateOf(false) }
    var showTemplateEditorDialog by remember { mutableStateOf(false) }
    val template by
        remember(showTemplateCreatorDialog, showTemplateSelectionDialog, showTemplateEditorDialog) {
            mutableStateOf(PreferenceUtil.getTemplate())
        }

    LaunchedEffect(selectedType) {
        if (selectedType == Playlist) {
            useFormatSelection = false
        }
    }

    Column {
        Column(modifier = modifier.padding(horizontal = 20.dp)) {
            Header(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                title = stringResource(R.string.settings_before_download),
                icon = Icons.Outlined.DoneAll,
            )
            DrawerSheetSubtitle(text = stringResource(id = R.string.download_type))
            DownloadTypeSelectionGroup(
                typeEntries = config.typeEntries,
                selectedType = selectedType,
                onSelect = { selectedType = it },
            )
            Column(modifier = Modifier.animateContentSize()) {
                if (selectedType != Command) {
                    DrawerSheetSubtitle(
                        text = stringResource(id = R.string.format_selection),
                        modifier = Modifier,
                    )
                    Preset(
                        modifier = Modifier,
                        preference = preferences,
                        selected = !useFormatSelection,
                        downloadType = selectedType,
                        onClick = { useFormatSelection = false },
                        showEditIcon = !useFormatSelection && selectedType != Playlist,
                        onEdit = { onPresetEdit(selectedType) },
                    )
                    Custom(
                        selected = useFormatSelection,
                        enabled = selectedType != Playlist,
                        onClick = { useFormatSelection = true },
                    )
                } else {
                    if (showTemplateSelectionDialog) {
                        TemplatePickerDialog { showTemplateSelectionDialog = false }
                    }
                    if (showTemplateCreatorDialog) {
                        CommandTemplateDialog(
                            onDismissRequest = { showTemplateCreatorDialog = false },
                            confirmationCallback = { scope.launch { TEMPLATE_ID.updateInt(it) } },
                        )
                    }
                    if (showTemplateEditorDialog) {
                        CommandTemplateDialog(
                            commandTemplate = template,
                            onDismissRequest = { showTemplateEditorDialog = false },
                        )
                    }
                    DrawerSheetSubtitle(
                        text = stringResource(id = R.string.template_selection),
                        modifier = Modifier,
                    )
                    LazyRow(modifier = Modifier) {
                        item {
                            ButtonChip(
                                icon = Icons.Outlined.Code,
                                label = template.name,
                                onClick = { showTemplateSelectionDialog = true },
                            )
                        }
                        item {
                            ButtonChip(
                                icon = Icons.Outlined.NewLabel,
                                label = stringResource(id = R.string.new_template),
                                onClick = { showTemplateCreatorDialog = true },
                            )
                        }
                        item {
                            ButtonChip(
                                icon = Icons.Outlined.Edit,
                                label = stringResource(id = R.string.edit_template, template.name),
                                onClick = { showTemplateEditorDialog = true },
                            )
                        }
                    }
                }
            }
        }
        var expanded by remember { mutableStateOf(false) }
        ExpandableTitle(expanded = expanded, onClick = { expanded = true }) { settingChips() }

        ActionButtons(
            modifier = Modifier.padding(horizontal = 20.dp),
            canProceed = canProceed,
            selectedType = selectedType,
            useFormatSelection = useFormatSelection,
            onCancel = { onActionPost(Action.HideSheet) },
            onDownload = {
                onConfigSave(
                    config.copy(
                        useFormatSelection = useFormatSelection,
                        downloadType = selectedType,
                    )
                )
                onActionPost(
                    Action.DownloadWithPreset(
                        urlList = listOf(url),
                        preferences = preferences.copyPreservingPluginOptions { copy(extractAudio = selectedType == Audio) }
                            .copyPluginOptions(mediaSelection = if (selectedType == Video) 1 else 0),
                    )
                )
            },
            onFetchInfo = {
                onConfigSave(
                    config.copy(
                        useFormatSelection = useFormatSelection,
                        downloadType = selectedType,
                    )
                )
                if (selectedType == Playlist) {
                    onActionPost(Action.FetchPlaylist(url = url, preferences = preferences))
                } else {
                    onActionPost(
                        Action.FetchFormats(
                            url = url,
                            audioOnly = selectedType == Audio,
                            preferences = preferences,
                        )
                    )
                }
            },
            onTaskStart = {
                onConfigSave(
                    config.copy(
                        useFormatSelection = useFormatSelection,
                        downloadType = selectedType,
                    )
                )
                onActionPost(
                    Action.RunCommand(url = url, template = template, preferences = preferences)
                )
            },
        )
    }
}

/** 여러 링크에서 공통 다운로드 유형·미디어 추가 설정을 선택합니다. */
@Composable
fun ConfigurePagePlaylistVariant(
    modifier: Modifier = Modifier,
    initialDownloadType: DownloadType,
    preferences: DownloadUtil.DownloadPreferences,
    onPreferencesUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
    onPresetEdit: (DownloadType?) -> Unit = {},
    onDismissRequest: () -> Unit,
    onDownload: (DownloadType) -> Unit,
) {

    var selectedType by remember(initialDownloadType) { mutableStateOf(initialDownloadType) }

    Column {
        Column(modifier = modifier.padding(horizontal = 20.dp)) {
            Header(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                title = stringResource(R.string.settings_before_download),
                icon = Icons.Outlined.DoneAll,
            )
            DrawerSheetSubtitle(text = stringResource(id = R.string.download_type))
            DownloadTypeSelectionGroup(
                typeEntries = listOf(Video, Audio),
                selectedType = selectedType,
                onSelect = { selectedType = it },
            )
            DrawerSheetSubtitle(
                text = stringResource(id = R.string.format_selection),
                modifier = Modifier,
            )
            Preset(
                modifier = Modifier,
                preference = preferences,
                selected = true,
                downloadType = selectedType,
                onClick = { onPresetEdit(selectedType) },
                showEditIcon = true,
                onEdit = { onPresetEdit(selectedType) },
            )
        }
        DrawerSheetSubtitle(text = stringResource(R.string.additional_settings), modifier = Modifier.padding(horizontal = 20.dp))
        AdditionalSettings(
            modifier = Modifier.padding(horizontal = 16.dp),
            preference = preferences,
            selectedType = selectedType,
            onPreferenceUpdate = onPreferencesUpdate,
        )

        ActionButtons(
            modifier = Modifier.padding(horizontal = 20.dp),
            canProceed = true,
            selectedType = selectedType,
            useFormatSelection = false,
            onCancel = onDismissRequest,
            onDownload = {
                onDownload(initialDownloadType)
                onDismissRequest()
            },
            onFetchInfo = { throw IllegalStateException() },
            onTaskStart = { throw IllegalStateException() },
        )
    }
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
            CookieOptions(preference, onUpdate = onPreferenceUpdate)
            DownloadAllChip(preference, onPreferenceUpdate)
            if (showSubtitles) VideoFilterChip(
                selected = downloadSubtitle,
                enabled = selectedType != Command,
                onClick = {
                    SUBTITLE.updateBoolean(!downloadSubtitle)
                    onPreferenceUpdate(com.plugin.compatibility.createPluginPreferences())
                },
                label = stringResource(id = R.string.download_subtitles),
            )
            VideoFilterChip(
                selected = createThumbnail,
                enabled = selectedType != Command,
                onClick = {
                    THUMBNAIL.updateBoolean(!createThumbnail)
                    onPreferenceUpdate(com.plugin.compatibility.createPluginPreferences())
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
@Composable
fun ExpandableTitle(
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column {
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = Dp.Hairline, modifier = Modifier.padding(horizontal = 20.dp))
        Column(
            modifier =
                modifier
                    .clickable(
                        onClick = onClick,
                        onClickLabel = stringResource(R.string.show_more_actions),
                        enabled = !expanded,
                    )
                    .padding(top = 12.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(24.dp))
                Text(
                    text = stringResource(R.string.additional_settings),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!expanded) {
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(32.dp))
                }
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
        }
    }
}
