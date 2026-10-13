package com.plugin.faces

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import com.plugin.compatibility.MediaLibrary
import com.plugin.compatibility.createPluginPreferences
import com.tencent.mmkv.MMKV

/** 실제 YuNet·MediaPipe와 해상도·목적지 규칙을 독립 메뉴에서 설정합니다. */
object FaceSortPlugin : FeaturePlugin() {
    override val id = PluginId.FACE_SORT
    override val route = "plugin_face_sort"
    override val titleResource = R.string.plugin_face_sort
    override val icon get() = Icons.Rounded.Face

    /** 선택 옵션을 즉시 저장하고 폴더 권한을 확인한 뒤 스캔을 실행합니다. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        val context = LocalContext.current
        val kv = remember { MMKV.defaultMMKV() }
        var model by rememberSaveable { mutableStateOf(kv.decodeString("face_sort_model", "yunet").takeIf { it in setOf("yunet", "mediapipe") } ?: "yunet") }
        var resolution by rememberSaveable { mutableIntStateOf(kv.decodeInt("face_sort_resolution", 640).takeIf { it in FaceRules.resolutions } ?: 640) }
        var perFolder by rememberSaveable { mutableStateOf(kv.decodeBool("face_sort_per_folder", false)) }
        var backend by rememberSaveable { mutableStateOf(kv.decodeString("face_sort_backend", "gpu")
            .takeIf { it in setOf("gpu", "cpu") } ?: "gpu") }
        var threshold by rememberSaveable { mutableFloatStateOf(kv.decodeFloat("face_sort_threshold", 0.5f).takeIf { it in 0.1f..0.9f } ?: 0.5f) }
        val preferences = createPluginPreferences()
        val progress by FaceScanService.state.collectAsStateWithLifecycle()
        var error by rememberSaveable { mutableStateOf("") }
        var permissionRefresh by remember { mutableIntStateOf(0) }
        val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionRefresh++ }
        val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRefresh++ }
        val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) runCatching {
                check(uri.authority == Uri.parse(preferences.sdcardUri).authority &&
                    DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(Uri.parse(preferences.sdcardUri))) {
                    "기본 다운로드 디렉터리와 같은 폴더를 선택하세요."
                }
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                permissionRefresh++
            }.onFailure { error = it.message.orEmpty() }
        }
        val needsPermission = remember(permissionRefresh, preferences.sdcard, preferences.privateDirectory, MediaLibrary.rootPath(preferences)) {
            com.plugin.compatibility.StorageDirectories.requiresManagementPermission(context, preferences)
        }
        /** 쓰기 권한이 없으면 시스템 화면을 열고 자동으로 파일 이동을 시작하지 않습니다. */
        fun requestPermission() {
            if (preferences.sdcard) treeLauncher.launch(Uri.parse(preferences.sdcardUri))
            else if (Build.VERSION.SDK_INT < 30) legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            else runCatching { settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"))) }.recoverCatching {
                settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure { error = it.message.orEmpty() }
        }
        Scaffold(topBar = { TopAppBar(title = { Text("얼굴 없는 사진 분류") }, navigationIcon = { BackButton(onBack) }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("스캔 위치", style = MaterialTheme.typography.titleSmall)
                        Text(MediaLibrary.rootPath(preferences), style = MaterialTheme.typography.bodySmall)
                        Text("이미지만 확인 · 영상 폴더와 NOFACE 제외", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("얼굴 판별 모델", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("yunet" to "YuNet", "mediapipe" to "MediaPipe").forEach { (value, label) ->
                        FilterChip(selected = model == value, enabled = !progress.running, onClick = {
                            model = value; kv.encode("face_sort_model", value)
                        }, label = { Text(label) })
                    }
                }
                Text(if (model == "yunet") "YuNet · 다양한 크기의 얼굴 검사"
                    else "MediaPipe BlazeFace · 가까운 얼굴에 적합", style = MaterialTheme.typography.bodySmall)
                Text("실행 장치", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("gpu" to "GPU 우선", "cpu" to "CPU").forEach { (value, label) ->
                        FilterChip(selected = backend == value, enabled = !progress.running, onClick = {
                            backend = value; kv.encode("face_sort_backend", value)
                        }, label = { Text(label) })
                    }
                }
                Text(if (model == "yunet") "GPU는 LiteRT를 사용합니다. 미지원·실행 실패 또는 원본 해상도 분석은 ONNX CPU로 전환합니다."
                    else "GPU 미지원·실행 실패 시 CPU로 전환합니다.", style = MaterialTheme.typography.bodySmall)
                Text("분석 해상도 · 긴 변 기준", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FaceRules.resolutions.forEach { value ->
                        FilterChip(selected = resolution == value, enabled = !progress.running, onClick = {
                            resolution = value; kv.encode("face_sort_resolution", value)
                        }, label = { Text(if (value == 0) "원본" else "${value}px") })
                    }
                }
                Text("분석용 이미지만 축소하며 원본 파일은 그대로 옮깁니다. 원본 분석은 419만 픽셀까지 지원합니다.",
                    style = MaterialTheme.typography.bodySmall)
                Text("분류 위치", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "단일 폴더", true to "폴더별").forEach { (value, label) ->
                        FilterChip(selected = perFolder == value, enabled = !progress.running, onClick = {
                            perFolder = value; kv.encode("face_sort_per_folder", value)
                        }, label = { Text(label) })
                    }
                }
                Text(if (perFolder) "각 일반 폴더의 NOFACE로 이동합니다. 720p·1080x1920 같은 화질 폴더는 상위 일반 폴더로 모읍니다."
                    else "기본 디렉터리 바로 아래 NOFACE로 모읍니다.", style = MaterialTheme.typography.bodySmall)
                var advanced by rememberSaveable { mutableStateOf(false) }
                TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "세부 설정 접기" else "세부 설정") }
                if (advanced) {
                    Text("얼굴 판정 기준: ${"%.2f".format(threshold)}", style = MaterialTheme.typography.titleSmall)
                    Slider(value = threshold, enabled = !progress.running, valueRange = 0.1f..0.9f, steps = 15,
                        onValueChange = { threshold = it }, onValueChangeFinished = { kv.encode("face_sort_threshold", threshold) })
                    Text("낮출수록 작은 얼굴을 더 보존합니다. 얼굴 검출은 오판할 수 있으며 움직이는 이미지는 제외합니다.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (needsPermission || preferences.sdcard) OutlinedButton(enabled = !progress.running,
                    onClick = ::requestPermission) { Text("폴더 읽기·쓰기 권한") }
                Text("같은 이름은 (1), (2)를 붙여 모두 보존합니다.", style = MaterialTheme.typography.bodySmall)
                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    error = ""
                    if (progress.running) FaceScanService.stop(context)
                    else if (needsPermission) requestPermission()
                    else FaceScanService.start(context, preferences, FaceScanService.Options(model, resolution, perFolder, threshold, backend))
                }) { Text(if (progress.running) "스캔 중단" else if (needsPermission) "권한 부여" else "스캔 후 얼굴 없는 사진 이동") }
                if (progress.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (progress.scanned > 0 || progress.running || progress.failures > 0) {
                    Text("확인 ${progress.scanned} · 이동 ${progress.moved} · 얼굴 있음 ${progress.kept} · 오류 ${progress.failures}",
                        style = MaterialTheme.typography.bodyMedium)
                    Text(progress.file, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(progress.message, style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotBlank() || progress.error.isNotBlank()) Text(error.ifBlank { progress.error },
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
