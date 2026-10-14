package com.plugin.compatibility

import com.plugin.compatibility.copyPreservingPluginOptions
import com.plugin.compatibility.PluginPreferences.QUICK_COOKIE_PROFILE
import com.plugin.compatibility.PluginPreferences.DOWNLOAD_ALL_MEDIA
import com.plugin.compatibility.PluginPreferences.IMAGE_FOLDER_NAME
import com.plugin.compatibility.PluginPreferences.VIDEO_FOLDER_NAME
import com.plugin.compatibility.PluginPreferences.IMAGE_TIERS
import com.plugin.compatibility.PluginPreferences.VIDEO_TIERS
import com.plugin.compatibility.PluginPreferences.IMAGE_RESOLUTION
import com.plugin.compatibility.PluginPreferences.CLASSIFY_MEDIA
import com.plugin.compatibility.PluginPreferences.QUALITY_THRESHOLD
import com.plugin.compatibility.PluginPreferences.TRANSCODE_AV1
import com.plugin.compatibility.PluginPreferences.KEEP_AV1_ORIGINAL
import com.plugin.compatibility.PluginPreferences.VIDEO_CONTAINER
import com.plugin.compatibility.PluginPreferences.AV1_CRF
import com.plugin.compatibility.PluginPreferences.AV1_PRESET
import com.plugin.compatibility.PluginPreferences.CLASSIFICATION_RULES

import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.junkfood.seal.ui.component.VideoFilterChip
import com.junkfood.seal.ui.page.settings.network.CookiesQuickSettingsDialog
import com.junkfood.seal.util.*
import com.junkfood.seal.util.FileUtil.getCookiesFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.updateString

