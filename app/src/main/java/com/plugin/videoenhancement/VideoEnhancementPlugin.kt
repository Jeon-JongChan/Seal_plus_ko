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
import com.plugin.compatibility.enhancement.VideoEnhancementWork
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import kotlinx.coroutines.CancellationException
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

/** 설정 단계 버튼으로 시작하며, 모델은 단계가 채운(또는 직접 고른) 공유 모델 설정을 따릅니다. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalPermissionsApi::class)
@Composable
private fun VideoAiAction(path: String, onSaved: (String, Boolean) -> Unit) {
    if (com.plugin.compatibility.PluginMediaMetadataStore.isProcessingExcluded(path)) return
    val context = LocalContext.current
    val notificationPermission = if (android.os.Build.VERSION.SDK_INT >= 33)
        rememberPermissionState(android.Manifest.permission.POST_NOTIFICATIONS) else null
    val saved by rememberUpdatedState(onSaved)
    var open by remember(path) { mutableStateOf(false) }; var advanced by remember(path) { mutableStateOf(false) }
    val work by VideoEnhancementWork.state.collectAsState()
    val output by VideoEnhancementWork.saved.collectAsState()
    val processing = work.running && work.source == path
    var error by remember(path) { mutableStateOf("") }
    LaunchedEffect(output) { output?.takeIf { it.source == path }?.let {
        // 화면을 떠난 동안 저장·삭제한 결과는 오래된 이벤트만으로 다시 페이지에 추가하지 않습니다.
        if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.plugin.compatibility.MediaLibrary.fileBytes(it.output) > 0 })
            saved(it.output, it.replace)
        // 저장 이벤트를 한 번만 전달해 삭제한 복제가 뷰어 재진입 때 다시 추가되지 않게 합니다.
        if (VideoEnhancementWork.saved.value === it) VideoEnhancementWork.saved.value = null
    } }
    LaunchedEffect(work.preview) {
        if (open && work.preview?.source == path) { open = false; VideoEnhancementWork.showResult.value = true }
    }
    /** 취소는 요청 캐시만 정리하며 원본을 변경하지 않습니다. */
    fun close() {
        if (processing) VideoEnhancementWork.cancel(context)
        open = false
    }
    /** 사용자가 선택을 확정할 때 모델·FPS·다운샘플링을 작업 사본으로 고정합니다. */
    fun start(option: Int, model: String) {
        if (processing) return
        error = ""
        // 알림 권한을 거부해도 보정은 계속하고 결과는 앱에서 확인할 수 있게 보관합니다.
        if (notificationPermission?.status?.isGranted == false) notificationPermission.launchPermissionRequest()
        runCatching { VideoEnhancementWork.start(context.applicationContext, path, option, model,
            EnhancementModels.VIDEO_FPS.getInt(0).takeIf { it in VideoRules.frameRates } ?: 0) }
            .onFailure { error = it.message.orEmpty() }
    }
    FilledTonalIconButton(onClick = {
        VideoEnhancementWork.restore(context)
        if (VideoEnhancementWork.state.value.preview != null) VideoEnhancementWork.showResult.value = true
        else { open = true; advanced = false; error = "" }
    }) { Icon(Icons.Rounded.AutoFixHigh, "AI 동영상 화질개선") }
    if (open) Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 720.dp).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AI 동영상 화질개선", style = MaterialTheme.typography.titleLarge)
                // 제목과 아래 오류·닫기 버튼은 고정하고 본문만 스크롤해, 옵션이 길어져도 닫기·취소가 항상 보이게 합니다.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (processing) {
                    LinearProgressIndicator(progress = { work.progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("영상 처리 중 · ${work.progress}%")
                    ProcessingStage(); Text(work.engine, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { open = false }) { Text("백그라운드에서 계속") }
                } else {
                    // 스크롤 없이 바로 누를 수 있게 단계 버튼(누르면 시작) → 출력 크기 → 현재 옵션 순서로, 상세 옵션은 펼쳐서 조정합니다.
                    // 단계를 적용한 뒤의 모델(속도·균형 Small, 품질·최고 Medium 또는 저장값)로 시작합니다.
                    PresetStartButtons(image = false) {
                        start(savedSize(false), EnhancementModels.VIDEO_SELECTED.getString(EnhancementModels.SMALL_MODEL).ifBlank { EnhancementModels.SMALL_MODEL })
                    }
                    SizeSettings(image = false)
                    SelectedOptionsSummary()
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "상세 조정 접기 ▴" else "상세 조정 ▾") }
                    if (advanced) {
                        // 옵션은 실제 처리 순서(전처리 → 업스케일 → 보간 → 후처리 → 저장)대로 배치합니다.
                        PresetSettings()
                        StageHeader("1. 전처리 · 디코드 직후"); DenoiseSettings(); ShrinkSettings()
                        StageHeader("2. AI 업스케일"); SamplingSettings()
                        StageHeader("3. 프레임 보간"); FrameSettings()
                        StageHeader("4. 후처리 · 인코딩 직전"); SharpenSettings()
                        StageHeader("5. 저장 · 인코딩"); VideoEncodingSettings()
                    }
                    Text("경량 모델도 영상 길이·해상도에 따라 처리 시간이 늘어납니다. 결과 확인 전에는 원본을 바꾸지 않습니다.", style = MaterialTheme.typography.bodySmall)
                }
                }
                val message = error.ifBlank { if (work.source == path) work.error else "" }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { close() }) { Text(if (processing) "취소" else "닫기") }
            }
        }
    }
}

