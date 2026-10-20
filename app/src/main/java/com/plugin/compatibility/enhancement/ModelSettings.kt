package com.plugin.compatibility.enhancement

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/** 공개 폴더 검색·파일 가져오기·추천 다운로드와 Google 지원 검사를 제공합니다. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ModelSettings(onBack: () -> Unit, video: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selectedKey = if (video) EnhancementModels.VIDEO_SELECTED else EnhancementModels.SELECTED
    val defaultModel = if (video) EnhancementModels.SMALL_MODEL else EnhancementModels.DEFAULT_MODEL
    val npuProvider = remember(context) { OptionalNpuProvider(context) }
    var models by remember { mutableStateOf(if (video) listOf(
        EnhancementModels.Model("QuickSRNet Small · 초고속 4배 · 영상 기본", EnhancementModels.SMALL_MODEL, 136_904L),
        EnhancementModels.Model("QuickSRNet Medium · 초고속 4배", EnhancementModels.MEDIUM_MODEL, 249_452L)
    ) else listOf(EnhancementModels.Model("Real-ESRGAN x4v3 · 모바일 4배 · 이미지 기본", EnhancementModels.DEFAULT_MODEL, 4_870_119L))) }
    var selected by remember { mutableStateOf(selectedKey.getString().ifBlank { defaultModel }) }
    var backend by remember { mutableStateOf(EnhancementModels.BACKEND.getString("litert")) }
    var accelerated by remember { mutableStateOf(EnhancementModels.ACCELERATED.getBoolean(true)) }
    var npuFirst by remember { mutableStateOf(EnhancementModels.NPU_FIRST.getBoolean(false)) }
    val threadLimit = EnhancementRules.cpuThreadLimit(Runtime.getRuntime().availableProcessors())
    var cpuThreads by remember { mutableIntStateOf(EnhancementRules.cpuThreads(EnhancementModels.CPU_THREADS.getInt(2), Runtime.getRuntime().availableProcessors())) }
    var googleFirst by remember { mutableStateOf(EnhancementModels.GOOGLE_FIRST.getBoolean()) }
    var google by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<EnhancementModels.Model?>(null) }
    var deleteRife by remember { mutableStateOf(false) }
    var rifeReady by remember { mutableStateOf(RifeModels.ready(context)) }

    /** 저장소 오류를 표시하고 기존 선택과 목록은 유지합니다. */
    fun refresh() { scope.launch { runCatching { EnhancementModels.scan(context) }.onSuccess { models = it }.onFailure { status = it.message.orEmpty() } } }
    /** 내려받기·가져오기는 화면의 작업 하나로 제한합니다. */
    fun acquire(action: suspend () -> EnhancementModels.Model) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val model = action()
                models = (models + model).distinctBy { it.source }
                selected = model.source; selectedKey.updateString(selected); EnhancementPreset.edited()
                status = "모델 준비 완료: ${model.name}"
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { status = failure.message.orEmpty() }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) acquire { EnhancementModels.import(context, uri) }
    }
    val permissionSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    // 설정 단계가 영상 모델을 바꾸면 선택 표시도 다시 읽습니다.
    LaunchedEffect(EnhancementPreset.reload) {
        selected = if (video) selectedKey.getString(defaultModel) else EnhancementModels.selectedSource(context)
        runCatching { EnhancementModels.scan(context) }.onSuccess { models = it }.onFailure { status = it.message.orEmpty() }
        if (!video) google = runCatching { GoogleEnhancer.availability(context) }.getOrDefault(false to false)
    }
    deleting?.let { model ->
        AlertDialog(onDismissRequest = { if (!busy) deleting = null },
            title = { Text("모델 삭제") },
            text = { Text("${model.name}\n${model.source}\n\n이 모델 파일을 삭제합니다. 사용 중이면 기본 모델로 전환합니다.") },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("취소") } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        EnhancementModels.delete(context, model)
                        if (selected == model.source) selected = defaultModel
                        models = models.filterNot { it.source == model.source }
                        status = "모델 삭제 완료: ${model.name}"; deleting = null
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { status = failure.message.orEmpty(); deleting = null }
                    finally { busy = false }
                }
            }) { Text("삭제", color = MaterialTheme.colorScheme.error) } })
    }
    if (deleteRife) AlertDialog(onDismissRequest = { if (!busy) deleteRife = false }, title = { Text("보간 모델 삭제") },
        text = { Text("RIFE 4.6 가중치 두 파일(약 10MB)을 삭제합니다. 다음 프레임 보간 시 다시 다운로드합니다.") },
        dismissButton = { TextButton(enabled = !busy, onClick = { deleteRife = false }) { Text("취소") } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try { RifeModels.delete(context); rifeReady = false; deleteRife = false; status = "보간 모델 삭제 완료" }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { status = failure.message.orEmpty() }
                finally { busy = false }
            }
        }) { Text("삭제") } })
    Scaffold(topBar = { TopAppBar(title = { Text(if (video) "AI 동영상 화질개선" else "AI 이미지 화질개선") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (video) "프레임을 한 장씩 보정합니다. 이미지와 같은 모델 목록을 사용하며 결과 확인 후 저장합니다."
                else "이미지를 별도 프로세스로 보정하며 결과를 확인한 뒤 저장합니다.")
            Text(if (selected.isBlank()) "모델을 먼저 준비하세요" else "사용 중: ${models.firstOrNull { it.source == selected }?.name ?: File(selected).name}", style = MaterialTheme.typography.titleSmall)
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (video) "영상 기본: QuickSRNet Small · 최대: Medium · LiteRT · 약 134/244 KB"
                else "이미지 기본: Real-ESRGAN x4v3 · LiteRT·ONNX · 약 4.9 MB", style = MaterialTheme.typography.bodySmall)
            // 옵션은 실제 처리 순서(전처리 → 업스케일 → 보간 → 후처리 → 저장)대로 배치합니다.
            PresetSettings(enabled = !busy, image = !video)
            StageHeader("1. 전처리 · 디코드 직후")
            DenoiseSettings(enabled = !busy, image = !video)
            if (video) ShrinkSettings(enabled = !busy)
            StageHeader("2. AI 업스케일")
            Text("사용할 모델은 위 '사용 중' 표시, 변경은 맨 아래 '모델 관리', 장치·엔진은 '실행 환경'에서 설정합니다.", style = MaterialTheme.typography.bodySmall)
            SamplingSettings(enabled = !busy)
            if (video) {
                StageHeader("3. 프레임 보간")
                FrameSettings(enabled = !busy)
                Text(if (rifeReady) "RIFE 4.6 보간 모델 설치됨" else "RIFE 4.6은 보간할 때 다운로드합니다.", style = MaterialTheme.typography.bodySmall)
                if (rifeReady) TextButton(enabled = !busy, onClick = { deleteRife = true }) { Text("보간 모델 삭제") }
            }
            StageHeader(if (video) "4. 후처리 · 인코딩 직전" else "3. 후처리 · 저장 직전")
            SharpenSettings(enabled = !busy)
            if (video) { StageHeader("5. 저장 · 인코딩"); VideoEncodingSettings(enabled = !busy) }
            // 모델 파일 관리(폴더·목록·추천·링크)는 처리 옵션이 아니므로 처리 순서 뒤 맨 아래에 둡니다.
            StageHeader("모델 관리 · 업스케일 모델 선택·다운로드")
            Text("모델 폴더", style = MaterialTheme.typography.titleMedium)
            Text("${EnhancementModels.publicRoot()}\nSD 카드 다운로드를 사용하면 선택한 폴더의 models를 검색합니다.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy, onClick = { refresh() }) { Text("새로고침") }
                TextButton(enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) { Text("파일 가져오기") }
                TextButton(enabled = !busy, onClick = {
                    if (Build.VERSION.SDK_INT >= 30) permissionSettings.launch(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                    else permission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }) { Text("폴더 권한") }
            }
            Text("단일 파일 ONNX 또는 기본·추천 모델과 호환되는 정적 RGB FP32 TFLite를 지원합니다. .pth·NCNN·별도 가중치 파일은 변환이 필요합니다.", style = MaterialTheme.typography.bodySmall)
            models.forEach { model ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(model.name)
                        val runtime = if (model.source == EnhancementModels.DEFAULT_MODEL) "LiteRT·ONNX"
                            else if (model.source.endsWith(".tflite", true) || model.name.endsWith(".tflite", true)) "LiteRT" else "ONNX"
                        Text("$runtime · ${model.bytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy && selected != model.source, onClick = { selected = model.source; selectedKey.updateString(selected); EnhancementPreset.edited() }) {
                                Text(if (selected == model.source) "사용 중" else "사용")
                            }
                            if (!model.source.startsWith("asset://")) TextButton(enabled = !busy, onClick = { deleting = model }) {
                                Text("삭제", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            Text("추천 모델", style = MaterialTheme.typography.titleMedium)
            // 첫 추천의 같은 가중치는 기본 내장 모델로 제공하므로 중복 다운로드를 표시하지 않습니다.
            EnhancementModels.recommendations.drop(1).forEach { recommendation ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (recommendation.name.endsWith("x4plus.tflite")) "Real-ESRGAN x4plus · 모바일 4배" else recommendation.name)
                        Text(recommendation.description, style = MaterialTheme.typography.bodySmall)
                        val existing = models.firstOrNull { it.name == recommendation.name }
                        TextButton(enabled = !busy, onClick = {
                            if (existing != null) { selected = existing.source; selectedKey.updateString(selected); EnhancementPreset.edited() }
                            else acquire { EnhancementModels.download(context, recommendation.url, recommendation) { status = it } }
                        }) { Text(if (existing == null) "다운로드" else if (selected == existing.source) "사용 중" else "사용") }
                    }
                }
            }
            OutlinedTextField(link, onValueChange = { link = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Hugging Face 모델 파일 링크") }, singleLine = true)
            TextButton(enabled = !busy && link.isNotBlank(), onClick = { acquire { EnhancementModels.download(context, link) { status = it } } }) { Text("링크에서 다운로드") }
            // 실행 환경(엔진·장치·스레드·Google)은 단계와 무관한 기기 설정이라 페이지 맨 아래에 둡니다.
            StageHeader("실행 환경 · 엔진과 장치")
            Text("실행 엔진", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(backend == "litert", enabled = !busy, onClick = { backend = "litert"; EnhancementModels.BACKEND.updateString(backend) }, label = { Text("LiteRT v2 · 기본") })
                FilterChip(backend == "onnx", enabled = !busy, onClick = { backend = "onnx"; EnhancementModels.BACKEND.updateString(backend) }, label = { Text("ONNX") })
            }
            Text("TFLite 모델은 LiteRT로 GPU·NPU를 시도하고 실패하면 CPU로 실행합니다. 사용자 ONNX 모델은 ONNX로 실행합니다. 4배 모델도 목표 2배 크기로 저장할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            Text("처리 장치", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("하드웨어 가속", Modifier.weight(1f))
                Switch(accelerated, enabled = !busy, onCheckedChange = {
                    accelerated = it; EnhancementModels.ACCELERATED.updateBoolean(it)
                })
            }
            if (backend == "litert" && accelerated) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!npuFirst, enabled = !busy, onClick = { npuFirst = false; EnhancementModels.NPU_FIRST.updateBoolean(false) }, label = { Text("GPU · 기본") })
                FilterChip(npuFirst, enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            check(npuProvider.isDeviceSupported()) { "이 기기의 NPU 런타임을 지원하지 않습니다. GPU를 사용하세요." }
                            npuProvider.install { status = it }
                            npuFirst = true; EnhancementModels.NPU_FIRST.updateBoolean(true)
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { status = failure.message.orEmpty() }
                        finally { busy = false }
                    }
                }, label = { Text("NPU·GPU 자동") })
            }
            Text("NPU는 처음 선택할 때 약 41MB 런타임을 다운로드합니다. GPU·CPU는 추가 다운로드가 필요 없습니다.", style = MaterialTheme.typography.bodySmall)
            Text("LiteRT는 준비 시간이 짧은 GPU를 기본으로 사용하며 NPU·GPU 자동 경로도 선택할 수 있습니다. ONNX의 NNAPI는 별도 옵션입니다. 가속을 끄면 CPU로 처리합니다.", style = MaterialTheme.typography.bodySmall)
            Text("CPU 스레드 · $cpuThreads / $threadLimit", style = MaterialTheme.typography.titleSmall)
            if (threadLimit > 1) Slider(value = cpuThreads.toFloat(), valueRange = 1f..threadLimit.toFloat(), steps = (threadLimit - 2).coerceAtLeast(0),
                enabled = !busy, onValueChange = { cpuThreads = it.roundToInt().coerceIn(1, threadLimit) },
                onValueChangeFinished = { EnhancementModels.CPU_THREADS.updateInt(cpuThreads) })
            Text("CPU 처리와 가속되지 않는 연산에 적용합니다. 기기 CPU 수의 절반까지 선택하며, 높이면 발열과 다운로드 부하가 늘 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            if (!video) {
            Text("Google Media Enhancement", style = MaterialTheme.typography.titleMedium)
            Text(when { google == null -> "기기 지원 여부 확인 중"; google?.first != true -> "이 기기에서는 사용할 수 없습니다.";
                google?.second == true -> "지원 기기 · 모듈 준비 완료"; else -> "지원 기기 · 모듈 다운로드 필요" })
            if (google?.first == true && google?.second != true) TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { check(GoogleEnhancer.install(context)) { "Google 모듈 설치에 실패했습니다." }; google = GoogleEnhancer.availability(context) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { status = failure.message.orEmpty() }
                    finally { busy = false }
                }
            }) { Text("Google 모듈 다운로드") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Google 보정 우선 사용", Modifier.weight(1f))
                Switch(googleFirst, enabled = !busy && accelerated && (googleFirst || google?.second == true), onCheckedChange = { googleFirst = it; EnhancementModels.GOOGLE_FIRST.updateBoolean(it) })
            }
            Text("자동 가속 모드에서 우선 사용합니다. 실패하면 선택한 실행 엔진으로 전환하고, CPU 모드에서는 적용하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

