package com.junkfood.seal.ui.page.videolist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junkfood.seal.util.*
import com.junkfood.seal.util.PreferenceUtil.updateInt
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 모든 하위 폴더의 실제 영상 크기·길이·분류를 표시하고 선택 파일을 이동합니다. */
@Composable
fun MediaFileManagerDialog(onDismiss: () -> Unit) {
    var preferences by remember {
        mutableStateOf(DownloadUtil.DownloadPreferences.createFromPreferences())
    }
    var files by remember { mutableStateOf(emptyList<MediaLibrary.MediaFile>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { MediaLibrary.listVideos(preferences) } }
            .onSuccess { files = it }
            .onFailure { message = it.message.orEmpty() }
        busy = false
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("영상 파일 관리자") },
        text = {
            Column {
                Text("Seal 하위 폴더·지정한 영상 폴더: ${files.size}개")
                Text("고화질 기준 ${preferences.qualityThresholdMbps} Mbps · 파일 크기 ÷ 영상 길이")
                Slider(
                    preferences.qualityThresholdMbps.toFloat(),
                    onValueChange = {
                        preferences =
                            preferences.copy(qualityThresholdMbps = it.toInt().coerceIn(1, 100))
                        QUALITY_THRESHOLD.updateInt(preferences.qualityThresholdMbps)
                    },
                    valueRange = 1f..100f,
                    steps = 98,
                    enabled = !busy,
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(
                    enabled = !busy,
                    onClick = {
                        selected =
                            if (selected.size == files.size) emptySet()
                            else files.map { it.path }.toSet()
                    },
                ) {
                    Text("전체 선택/해제")
                }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(files, key = { it.path }) { file ->
                        Row(Modifier.fillMaxWidth()) {
                            Checkbox(
                                file.path in selected,
                                enabled = !busy,
                                onCheckedChange = {
                                    selected =
                                        if (it) selected + file.path else selected - file.path
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(file.name)
                                val bitrate = MediaRules.bitrateMbps(file.bytes, file.duration)
                                Text(
                                    String.format(
                                        Locale.KOREA,
                                        "%.1f MB · %.0f초 · %s",
                                        file.bytes / 1_000_000.0,
                                        file.duration,
                                        bitrate?.let {
                                            String.format(Locale.KOREA, "%.2f Mbps", it)
                                        } ?: "길이 미확인",
                                    )
                                )
                                Text(
                                    MediaRules.qualityFolder(
                                        file.bytes,
                                        file.duration,
                                        preferences.qualityThresholdMbps.toDouble(),
                                    )
                                )
                            }
                        }
                    }
                }
                if (message.isNotEmpty()) Text(message)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && selected.isNotEmpty(),
                onClick = {
                    busy = true
                    scope.launch {
                        val failures =
                            withContext(Dispatchers.IO) {
                                files
                                    .filter { it.path in selected }
                                    .mapNotNull { file ->
                                        runCatching { MediaLibrary.classify(file, preferences) }
                                            .exceptionOrNull()
                                            ?.let { "${file.name}: ${it.message}" }
                                    }
                            }
                        runCatching {
                                withContext(Dispatchers.IO) { MediaLibrary.listVideos(preferences) }
                            }
                            .onSuccess { files = it }
                            .onFailure { message = it.message.orEmpty() }
                        selected = emptySet()
                        message =
                            if (failures.isEmpty()) "선택 파일을 화질 폴더로 이동했습니다."
                            else failures.joinToString("\n")
                        busy = false
                    }
                },
            ) {
                Text("선택 파일 분류·이동")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("닫기") } },
    )
}