/** 완료 알림이나 뷰어에서 연 결과를 같은 비교·안전 저장 화면으로 표시합니다. */
@Composable
fun VideoEnhancementResultHost() {
    val context = LocalContext.current
    val work by VideoEnhancementWork.state.collectAsState()
    val open by VideoEnhancementWork.showResult.collectAsState()
    val result = work.preview
    var error by remember(result) { mutableStateOf("") }
    if (open && result == null && !work.running && work.error.isNotBlank()) {
        AlertDialog(onDismissRequest = { VideoEnhancementWork.showResult.value = false },
            title = { Text("영상 보정 실패") }, text = { Text(work.error) },
            confirmButton = { TextButton(onClick = { VideoEnhancementWork.showResult.value = false }) { Text("닫기") } })
    }
    if (!open || result == null) return
    val scope = rememberCoroutineScope()
    var original by remember(result) { mutableStateOf(false) }
    var saving by remember(result) { mutableStateOf(false) }
    var ready by remember(result, original) { mutableStateOf(false) }
    /** 사용자 확인 후 기존 저장 어댑터로 원본 검증과 원자적 저장을 수행합니다. */
    fun save(replace: Boolean) {
        if (saving) return
        saving = true; error = ""
        scope.launch {
            try {
                val output = EnhancementFiles.save(context, result, replace)
                VideoEnhancementWork.saved.value = VideoEnhancementWork.Companion.Saved(result.source, output, replace)
                VideoEnhancementWork.dismiss(context)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message.orEmpty() }
            finally { saving = false }
        }
    }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.heightIn(max = 720.dp).padding(20.dp)) {
                // 작은 화면에서도 닫기 버튼은 고정하고 비교 내용만 스크롤합니다.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("원본과 보정 결과 비교", style = MaterialTheme.typography.titleLarge)
                Text("${result.width} × ${result.height} · %.2f fps".format(java.util.Locale.ROOT, result.frameRate))
                Text(result.engine, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(original, onClick = { original = true; error = "" }, label = { Text("원본") })
                    FilterChip(!original, onClick = { original = false; error = "" }, label = { Text("보정 결과") })
                }
                com.plugin.ui.VideoWallpaperPreview(if (original) File(result.directory, "source").path else result.image.path,
                    Modifier.fillMaxWidth().height(240.dp), onReady = { ready = it },
                    onError = { error = "미리보기를 재생할 수 없습니다." }, muted = false, crop = false)
                Text(if (ready) "미리보기 재생 중" else "미리보기 준비 중", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !saving && EnhancementFiles.canReplace(result), onClick = { save(true) }) { Text("교체") }
                    OutlinedButton(enabled = !saving, onClick = { save(false) }) { Text("복제") }
                }
                Text("MP4 · ${result.codec} · MP4 원본만 교체할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
                TextButton(enabled = !saving, onClick = { VideoEnhancementWork.dismiss(context) }) { Text("닫기") }
            }
        }
    }
}
