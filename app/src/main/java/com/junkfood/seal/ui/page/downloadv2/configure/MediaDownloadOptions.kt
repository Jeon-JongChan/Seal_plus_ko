package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.util.*
import com.junkfood.seal.util.FileUtil.getCookiesFile
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 빠른 다운로드와 일반 다운로드에서 동일한 쿠키·이미지·AV1 설정을 제공합니다. */
@Composable
fun MediaDownloadOptions(
    preferences: DownloadUtil.DownloadPreferences,
    onUpdate: (DownloadUtil.DownloadPreferences) -> Unit,
    instagram: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var showCookies by remember { mutableStateOf(false) }
    var cookieError by remember { mutableStateOf("") }
    val profiles by DatabaseUtil.getCookiesFlow().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val save: (DownloadUtil.DownloadPreferences) -> Unit = { value ->
        COOKIES.updateBoolean(value.cookies)
        MEDIA_SELECTION.updateInt(value.mediaSelection)
        IMAGE_RESOLUTION.updateInt(value.imageResolution)
        CLASSIFY_MEDIA.updateBoolean(value.classifyMedia)
        QUALITY_THRESHOLD.updateInt(value.qualityThresholdMbps)
        TRANSCODE_AV1.updateBoolean(value.transcodeAv1)
        AV1_CRF.updateInt(value.av1Crf)
        VIDEO_QUALITY.updateInt(value.videoResolution)
        onUpdate(value)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { save(preferences.copy(cookies = !preferences.cookies)) }) {
                Text(if (preferences.cookies) "쿠키 적용: 켜짐" else "쿠키 적용: 꺼짐")
            }
            TextButton(onClick = { showCookies = true }) { Text("쿠키 선택") }
            TextButton(onClick = { expanded = true }) { Text("미디어 설정") }
        }
        if (instagram) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("영상/이미지", "영상만", "이미지만").forEachIndexed { index, label ->
                    FilterChip(
                        selected = preferences.mediaSelection == index,
                        onClick = { save(preferences.copy(mediaSelection = index)) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
    if (showCookies)
        AlertDialog(
            onDismissRequest = { showCookies = false },
            title = { Text("쿠키 선택") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("선택한 경우에만 로그인 쿠키를 사용합니다. 쿠키 등록은 설정 → 네트워크에서 할 수 있습니다.")
                    profiles.forEach { profile ->
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                            withContext(Dispatchers.IO) {
                                                com.junkfood.seal.App.context
                                                    .getCookiesFile()
                                                    .writeText(profile.content)
                                            }
                                        }
                                        .onSuccess {
                                            save(preferences.copy(cookies = true))
                                            showCookies = false
                                        }
                                        .onFailure { cookieError = "쿠키를 적용하지 못했습니다." }
                                }
                            }
                        ) {
                            Text(profile.url)
                        }
                    }
                    if (cookieError.isNotEmpty()) Text(cookieError)
                }
            },
            confirmButton = { TextButton(onClick = { showCookies = false }) { Text("닫기") } },
        )
    if (expanded)
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("이미지·파일 분류·AV1") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (instagram) {
                        Text("영상 해상도")
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(0 to "원본", 3 to "1080", 4 to "720", 6 to "360").forEach {
                                (quality, label) ->
                                FilterChip(
                                    preferences.videoResolution == quality,
                                    onClick = { save(preferences.copy(videoResolution = quality)) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    Text("이미지 해상도")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "원본", 1080 to "1080", 720 to "720").forEach { (height, label) ->
                            FilterChip(
                                preferences.imageResolution == height,
                                onClick = { save(preferences.copy(imageResolution = height)) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("받은 영상을 화질 폴더로 분류", modifier = Modifier.weight(1f))
                        Switch(
                            preferences.classifyMedia,
                            onCheckedChange = { save(preferences.copy(classifyMedia = it)) },
                        )
                    }
                    Text("분류 기준: ${preferences.qualityThresholdMbps} Mbps")
                    Slider(
                        preferences.qualityThresholdMbps.toFloat(),
                        onValueChange = {
                            save(
                                preferences.copy(qualityThresholdMbps = it.toInt().coerceIn(1, 100))
                            )
                        },
                        valueRange = 1f..100f,
                        steps = 98,
                    )
                    Text("파일 크기와 길이에 따른 추정입니다. 코덱에 따라 실제 화질은 다를 수 있습니다.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("AV1으로 변환해 원본 대체", modifier = Modifier.weight(1f))
                        Switch(
                            preferences.transcodeAv1,
                            onCheckedChange = { save(preferences.copy(transcodeAv1 = it)) },
                        )
                    }
                    Text("변환 성공·검증 후 원본을 대체합니다. 실패·취소 시 원본을 보존합니다.")
                    if (preferences.transcodeAv1) {
                        Text("AV1 품질(CRF): ${preferences.av1Crf} · 낮을수록 고화질")
                        Slider(
                            preferences.av1Crf.toFloat(),
                            onValueChange = {
                                save(preferences.copy(av1Crf = it.toInt().coerceIn(20, 50)))
                            },
                            valueRange = 20f..50f,
                            steps = 29,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("완료") } },
        )
}