/** 미디어 옵션을 펼쳐 표시하고 컨테이너 선택은 AV1 변환을 켰을 때만 제공합니다. */
@Composable
fun MediaDownloadOptions(
    preferences: DownloadUtil.DownloadPreferences,
    onUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
    instagram: Boolean = false,
    compact: Boolean = false,
    imageOnly: Boolean = false,
) {
    if (!com.plugin.PluginState.isEnabled(com.plugin.PluginId.MEDIA)) return
    var speedDialog by remember { mutableStateOf(false) }
    var speedInput by remember { mutableStateOf("") }
    var crf by remember(preferences.av1Crf) { mutableFloatStateOf(preferences.av1Crf.toFloat()) }
    val save: (DownloadUtil.DownloadPreferences) -> Unit = { value ->
        DOWNLOAD_ALL_MEDIA.updateBoolean(value.downloadAllMedia)
        IMAGE_RESOLUTION.updateInt(value.imageResolution)
        QUALITY_THRESHOLD.updateInt(value.qualityThresholdMbps)
        TRANSCODE_AV1.updateBoolean(value.transcodeAv1)
        KEEP_AV1_ORIGINAL.updateBoolean(value.keepAv1Original)
        AV1_CRF.updateInt(value.av1Crf)
        AV1_PRESET.updateInt(MediaRules.av1Preset(value.av1Preset))
        VIDEO_QUALITY.updateInt(value.videoResolution)
        VIDEO_CONTAINER.updateString(value.videoContainer)
        MERGE_OUTPUT_MKV.updateBoolean(value.mergeToMkv)
        onUpdate(value)
    }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!imageOnly && preferences.mediaSelection != 2) {
                        Text(stringResource(R.string.loc_video_resolution), style = MaterialTheme.typography.titleSmall)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(0 to stringResource(R.string.loc_original_resolution), 3 to "1080", 4 to "720", 6 to "360").forEach {
                                (quality, label) ->
                                FilterChip(
                                    preferences.videoResolution == quality,
                                    onClick = { save(preferences.copyPreservingPluginOptions { copy(videoResolution = quality) }) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    if (instagram && com.plugin.PluginState.isEnabled(com.plugin.PluginId.SOCIAL)) {
                    Text(stringResource(R.string.loc_image_resolution))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to stringResource(R.string.loc_original_resolution), 1080 to "1080", 720 to "720").forEach { (height, label) ->
                            FilterChip(
                                preferences.imageResolution == height,
                                onClick = { save(preferences.copyPluginOptions(imageResolution = height)) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(stringResource(R.string.loc_image_resolution_hint), style = MaterialTheme.typography.bodySmall)
                    }

                    if (!imageOnly && preferences.mediaSelection != 2) {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.loc_av1_conversion), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall)
                        Switch(
                            preferences.transcodeAv1,
                            onCheckedChange = { save(preferences.copyPluginOptions(transcodeAv1 = it)) },
                        )
                    }

                    if (preferences.transcodeAv1) {
                    Text(stringResource(R.string.loc_video_container), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("mp4", "mkv").forEach { container ->
                            FilterChip(selected = preferences.videoContainer == container,
                                onClick = { save(preferences.copyPreservingPluginOptions { copy(mergeToMkv = false) }.copyPluginOptions(videoContainer = container)) },
                                label = { Text(container.uppercase()) })
                        }
                    }

                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!preferences.keepAv1Original,
                                onClick = { save(preferences.copyPluginOptions(keepAv1Original = false)) },
                                label = { Text(stringResource(R.string.loc_replace_original)) })
                            FilterChip(preferences.keepAv1Original,
                                onClick = { save(preferences.copyPluginOptions(keepAv1Original = true)) },
                                label = { Text(stringResource(R.string.loc_keep_original)) })
                        }
                        Text(if (preferences.keepAv1Original) stringResource(R.string.loc_keep_original_hint)
                            else stringResource(R.string.loc_replace_original_hint), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.loc_av1_quality, crf.toInt()), style = MaterialTheme.typography.titleSmall)
                        Text("지원 기기는 하드웨어를 우선 사용합니다. 하드웨어 CQ 품질은 선택한 CRF의 상대 수준으로 적용하며, 지원되지 않으면 소프트웨어를 사용합니다.",
                            style = MaterialTheme.typography.bodySmall)
                        Slider(
                            crf,
                            onValueChange = { crf = it },
                            onValueChangeFinished = { save(preferences.copyPluginOptions(av1Crf = crf.toInt().coerceIn(20, 50))) },
                            valueRange = 20f..50f,
                            steps = 29,
                        )
                        HorizontalDivider()
                        Text(stringResource(R.string.loc_av1_speed, preferences.av1Preset), style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            listOf(10 to R.string.loc_av1_fast, 8 to R.string.loc_av1_balanced,
                                6 to R.string.loc_av1_compression).forEach { (preset, label) ->
                                FilterChip(selected = preferences.av1Preset == preset,
                                    onClick = { save(preferences.copyPluginOptions(av1Preset = preset)) },
                                    label = { Text(stringResource(label)) })
                            }
                            TextButton(onClick = {
                                speedInput = preferences.av1Preset.toString()
                                speedDialog = true
                            }) { Text(stringResource(R.string.loc_av1_speed_details)) }
                        }
                    }
                    }
                }
    if (speedDialog) {
        val preset = speedInput.toIntOrNull()?.takeIf { it in 0..12 }
        AlertDialog(onDismissRequest = { speedDialog = false },
            title = { Text(stringResource(R.string.loc_av1_speed_details)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.loc_av1_speed_hint))
                    OutlinedTextField(value = speedInput, onValueChange = { speedInput = it },
                        singleLine = true, isError = preset == null,
                        label = { Text(stringResource(R.string.loc_av1_speed_input)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(enabled = preset != null, onClick = {
                    // 숫자가 유효할 때만 적용하며 화질(CRF)은 변경하지 않습니다.
                    preset?.let { save(preferences.copyPluginOptions(av1Preset = it)); speedDialog = false }
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { speedDialog = false }) { Text(stringResource(R.string.cancel)) }
            })
    }
}

/** 다운로드 화면에서는 버튼만 표시하고 인코딩 설정은 스크롤 가능한 팝업으로 엽니다. */
@Composable
fun MediaSettingsButton(preferences: DownloadUtil.DownloadPreferences,
    onUpdate: (DownloadUtil.DownloadPreferences) -> Unit, imageOnly: Boolean = false) {
    if (!com.plugin.PluginState.isEnabled(com.plugin.PluginId.MEDIA)) return
    var opened by remember { mutableStateOf(false) }
    FilterChip(selected = preferences.transcodeAv1, onClick = { opened = true },
        label = { Text(stringResource(R.string.revision5_encoding)) })
    if (opened) AlertDialog(onDismissRequest = { opened = false },
        title = { Text(stringResource(R.string.loc_media_tools)) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                MediaDownloadOptions(preferences, onUpdate, instagram = true, imageOnly = imageOnly)
            }
        }, confirmButton = {
            TextButton(onClick = { opened = false }) { Text(stringResource(R.string.close)) }
        })
}

/** 모두 다운로드 선택을 공통 저장하고 여러 페이지 선택창의 생략 여부에 사용합니다. */
@Composable
fun DownloadAllChip(preferences: DownloadUtil.DownloadPreferences, onUpdate: (DownloadUtil.DownloadPreferences) -> Unit) {
    if (!(com.plugin.PluginState.isEnabled(com.plugin.PluginId.SOCIAL) || com.plugin.PluginState.isEnabled(com.plugin.PluginId.LIST))) return
    FilterChip(selected = preferences.downloadAllMedia, onClick = {
        DOWNLOAD_ALL_MEDIA.updateBoolean(!preferences.downloadAllMedia)
        onUpdate(preferences.copyPluginOptions(downloadAllMedia = !preferences.downloadAllMedia))
    }, label = { Text(stringResource(R.string.loc_download_all_media)) })
}

/** 미디어 폴더·파일 관리에서 저장 폴더와 이미지·영상 해상도 구간을 설정합니다. */
@Composable
fun MediaDirectoryOptions(preferences: DownloadUtil.DownloadPreferences,
    onUpdate: (DownloadUtil.DownloadPreferences) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var json by remember { mutableStateOf("") }
    /** 입력 중 잘못된 값은 표시만 하고 유효한 값만 저장합니다. */
    @Composable
    fun Entry(key: String, label: Int, initial: String, valid: (String) -> Boolean,
        update: (String) -> DownloadUtil.DownloadPreferences) {
        var text by remember(key) { mutableStateOf(initial) }
        OutlinedTextField(text, onValueChange = { value ->
            text = value
            if (valid(value)) { key.updateString(value); onUpdate(update(value)) }
        }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            label = { Text(stringResource(label)) }, isError = !valid(text))
    }
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.loc_media_directory_settings), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.loc_media_directory_hint))
        Entry(IMAGE_FOLDER_NAME, R.string.loc_image_folder_name, preferences.imageFolderName,
            { it.isEmpty() || MediaRules.folderName(it, "").isNotEmpty() }, { preferences.copyPluginOptions(imageFolderName = it) })
        Entry(VIDEO_FOLDER_NAME, R.string.loc_video_folder_name, preferences.videoFolderName,
            { it.isEmpty() || MediaRules.folderName(it, "").isNotEmpty() }, { preferences.copyPluginOptions(videoFolderName = it) })
        Text(stringResource(R.string.revision5_empty_folder), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.website_folder_target), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to R.string.revision6_classification_both, 2 to R.string.loc_media_images,
                1 to R.string.loc_media_videos).forEach { (target, label) ->
                FilterChip(selected = preferences.websiteFolderTarget == target, onClick = {
                    PluginPreferences.WEBSITE_FOLDER_TARGET.updateInt(target)
                    onUpdate(preferences.copyPluginOptions(websiteFolderTarget = target))
                }, label = { Text(stringResource(label)) })
            }
        }
        Text(stringResource(R.string.website_folder_target_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.loc_classify_downloads), Modifier.weight(1f))
            Switch(preferences.classifyMedia, onCheckedChange = {
                CLASSIFY_MEDIA.updateBoolean(it); onUpdate(preferences.copyPluginOptions(classifyMedia = it))
            })
        }
        if (preferences.classifyMedia) {
        Text(stringResource(R.string.classification_priority), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to R.string.classification_lower_first, true to R.string.classification_upper_first).forEach { (upper, label) ->
                FilterChip(selected = preferences.classificationUpperFirst == upper, onClick = {
                    PluginPreferences.CLASSIFICATION_UPPER_FIRST.updateBoolean(upper)
                    onUpdate(preferences.copyPluginOptions(classificationUpperFirst = upper))
                }, label = { Text(stringResource(label)) })
            }
        }
        Text(stringResource(R.string.classification_priority_hint), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.revision6_classification_target), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.revision6_classification_both, R.string.loc_media_videos, R.string.loc_media_images).forEachIndexed { index, label ->
                FilterChip(selected = preferences.classificationTarget == index, onClick = {
                    PluginPreferences.CLASSIFICATION_TARGET.updateInt(index)
                    onUpdate(preferences.copyPluginOptions(classificationTarget = index))
                }, label = { Text(stringResource(label)) })
            }
        }
        Text(stringResource(R.string.revision6_classification_target_hint), style = MaterialTheme.typography.bodySmall)
        if (preferences.classificationTarget != 1) {
        Text(stringResource(R.string.loc_image_resolution), style = MaterialTheme.typography.titleSmall)
        MediaRules.imageTiers(preferences.imageTiers).orEmpty().forEach { (short, long) ->
            val tier = "${short}x${long}"
            val disabled = MediaRules.disabledTiers(preferences.imageTiers)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(tier, Modifier.weight(1f))
                Switch(tier !in disabled, enabled = preferences.classifyMedia, onCheckedChange = { checked ->
                    val value = (if (checked) disabled - tier else disabled + tier).sorted().joinToString(",")
                    MediaRules.DISABLED_IMAGE_TIERS.updateString(value)
                    onUpdate(preferences.copyPluginOptions(imageTiers = MediaRules.withDisabledTiers(preferences.imageTiers, value)))
                })
            }
        }
        }
        if (preferences.classificationTarget != 2) {
        Text(stringResource(R.string.loc_video_resolution), style = MaterialTheme.typography.titleSmall)
        MediaRules.videoTiers(preferences.videoTiers).orEmpty().forEach { (height, mbps30, mbps60) ->
            val tier = "${height}p"
            val disabled = MediaRules.disabledTiers(preferences.videoTiers)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tier)
                    Text("30 fps: $mbps30 MB/s · 60 fps: $mbps60 MB/s", style = MaterialTheme.typography.bodySmall)
                }
                Switch(tier !in disabled, enabled = preferences.classifyMedia, onCheckedChange = { checked ->
                    val value = (if (checked) disabled - tier else disabled + tier).sorted().joinToString(",")
                    MediaRules.DISABLED_VIDEO_TIERS.updateString(value)
                    onUpdate(preferences.copyPluginOptions(videoTiers = MediaRules.withDisabledTiers(preferences.videoTiers, value)))
                })
            }
        }
        }
        Text(stringResource(R.string.revision5_disabled_tier), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            json = MediaRules.classificationJson(preferences.imageTiers, preferences.videoTiers)
            editing = true
        }) { Text(stringResource(R.string.loc_edit_classification_json)) }
        Text(stringResource(R.string.loc_codec_reference), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (editing) {
        val rules = MediaRules.parseClassificationJson(json)
        AlertDialog(onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.loc_edit_classification_json)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.loc_classification_json_hint))
                    OutlinedTextField(json, onValueChange = { json = it },
                        modifier = Modifier.fillMaxWidth(), isError = rules == null,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                }
            }, confirmButton = {
                TextButton(enabled = rules != null, onClick = {
                    rules?.let { (images, videos) ->
                        CLASSIFICATION_RULES.updateString(MediaRules.classificationJson(images, videos))
                        IMAGE_TIERS.updateString(images); VIDEO_TIERS.updateString(videos)
                        onUpdate(preferences.copyPluginOptions(imageTiers = MediaRules.withDisabledTiers(images, MediaRules.disabledTiers(preferences.imageTiers).joinToString(",")),
                            videoTiers = MediaRules.withDisabledTiers(videos, MediaRules.disabledTiers(preferences.videoTiers).joinToString(","))))
                        editing = false
                    }
                }) { Text(stringResource(R.string.confirm)) }
            }, dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.cancel)) }
            })
    }
}

