package com.plugin.duplicates

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import com.plugin.compatibility.*
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 검토·보존 추천·제외 기록·명시적 삭제를 제공하는 독립 플러그인입니다. */
object DuplicatePlugin : FeaturePlugin() {
    override val id = PluginId.DUPLICATES
    override val route = "duplicate_media"
    override val titleResource = R.string.plugin_duplicates
    override val icon = Icons.Outlined.FindInPage

    /** 모델과 프리셋 선택은 저장하고 후보·삭제 계획은 화면에서 구체적으로 확인합니다. */
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var options by remember { mutableStateOf(runCatching {
            Json.decodeFromString<DuplicateOptions>(MMKV.defaultMMKV().decodeString("duplicate_options") ?: "{}")
        }.getOrDefault(DuplicateOptions())) }
        val progress by DuplicateScanService.state.collectAsStateWithLifecycle()
        val report by DuplicateScanService.results.collectAsStateWithLifecycle()
        val editing by DuplicateScanService.editing.collectAsStateWithLifecycle()
        val npuProvider = remember(context) { com.plugin.compatibility.enhancement.OptionalNpuProvider(context) }
        val npuSupported = remember(npuProvider) { runCatching { npuProvider.isDeviceSupported() }.getOrDefault(false) }
        var npuReady by remember { mutableStateOf(npuProvider.isLibraryReady()) }
        var installingNpu by remember { mutableStateOf(false) }
        val busy = progress.running || editing || installingNpu
        var error by rememberSaveable { mutableStateOf("") }
        var message by rememberSaveable { mutableStateOf("") }
        var opened by remember { mutableStateOf<List<String>?>(null) }
        var keepers by remember(report) { mutableStateOf<Map<String, String>>(emptyMap()) }
        var omitted by remember(report) { mutableStateOf<Set<String>>(emptySet()) }
        var plan by remember { mutableStateOf<Map<String, String>?>(null) }
        var clearExclusions by remember { mutableStateOf(false) }
        val preferences = createPluginPreferences()
        val root = MediaLibrary.rootPath(preferences)
        var permissionRevision by remember { mutableIntStateOf(0) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionRevision++ }
        val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRevision++ }
        val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) runCatching {
                check(android.provider.DocumentsContract.getTreeDocumentId(uri) == android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(preferences.sdcardUri)) &&
                    uri.authority == Uri.parse(preferences.sdcardUri).authority) { "설정한 다운로드 폴더를 선택하세요." }
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                permissionRevision++
            }.onFailure { error = it.message.orEmpty() }
        }
        val needsPermission = remember(root, permissionRevision) { StorageDirectories.requiresManagementPermission(context, preferences) }
        LaunchedEffect(root) { runCatching { DuplicateScanService.restore(context) }.onFailure { error = it.message.orEmpty() } }

        /** 사용자 변경은 기존 저장소에 즉시 반영하며 분석 중에는 입력을 잠급니다. */
        fun update(value: DuplicateOptions) { options = value; MMKV.defaultMMKV().encode("duplicate_options", Json.encodeToString(value)) }

        /** 폴더 권한을 얻어도 분석·삭제를 자동으로 실행하지 않습니다. */
        fun requestPermission() {
            runCatching {
                if (preferences.sdcard) treeLauncher.launch(Uri.parse(preferences.sdcardUri))
                else if (Build.VERSION.SDK_INT < 30) legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                else permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
            }.onFailure { error = it.message.orEmpty() }
        }

        val groups = if (report.root == root) report.groups else emptyList()
        val recommended = remember(groups, options.priority) { groups.associate { it.id to DuplicateRules.keeper(it.members, options.priority).path } }
        // 한 그룹에 복사본이 수천 개 있어도 화면에 보이는 파일 행만 구성합니다.
        val rows = remember(groups) { buildList<Pair<DuplicateGroup, DuplicateMedia?>> {
            groups.forEach { group -> add(group to null); group.members.forEach { add(group to it) } }
        } }
        val dateFormat = remember { java.text.DateFormat.getDateTimeInstance() }
        val selected = groups.filterNot { it.id in omitted }
        val candidates = selected.sumOf { it.members.size - 1 }
        Scaffold(topBar = { TopAppBar(title = { Text("AI 이미지·영상 중복 제거", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("분석 위치: $root", style = MaterialTheme.typography.bodySmall)
                    Text("이미지·영상만 분석합니다. AI 결과는 검토 후보이며 분석만으로 파일을 삭제하지 않습니다.")
                    Text("이미지는 전체 모양, 영상은 길이와 시간순 대표 프레임 8개를 비교합니다. 부분 편집 영상은 전체 중복으로 취급하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    Text("AI 모델", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("small" to "MobileNetV3 Small", "large" to "MobileNetV3 Large").forEach { (value, label) ->
                            FilterChip(selected = options.model == value, enabled = !busy, onClick = { update(options.copy(model = value)) }, label = { Text(label) })
                        }
                    }
                    Text("AI 실행 장치", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DuplicateBackend.entries.forEach { backend ->
                            FilterChip(selected = options.backend == backend, enabled = !busy,
                                onClick = { update(options.copy(backend = backend)) }, label = { Text(backend.label) })
                        }
                    }
                    Text("기본값은 NPU → GPU → CPU 순서로 시도합니다. 미지원·실행 실패 시 다음 장치로 전환합니다. GPU·CPU는 추가 다운로드가 필요 없습니다.", style = MaterialTheme.typography.bodySmall)
                    if (options.backend == DuplicateBackend.AUTO && npuSupported && !npuReady) {
                        OutlinedButton(enabled = !busy, onClick = {
                            installingNpu = true
                            scope.launch {
                                try {
                                    npuProvider.install { message = it }
                                    npuReady = npuProvider.isLibraryReady()
                                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                                catch (failure: Exception) { error = failure.message.orEmpty() }
                                finally { installingNpu = false }
                            }
                        }) { Text("NPU 런타임 설치 · 약 41MB") }
                    }
                    if (options.backend == DuplicateBackend.AUTO) Text(
                        if (!npuSupported) "이 기기는 현재 NPU 런타임 지원 대상이 아니므로 GPU·CPU를 사용합니다."
                        else if (npuReady) "NPU 런타임 준비됨 · 모델 실행에 실패하면 GPU·CPU로 전환합니다."
                        else "NPU 런타임 미설치 · 설치 전에는 GPU·CPU를 사용합니다.", style = MaterialTheme.typography.bodySmall)
                    Text("후보 판정 프리셋", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DuplicatePreset.entries.forEach { preset ->
                            FilterChip(selected = options.preset == preset, enabled = !busy, onClick = { update(options.copy(preset = preset)) }, label = { Text(preset.label) })
                        }
                    }
                    Text("엄격은 후보를 좁히며 민감·관대는 후보를 넓힙니다. 실제 파일을 확인한 후 삭제하세요.", style = MaterialTheme.typography.bodySmall)
                    Text("삭제하지 않을 파일 우선순위", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeepPriority.entries.forEach { priority ->
                            FilterChip(selected = options.priority == priority, enabled = !busy,
                                onClick = { update(options.copy(priority = priority)); keepers = emptyMap() }, label = { Text(priority.label) })
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text("제외 기록 남기기", Modifier.weight(1f).padding(top = 12.dp))
                        Switch(checked = options.rememberExclusions, enabled = !busy, onCheckedChange = { update(options.copy(rememberExclusions = it)) })
                    }
                    Text("기본 켬 · 기록한 파일은 다음 분석에서도 제외합니다. 끄면 이번 결과에서만 제외합니다.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { clearExclusions = true }) { Text("제외 기록 초기화") }
                    if (needsPermission || preferences.sdcard) OutlinedButton(enabled = !busy, onClick = ::requestPermission) { Text("폴더 읽기·쓰기 권한") }
                    Button(modifier = Modifier.fillMaxWidth(), enabled = !editing && Build.VERSION.SDK_INT >= 28, onClick = {
                        error = ""; message = ""
                        runCatching {
                            if (progress.running) DuplicateScanService.stop(context)
                            else if (needsPermission) requestPermission()
                            else DuplicateScanService.start(context, options)
                        }.onFailure { error = it.message.orEmpty() }
                    }) { Text(if (progress.running) "분석 중단" else "AI 판단 실행") }
                    if (Build.VERSION.SDK_INT < 28) Text("Android 9 이상에서 AI 중복 분석을 사용할 수 있습니다.")
                    if (progress.running || editing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text((if (progress.total > 0) "확인 ${progress.done} / ${progress.total} · " else "") + "후보 ${groups.size}그룹", style = MaterialTheme.typography.bodyMedium)
                    if (progress.message.isNotBlank()) Text(progress.message, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (report.scanned > 0) Text("마지막 결과: ${report.scanned}개 확인 · 오류 ${report.failures}개 · 후보 비교 ${report.comparisons}회", style = MaterialTheme.typography.bodySmall)
                    val engine = if (progress.running) progress.engine else report.engine.takeIf { report.scanned > 0 }.orEmpty()
                    if (engine.isNotBlank()) Text("AI 실행: $engine", style = MaterialTheme.typography.bodySmall)
                    if (message.isNotBlank()) Text(message)
                    if (error.isNotBlank() || progress.error.isNotBlank()) Text(error.ifBlank { progress.error }, color = MaterialTheme.colorScheme.error)
                    Button(enabled = !busy && candidates > 0, modifier = Modifier.fillMaxWidth(), onClick = {
                        plan = selected.associate { group -> group.id to (keepers[group.id] ?: recommended.getValue(group.id)) }
                    }) { Text("중복 삭제 · ${candidates}개") }
                    Text("아래에서 보존할 파일과 삭제할 그룹을 바꿀 수 있습니다. ‘중복 아님 · 제외’는 원본 파일을 삭제하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                }
                items(rows, key = { (group, media) -> group.id + ":" + (media?.identity ?: "header") }) { (group, media) ->
                    val keeper = keepers[group.id] ?: recommended.getValue(group.id)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (media == null) Row {
                                Checkbox(checked = group.id !in omitted, enabled = !busy, onCheckedChange = { checked -> omitted = if (checked) omitted - group.id else omitted + group.id })
                                Text(if (group.exact) "파일 내용 동일 · SHA-256 일치" else "시각적 유사 후보 · AI ${"%.1f".format(group.similarity * 100)}%", Modifier.padding(top = 12.dp))
                            }
                            else {
                                Row(Modifier.fillMaxWidth()) {
                                    RadioButton(selected = keeper == media.path, enabled = !busy,
                                        onClick = { keepers = keepers + (group.id to media.path) })
                                    Column(Modifier.weight(1f)) {
                                        AsyncImage(model = if (media.video) File(TemporarySession.root?.let { File(it, ".data") } ?: context.cacheDir,
                                            "duplicate-thumbnails/${media.sha256}.jpg")
                                            else if (media.path.startsWith("content://")) Uri.parse(media.path) else File(media.path),
                                            contentDescription = media.name, modifier = Modifier.size(96.dp))
                                        Text((if (keeper == media.path) "보존 · " else "삭제 후보 · ") + media.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("${media.width}×${media.height} · ${media.bytes / 1024} KB · " + dateFormat.format(java.util.Date(media.modified)), style = MaterialTheme.typography.bodySmall)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = { opened = group.members.map { it.path } }) { Text("열기·비교") }
                                            TextButton(enabled = !busy, onClick = { scope.launch {
                                                runCatching { DuplicateScanService.exclude(context, media, options.rememberExclusions) }.onFailure { error = it.message.orEmpty() }
                                            } }) { Text("중복 아님 · 제외") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        opened?.let { PostMediaViewer(it, null) { opened = null } }
        plan?.let { confirmed ->
            val plannedGroups = groups.filter { it.id in confirmed }
            AlertDialog(onDismissRequest = { plan = null }, title = { Text("중복 파일을 삭제할까요?") },
                text = { LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("${plannedGroups.sumOf { it.members.size - 1 }}개 파일을 영구 삭제합니다. 그룹마다 선택한 파일 1개는 보존합니다.") }
                    items(plannedGroups, key = { it.id }) { group -> Text("보존: " + group.members.first { it.path == confirmed[group.id] }.name) }
                    item { Text("변경되었거나 AI 처리 중인 파일은 건너뜁니다.") }
                } },
                confirmButton = { TextButton(onClick = { plan = null; scope.launch {
                    runCatching { DuplicateScanService.delete(context, confirmed) }.onSuccess { (count, failures) ->
                        message = "${count}개 삭제 · 실패 ${failures.size}개"; error = failures.take(3).joinToString("\n")
                    }.onFailure { error = it.message.orEmpty() }
                } }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { plan = null }) { Text("취소") } })
        }
        if (clearExclusions) AlertDialog(onDismissRequest = { clearExclusions = false }, title = { Text("제외 기록을 초기화할까요?") },
            text = { Text("다음 분석에서 제외했던 파일도 다시 검사합니다. 미디어 파일은 변경하지 않습니다.") },
            confirmButton = { TextButton(onClick = { clearExclusions = false; scope.launch {
                runCatching { withContext(Dispatchers.IO) { DuplicateStore(context).use { it.clearExclusions() } } }
                    .onSuccess { message = "제외 기록을 초기화했습니다." }.onFailure { error = it.message.orEmpty() }
            } }) { Text("초기화") } }, dismissButton = { TextButton(onClick = { clearExclusions = false }) { Text("취소") } })
    }
}
