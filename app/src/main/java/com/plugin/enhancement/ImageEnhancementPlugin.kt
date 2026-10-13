package com.plugin.enhancement

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

/** 이미지 뷰어 동작과 모델 설정만 제공하며 다운로드 엔진에는 참여하지 않습니다. */
object ImageEnhancementPlugin : FeaturePlugin {
    override val id = PluginId.IMAGE_AI
    override val route = "plugin_image_ai"
    override val titleResource = R.string.plugin_image_ai
    override val icon get() = Icons.Rounded.AutoFixHigh

    /** 모델 관리 메뉴는 다른 선택 플러그인을 켜지 않아도 사용할 수 있습니다. */
    @Composable
    override fun Content(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) = ModelSettings(onBack)

    /** 현재 이미지 위에 플랫폼 기본 마법봉 아이콘을 표시합니다. */
    @Composable
    override fun ImageAction(path: String, onSaved: (String, Boolean) -> Unit) =
        ImageAiAction(listOf(path), false) { _, output, replace -> onSaved(output, replace) }

    /** 복수 이미지 게시글에는 현재 이미지 동작 옆에 전체 보정 아이콘을 표시합니다. */
    @Composable
    override fun PostImageAction(paths: List<String>, onSaved: (String, String, Boolean) -> Unit) {
        val images = remember(paths) { EnhancementRules.postImages(paths) }
        if (images.size > 1) ImageAiAction(images, true, onSaved)
    }
}