/** 기존 쿠키 칩과 대화상자를 재사용하고 활성화할 때 전체 로그인 쿠키를 갱신합니다. */
@Composable
fun CookieOptions(preferences: DownloadUtil.DownloadPreferences, persistDefaults: Boolean = true, compactLabel: Boolean = false, onUpdate: (DownloadUtil.DownloadPreferences) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val profiles by com.junkfood.seal.util.DatabaseUtil.getCookiesFlow().collectAsState(emptyList())
    /** 쿠키 파일 준비와 활성화를 공통 처리하고 빠른 프로필은 추가 선택창을 생략합니다. */
    fun toggleCookies(enabled: Boolean, profileId: Int = preferences.cookieProfileId, explicit: Boolean = preferences.cookieProfileExplicit) {
            if (!busy) {
                busy = true
                scope.launch {
                    runCatching {
                        if (enabled) withContext(Dispatchers.IO) {
                            val file = com.plugin.runtime.PluginDownloadUtil.prepareCookies(profileId)
                            check(file.isFile) { com.junkfood.seal.App.context.getString(R.string.loc_cookie_login_hint) }
                        }
                        // 다시시도의 쿠키는 해당 요청에만 붙이고 새 다운로드창의 기본 설정을 바꾸지 않습니다.
                        if (persistDefaults) {
                            if (explicit) PluginPreferences.EXPLICIT_COOKIE_PROFILE.updateInt(profileId)
                            COOKIES.updateBoolean(enabled)
                        }
                        onUpdate(preferences.copyPreservingPluginOptions { copy(cookies = enabled) }.copyPluginOptions(cookieProfileId = profileId, cookieProfileExplicit = explicit))
                    }.onFailure { error = it.message.orEmpty() }
                    busy = false
                }
            }
    }
    val selectedHost = profiles.firstOrNull { preferences.cookieProfileExplicit && it.id == preferences.cookieProfileId }?.url?.let {
        runCatching { java.net.URI(it).host }.getOrNull()
    }
    VideoFilterChip(selected = preferences.cookies, onClick = {
        val quick = if (preferences.cookieProfileExplicit) preferences.cookieProfileId else if (com.plugin.PluginState.isEnabled(com.plugin.PluginId.INTERACTION))
            com.junkfood.seal.util.PreferenceUtil.run { QUICK_COOKIE_PROFILE.getInt(-1) } else -1
        if (!preferences.cookieProfileExplicit && profiles.any { it.id == quick }) toggleCookies(!preferences.cookies, quick) else opened = true
    }, label = if (compactLabel || selectedHost == null) stringResource(R.string.cookies) else "${stringResource(R.string.cookies)} · $selectedHost")
    if (opened) CookiesQuickSettingsDialog(
        isCookiesEnabled = preferences.cookies,
        cookieProfiles = listOf(com.junkfood.seal.database.objects.CookieProfile(-1, stringResource(R.string.cookies_all_sources), "")) + profiles,
        onCookieProfileClicked = { profile ->
            toggleCookies(true, profile.id, explicit = true)
            opened = false
        },
        onDismissRequest = { if (!busy) opened = false },
        onCookiesToggled = { toggleCookies(it) },
    )
    if (error.isNotEmpty()) AlertDialog(
        onDismissRequest = { error = "" }, title = { Text(stringResource(R.string.loc_cookie_check)) },
        text = { Text(error) }, confirmButton = { TextButton(onClick = { error = "" }) { Text(stringResource(R.string.confirm)) } })
}
