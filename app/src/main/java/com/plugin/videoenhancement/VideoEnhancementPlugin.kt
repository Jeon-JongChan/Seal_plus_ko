package com.plugin.videoenhancement

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.junkfood.seal.R
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import com.plugin.compatibility.enhancement.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** 영상 UI 파사드는 공통 모델·실행·저장 어댑터를 사용하고 이미지 기능과 독립적으로 활성화됩니다. */
object VideoEnhancementPlugin : FeaturePlugin() {
    override val id = PluginId.VIDEO_AI
    override val route = "plugin_video_ai"
    override val titleResource = R.string.plugin_video_ai
    override val icon get() = Icons.Rounded.AutoFixHigh

    /** 모델 파일은 이미지와 공유하고 영상용 기본 선택과 FPS만 분리합니다. */
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) = ModelSettings(onBack, video = true)

    /** 저장 영상의 공통 실행·전후 비교 진입점을 제공합니다. */
    @Composable
    override fun VideoAction(path: String, onSaved: (String, Boolean) -> Unit) = VideoAiAction(path, onSaved)
}

/** 간편 선택은 Small·Medium을 고정 사용하고 직접 선택은 공유 모델 설정을 따릅니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoAiAction(path: String, onSaved: (String, Boolean) -> Unit) {
    if (com.plugin.compatibility.PluginMediaMetadataStore.isProcessingExcluded(path)) return
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val saved by rememberUpdatedState(onSaved)
    var open by remember(path) { mutableStateOf(false) }; var advanced by remember(path) { mutableStateOf(false) }
    var preview by remember(path) { mutableStateOf<EnhancementClient.Preview?>(null) }
    var job by remember(path) { mutableStateOf<Job?>(null) }; var processing by remember(path) { mutableStateOf(false) }
    var saving by remember(path) { mutableStateOf(false) }; var original by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf("") }; var engine by remember(path) { mutableStateOf("") }
    var progress by remember(path) { mutableIntStateOf(0) }
    /** 취소는 요청 캐시만 정리하며 원본을 변경하지 않습니다. */
    fun close() {
        if (saving) return
        job?.cancel(); preview?.let(EnhancementClient::discard); preview = null; open = false
    }
    /** 사용자가 선택을 확정할 때 모델·FPS·다운샘플링을 작업 사본으로 고정합니다. */
    fun start(option: Int, model: String) {
        if (processing || saving) return
        error = ""; progress = 0; processing = true
        job = scope.launch {
            try {
                preview = EnhancementClient.enhanceVideo(context, path, option, model,
                    EnhancementModels.VIDEO_FPS.getInt(0).takeIf { it in VideoRules.frameRates } ?: 0, { engine = it }) { progress = it }
                original = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { processing = false }
        }
    }
    /** 확인한 결과만 원자적 저장 어댑터로 교체하거나 MP4 복제로 저장합니다. */
    fun save(replace: Boolean) {
        val result = preview ?: return; if (saving) return
        saving = true; error = ""
        job = scope.launch {
            try {
                val output = EnhancementFiles.save(context, result, replace)
                EnhancementClient.discard(result); preview = null; saved(output, replace); open = false
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { saving = false }
        }
    }
    DisposableEffect(path) { onDispose { job?.cancel(); preview?.let(EnhancementClient::discard) } }
    FilledTonalIconButton(onClick = { open = true; advanced = false; error = "" }) { Icon(Icons.Rounded.AutoFixHigh, "AI 동영상 화질개선") }
    if (open) Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !saving, dismissOnClickOutside = !saving)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI 동영상 화질개선", style = MaterialTheme.typography.titleLarge)
                val result = preview
                if (result != null) {
                    Text("${result.width} × ${result.height} · %.2f fps".format(java.util.Locale.ROOT, result.frameRate))
                    Text(result.engine, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(original, onClick = { original = true }, label = { Text("원본") })
                        FilterChip(!original, onClick = { original = false }, label = { Text("보정 결과") })
                    }
                    com.plugin.ui.VideoWallpaperPreview(if (original) File(result.directory, "source").path else result.image.path,
                        Modifier.fillMaxWidth().height(240.dp), onReady = {}, onError = { error = "미리보기를 재생할 수 없습니다." }, muted = false, crop = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !saving && EnhancementFiles.canReplace(result), onClick = { save(true) }) { Text("교체") }
                        OutlinedButton(enabled = !saving, onClick = { save(false) }) { Text("복제") }
                    }
                    Text("MP4 · ${when (result.codec) { "video/av01" -> "AV1"; "video/avc" -> "H.264"; "video/hevc" -> "H.265"; else -> result.codec }} · MP4 원본만 교체할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                } else if (processing) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("영상 처리 중 · $progress%"); Text(engine, style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { start(EnhancementRules.MINIMUM, EnhancementModels.SMALL_MODEL) }, modifier = Modifier.weight(1f)) { Text("최소") }
                        Button(onClick = { start(EnhancementRules.MAXIMUM, EnhancementModels.MEDIUM_MODEL) }, modifier = Modifier.weight(1f)) { Text("최대") }
                    }
                    Text("최소: QuickSRNet Small · 2배\n최대: QuickSRNet Medium · 4K에 가깝게", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "상세 조정 접기" else "상세 조정") }
                    if (advanced) {
                        VideoEncodingSettings(); FrameSettings(); SamplingSettings()
                        Text("직접 선택 모델: ${EnhancementModels.VIDEO_SELECTED.getString(EnhancementModels.SMALL_MODEL).substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1 to "크기 유지", -720 to "720p", -1080 to "1080p", -1440 to "2K", -2160 to "4K", 2 to "2배", 4 to "4배").forEach { (option, label) ->
                                OutlinedButton(onClick = { start(option, EnhancementModels.VIDEO_SELECTED.getString(EnhancementModels.SMALL_MODEL)) }) { Text(label) }
                            }
                        }
                    }
                    Text("경량 모델도 영상 길이·해상도에 따라 처리 시간이 늘어납니다. 결과 확인 전에는 원본을 바꾸지 않습니다.", style = MaterialTheme.typography.bodySmall)
                }
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !saving, onClick = { close() }) { Text(if (processing) "취소" else "닫기") }
            }
        }
    }
}