/** 공개 폴더 검색·파일 가져오기·추천 다운로드와 Google 지원 검사를 제공합니다. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(listOf(EnhancementModels.Model("Real-ESRGAN x4v3 · 모바일 4배 · 기본", EnhancementModels.DEFAULT_MODEL, 4_870_119L))) }
    var selected by remember { mutableStateOf(EnhancementModels.SELECTED.getString().ifBlank { EnhancementModels.DEFAULT_MODEL }) }
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
                selected = model.source; EnhancementModels.SELECTED.updateString(selected)
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
    LaunchedEffect(Unit) {
        selected = EnhancementModels.selectedSource(context)
        runCatching { EnhancementModels.scan(context) }.onSuccess { models = it }.onFailure { status = it.message.orEmpty() }
        google = runCatching { GoogleEnhancer.availability(context) }.getOrDefault(false to false)
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
                        if (selected == model.source) selected = EnhancementModels.DEFAULT_MODEL
                        models = models.filterNot { it.source == model.source }
                        status = "모델 삭제 완료: ${model.name}"; deleting = null
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { status = failure.message.orEmpty(); deleting = null }
                    finally { busy = false }
                }
            }) { Text("삭제", color = MaterialTheme.colorScheme.error) } })
    }
    Scaffold(topBar = { TopAppBar(title = { Text("이미지 AI 보정") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("이미지만 처리합니다. Android 9 이상에서 별도 프로세스로 실행하며 결과를 확인한 뒤 저장합니다.")
            Text(if (selected.isBlank()) "모델을 먼저 준비하세요" else "사용 중: ${models.firstOrNull { it.source == selected }?.name ?: File(selected).name}", style = MaterialTheme.typography.titleSmall)
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("기본 모델: 사진 복원용 4배 모델 · LiteRT·ONNX 지원 · 약 4.9 MB", style = MaterialTheme.typography.bodySmall)
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
                        Text("${model.bytes / 1024} KB", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy && selected != model.source, onClick = { selected = model.source; EnhancementModels.SELECTED.updateString(selected) }) {
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
                            if (existing != null) { selected = existing.source; EnhancementModels.SELECTED.updateString(selected) }
                            else acquire { EnhancementModels.download(context, recommendation.url, recommendation) { status = it } }
                        }) { Text(if (existing == null) "다운로드" else if (selected == existing.source) "사용 중" else "사용") }
                    }
                }
            }
            OutlinedTextField(link, onValueChange = { link = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Hugging Face 모델 파일 링크") }, singleLine = true)
            TextButton(enabled = !busy && link.isNotBlank(), onClick = { acquire { EnhancementModels.download(context, link) { status = it } } }) { Text("링크에서 다운로드") }
            HorizontalDivider()
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
                FilterChip(npuFirst, enabled = !busy, onClick = { npuFirst = true; EnhancementModels.NPU_FIRST.updateBoolean(true) }, label = { Text("NPU·GPU 자동") })
            }
            Text("LiteRT는 준비 시간이 짧은 GPU를 기본으로 사용하며 NPU·GPU 자동 경로도 선택할 수 있습니다. ONNX의 NNAPI는 별도 옵션입니다. 가속을 끄면 CPU로 처리합니다.", style = MaterialTheme.typography.bodySmall)
            Text("CPU 스레드 · $cpuThreads / $threadLimit", style = MaterialTheme.typography.titleSmall)
            if (threadLimit > 1) Slider(value = cpuThreads.toFloat(), valueRange = 1f..threadLimit.toFloat(), steps = (threadLimit - 2).coerceAtLeast(0),
                enabled = !busy, onValueChange = { cpuThreads = it.roundToInt().coerceIn(1, threadLimit) },
                onValueChangeFinished = { EnhancementModels.CPU_THREADS.updateInt(cpuThreads) })
            Text("CPU 처리와 가속되지 않는 연산에 적용합니다. 기기 CPU 수의 절반까지 선택하며, 높이면 발열과 다운로드 부하가 늘 수 있습니다.", style = MaterialTheme.typography.bodySmall)
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

/** 보정 선택·진행·전후 비교·명시적 저장을 한 화면에서 제공합니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageAiAction(paths: List<String>, batch: Boolean, onSaved: (String, String, Boolean) -> Unit) {
    val path = paths.firstOrNull() ?: return
    if (com.plugin.compatibility.MediaRules.isNoFacePath(path)) return
    // 전체 저장 도중 SAF 경로가 바뀌어도 게시글 작업 상태와 취소 핸들을 유지합니다.
    val stateKey = if (batch) "post" else path
    val latestSaved by rememberUpdatedState(onSaved)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember(stateKey) { mutableStateOf(false) }
    var advanced by remember(stateKey) { mutableStateOf(false) }
    var upscale by remember(stateKey) { mutableStateOf(false) }
    var previews by remember(stateKey) { mutableStateOf(listOf<EnhancementClient.Preview>()) }
    var selectedPreview by remember(stateKey) { mutableIntStateOf(0) }
    var completed by remember(stateKey) { mutableIntStateOf(0) }
    var page by remember(stateKey) { mutableIntStateOf(1) }
    var count by remember(stateKey) { mutableIntStateOf(paths.size) }
    var job by remember(stateKey) { mutableStateOf<Job?>(null) }
    var engine by remember(stateKey) { mutableStateOf("실행 준비 중") }
    var percent by remember(stateKey) { mutableIntStateOf(0) }
    var processing by remember(stateKey) { mutableStateOf(false) }
    var saving by remember(stateKey) { mutableStateOf(false) }
    var original by remember(stateKey) { mutableStateOf(false) }
    var error by remember(stateKey) { mutableStateOf("") }
    /** 닫기와 취소는 원본 대신 해당 작업의 임시 결과만 정리합니다. */
    fun close() {
        if (saving) return
        job?.cancel(); previews.forEach(EnhancementClient::discard); previews = emptyList(); open = false
    }
    /** 해상도 선택이 끝난 시점에만 별도 프로세스의 추론을 시작합니다. */
    fun start(option: Int) {
        if (processing || saving) return
        error = ""; processing = true; percent = 0; completed = 0; selectedPreview = 0; engine = "실행 준비 중"
        job = scope.launch {
            try {
                previews = if (batch) EnhancementClient.enhancePost(context, paths, option, onEngine = { engine = it }) { current, total, progress ->
                    page = current; count = total; percent = progress
                } else listOf(EnhancementClient.enhance(context, path, option, onEngine = { engine = it }) { percent = it })
                original = false
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { processing = false }
        }
    }
    /** 사용자 저장 선택 뒤에만 파일 교체·복제와 뷰어 갱신을 실행합니다. */
    fun save(replace: Boolean) {
        if (saving || previews.isEmpty()) return
        saving = true; error = ""
        job = scope.launch {
            try {
                // 성공한 결과를 즉시 목록에서 제거해 부분 실패 후 재시도 시 중복 저장을 막습니다.
                while (previews.isNotEmpty()) {
                    val result = previews.first()
                    val saved = EnhancementFiles.save(context, result, replace)
                    previews = previews.drop(1); selectedPreview = 0; completed++
                    EnhancementClient.discard(result); latestSaved(result.source, saved, replace)
                }
                open = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = if (batch) "${completed}장 저장 완료 · 나머지 ${previews.size}장: ${failure.message.orEmpty()}" else failure.message.orEmpty() }
            finally { saving = false }
        }
    }
    DisposableEffect(stateKey) { onDispose { job?.cancel(); previews.forEach(EnhancementClient::discard) } }
    FilledTonalIconButton(onClick = { advanced = false; upscale = false; error = ""; open = true }) {
        Icon(if (batch) Icons.Rounded.Collections else Icons.Rounded.AutoFixHigh, contentDescription = if (batch) "게시글 전체 화질 개선" else "이미지 AI 보정")
    }
    if (open) Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !saving, dismissOnClickOutside = !saving)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (batch) "게시글 전체 화질 개선" else "이미지 AI 보정", style = MaterialTheme.typography.titleLarge)
                val result = previews.getOrNull(selectedPreview.coerceIn(0, (previews.size - 1).coerceAtLeast(0)))
                if (result != null) {
                    if (batch) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = !saving && selectedPreview > 0, onClick = { selectedPreview-- }) { Text("이전") }
                        Text("${selectedPreview + 1} / ${previews.size}장")
                        TextButton(enabled = !saving && selectedPreview < previews.lastIndex, onClick = { selectedPreview++ }) { Text("다음") }
                    }
                    Text("${result.width} × ${result.height} · ${result.engine}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(original, onClick = { original = true }, label = { Text("원본") })
                        FilterChip(!original, onClick = { original = false }, label = { Text("보정 결과") })
                    }
                    AsyncImage(if (original) File(result.directory, "source") else result.image, "보정 전후 비교",
                        Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !saving && previews.all(EnhancementFiles::canReplace), onClick = { save(true) }) { Text(if (batch) "전체 교체" else "교체") }
                        OutlinedButton(enabled = !saving, onClick = { save(false) }) { Text(if (batch) "전체 복제" else "복제") }
                    }
                    if (!EnhancementFiles.canReplace(result)) Text("이 파일 형식은 PNG 복제로 저장할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                } else if (processing) {
                    LinearProgressIndicator(progress = { if (batch) ((page - 1) + percent / 100f) / count.coerceAtLeast(1) else percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(if (batch) "보정 중 · $page / ${count}장 · $percent%" else "보정 중 · $percent%"); Text(engine, style = MaterialTheme.typography.bodySmall)
                    Text("다운로드는 계속 실행됩니다. 닫으면 보정을 취소합니다.", style = MaterialTheme.typography.bodySmall)
                } else if (upscale) {
                    TextButton(onClick = { upscale = false }) { Text("간편 설정으로") }
                    Text("원본 비율을 유지할 목표 해상도 또는 배율을 선택하세요.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(-720 to "720p", -1080 to "1080p", -1440 to "2K (1440p)", -2160 to "4K (2160p)",
                            2 to "2배", 4 to "4배", 8 to "8배").forEach { (option, label) ->
                            OutlinedButton(onClick = { start(option) }) { Text(label) }
                        }
                    }
                } else {
                    Text(if (batch) "게시글 이미지 ${paths.size}장을 보정합니다. 동영상·NOFACE는 제외하며 결과 확인 후 전체 저장을 선택하세요."
                        else "보정 강도를 선택하세요. 결과를 확인한 뒤 저장할 수 있습니다.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { start(EnhancementRules.MINIMUM) }, modifier = Modifier.weight(1f)) { Text("최소") }
                        Button(onClick = { start(EnhancementRules.MAXIMUM) }, modifier = Modifier.weight(1f)) { Text("최대") }
                    }
                    Text("최소: 2배 · 최대: 4K에 가깝게. 원본 비율과 출력 제한을 유지하며 이미 큰 사진은 확대하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "직접 설정 접기" else "직접 설정") }
                    if (advanced) {
                        OutlinedButton(onClick = { start(1) }, modifier = Modifier.fillMaxWidth()) { Text("크기 유지 · 화질 개선") }
                        OutlinedButton(onClick = { upscale = true }, modifier = Modifier.fillMaxWidth()) { Text("해상도·배율 선택") }
                    }
                    Text("먼저 플러그인 설정에서 모델을 준비하세요. 지원 기기는 Google 보정도 선택할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                }
                if (saving) { LinearProgressIndicator(Modifier.fillMaxWidth()); if (batch) Text("저장 중 · ${completed}장 완료") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !saving, onClick = { close() }) { Text(if (processing) "취소" else "닫기") }
            }
        }
    }
}
