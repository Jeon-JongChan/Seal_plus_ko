package com.plugin.enhancement

import com.plugin.compatibility.enhancement.*

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
object ImageEnhancementPlugin : FeaturePlugin() {
    override val id = PluginId.IMAGE_AI
    override val route = "plugin_image_ai"
    override val titleResource = R.string.plugin_image_ai
    override val icon get() = Icons.Rounded.AutoFixHigh

    /** 모델 관리 메뉴는 다른 선택 플러그인을 켜지 않아도 사용할 수 있습니다. */
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) = ModelSettings(onBack)

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

/** 보정 선택·진행·전후 비교·명시적 저장을 한 화면에서 제공합니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageAiAction(paths: List<String>, batch: Boolean, onSaved: (String, String, Boolean) -> Unit) {
    val path = paths.firstOrNull() ?: return
    if (com.plugin.compatibility.PluginMediaMetadataStore.isProcessingExcluded(path)) return
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
                        SamplingSettings()
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
