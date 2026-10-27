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
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import com.plugin.compatibility.*
import com.plugin.compatibility.enhancement.OptionHelp
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        var downloading by remember { mutableStateOf<String?>(null) }
        var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        var downloadStatus by remember { mutableStateOf("") }
        var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
        val busy = progress.running || editing || installingNpu || downloading != null
        var error by rememberSaveable { mutableStateOf("") }
        var message by rememberSaveable { mutableStateOf("") }
        var review by rememberSaveable { mutableStateOf(false) }
        var opened by remember { mutableStateOf<List<String>?>(null) }
        var keepers by remember(report, options.target) { mutableStateOf<Map<String, String>>(emptyMap()) }
        var omitted by remember(report, options.target) { mutableStateOf<Set<String>>(emptySet()) }
        var clearExclusions by remember { mutableStateOf(false) }
        val preferences = createPluginPreferences()
        val root = MediaLibrary.rootPath(preferences)
        var plan by remember(report, options.target, root) { mutableStateOf<Map<String, String>?>(null) }
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
        LaunchedEffect(context) { installed = DuplicateModels.installed(context) }
        LaunchedEffect(root, options.target) {
            opened = null; message = ""; error = ""
            runCatching { DuplicateScanService.restore(context, options.target) }.onFailure { error = it.message.orEmpty() }
        }

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

        val matchingReport = report.root == root && report.target == options.target
        val groups = if (matchingReport) report.groups else emptyList()
        val recommended = remember(groups, options.priority) { groups.associate { it.id to DuplicateRules.keeper(it.members, options.priority).path } }
        val selected = remember(groups, omitted) { groups.filterNot { it.id in omitted } }
        val candidates = selected.sumOf { it.members.size - 1 }
        Scaffold(topBar = { TopAppBar(title = { Text("AI 이미지·영상 중복 제거", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("분석 위치: $root", style = MaterialTheme.typography.bodySmall)
                    Text("이미지·영상만 분석합니다. AI 결과는 검토 후보이며 분석만으로 파일을 삭제하지 않습니다.")
                    OptionHelp("",emptyList(),summary="중복 판정 원리와 비교 범위",
                        note="이미지는 전체 모양, 영상은 길이와 시간순 대표 프레임 8개를 비교합니다. 부분 편집 영상은 전체 중복으로 취급하지 않습니다.")
                    Text("분석 대상", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DuplicateTarget.entries.forEach { target ->
                            FilterChip(selected = options.target == target, enabled = !busy,
                                onClick = { update(options.copy(target = target)) }, label = { Text(target.label) })
                        }
                    }
                    Text("중복 분석용 AI 모델 · 기본 추천 Small", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    Text("선택 모델: " + (DuplicateModels.models.firstOrNull { it.id == options.model }?.label ?: "지원하지 않는 모델 · 다시 선택하세요"), style = MaterialTheme.typography.bodySmall)
                    DuplicateModels.models.forEach { model ->
                        OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${model.label} · ${"%.1f".format(model.bytes / 1_000_000.0)} MB")
                                Text(if (model.id in installed) "검증 완료 · 준비됨" else "미설치 · 다운로드 필요", style = MaterialTheme.typography.bodySmall)
                                if (downloading == model.id) {
                                    Text(downloadStatus, style = MaterialTheme.typography.bodySmall)
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    TextButton(onClick = { downloadJob?.cancel() }) { Text("다운로드 취소") }
                                } else if (model.id in installed) {
                                    FilterChip(selected = options.model == model.id, enabled = !busy,
                                        onClick = { update(options.copy(model = model.id)) },
                                        label = { Text(if (options.model == model.id) "사용 중" else "사용") })
                                } else TextButton(enabled = !busy, onClick = {
                                    downloading = model.id; downloadStatus = "다운로드 준비 중"; error = ""
                                    downloadJob = scope.launch {
                                        try {
                                            DuplicateModels.download(context, model) { downloadStatus = it }
                                            installed = DuplicateModels.installed(context)
                                            message = "${model.label} 준비 완료 · 사용할 모델을 선택하세요."
                                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                                            message = "모델 다운로드를 취소했습니다. 기존 모델은 보존됩니다."; throw cancel
                                        } catch (failure: Exception) { error = "다운로드 실패 · 저장 공간과 연결을 확인하세요. ${failure.message.orEmpty()}" }
                                        finally { downloading = null }
                                    }
                                }) { Text("다운로드") }
                            }
                        }
                    }
                    OptionHelp(options.model, listOf(
                        "small" to "MobileNetV3 Small: 약 4.1 MB, 경량 기본 모델, 특징 벡터 1,024차원.",
                        "large" to "MobileNetV3 Large: 약 10.9 MB, 더 큰 모델, 특징 벡터 1,280차원."
                    ), note = "입력은 둘 다 224×224 RGB입니다. Large의 실제 중복 판정 정확도·속도는 비교 검증 전까지 우수하다고 보장하지 않습니다. 모델은 명시적으로 다운로드해야 하며 화질 개선용 모델과 별도로 관리합니다.")
                    Text("AI 실행 장치", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DuplicateBackend.entries.forEach { backend ->
                            FilterChip(selected = options.backend == backend, enabled = !busy,
                                onClick = { update(options.copy(backend = backend)) }, label = { Text(backend.label) })
                        }
                    }
                    OptionHelp(options.backend.name, listOf(
                        "AUTO" to "자동: 준비된 NPU → GPU → CPU 순서로 시도하며, 미지원·미설치·실행 실패 시 다음 장치로 전환합니다.",
                        "GPU" to "GPU 우선: GPU 실행에 실패하면 CPU로 전환합니다.",
                        "CPU" to "CPU: 추가 구성 요소 없이 실행합니다."
                    ), note = "LiteRT는 AI 모델을 실행하는 프로그램입니다. CPU·GPU 실행에 필요한 구성 요소는 앱에 포함되어 있습니다. 지원 Qualcomm 기기에서 NPU를 사용하려면 약 41 MB의 추가 실행 파일이 필요합니다. 설치하지 않아도 GPU·CPU로 분석할 수 있습니다. 이 다운로드는 AI 모델이 아니라 NPU 사용에 필요한 추가 구성 요소입니다. 화질 개선에서 이미 설치했다면 함께 사용합니다.")
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
                        }) { Text("NPU 추가 구성 요소 설치 · 약 41 MB") }
                    }
                    if (options.backend == DuplicateBackend.AUTO) Text(
                        if (!npuSupported) "NPU 추가 구성 요소 미지원 · GPU·CPU 사용"
                        else if (npuReady) "NPU 추가 구성 요소 준비됨 · 화질 개선과 공유"
                        else "NPU 추가 구성 요소 미설치 · GPU·CPU 사용 가능", style = MaterialTheme.typography.bodySmall)
                    Text("후보 판정 프리셋", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DuplicatePreset.entries.forEach { preset ->
                            FilterChip(selected = options.preset == preset, enabled = !busy, onClick = { update(options.copy(preset = preset)) }, label = { Text(preset.label) })
                        }
                    }
                    OptionHelp("",emptyList(),summary="후보 판정 프리셋 설명",
                        note="엄격은 후보를 좁히며 민감·관대는 후보를 넓힙니다. 실제 파일을 확인한 후 삭제하세요.")
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
                    if (options.model !in installed) Text("선택한 모델을 먼저 다운로드하세요. 다른 설치 모델로 자동 변경하지 않습니다.")
                    Button(modifier = Modifier.fillMaxWidth(), enabled = !editing && !installingNpu && downloading == null && Build.VERSION.SDK_INT >= 28, onClick = {
                        error = ""; message = ""
                        runCatching {
                            if (progress.running) DuplicateScanService.stop(context)
                            else if (options.model !in installed) error = "선택한 모델을 먼저 다운로드하세요."
                            else if (needsPermission) requestPermission()
                            else DuplicateScanService.start(context, options)
                        }.onFailure { error = it.message.orEmpty() }
                    }) { Text(if (progress.running) "분석 중단" else "AI 판단 실행") }
                    if (Build.VERSION.SDK_INT < 28) Text("Android 9 이상에서 AI 중복 분석을 사용할 수 있습니다.")
                    if (progress.deleting && progress.total > 0) LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                    else if (progress.running || editing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (progress.deleting) TextButton(onClick = { DuplicateScanService.stop(context) }) { Text("삭제 중단 · 완료한 삭제는 유지") }
                    Text("${options.target.label} · " + (if (progress.target == options.target && progress.total > 0) "확인 ${progress.done} / ${progress.total} · " else "") + "후보 ${groups.size}그룹", style = MaterialTheme.typography.bodyMedium)
                    if (progress.target == options.target && progress.message.isNotBlank()) Text(progress.message, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (matchingReport && report.scanned > 0) Text("마지막 결과: ${report.target.label} · ${report.scanned}개 확인 · 오류 ${report.failures}개 · 후보 비교 ${report.comparisons}회", style = MaterialTheme.typography.bodySmall)
                    val engine = if (progress.running && progress.target == options.target) progress.engine else report.engine.takeIf { matchingReport && report.scanned > 0 }.orEmpty()
                    if (engine.isNotBlank()) Text("AI 실행: $engine", style = MaterialTheme.typography.bodySmall)
                    if (message.isNotBlank()) Text(message)
                    if (error.isNotBlank() || progress.target == options.target && progress.error.isNotBlank()) Text(error.ifBlank { progress.error }, color = MaterialTheme.colorScheme.error)
                    Button(enabled = !busy && candidates > 0, modifier = Modifier.fillMaxWidth(), onClick = {
                        plan = selected.associate { group -> group.id to (keepers[group.id] ?: recommended.getValue(group.id)) }
                    }) { Text("중복 삭제 · ${candidates}개") }
                    Text("비교창에서 보존할 파일과 삭제할 그룹을 바꿀 수 있습니다. ‘중복 아님 · 제외’는 원본 파일을 삭제하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    OutlinedButton(enabled = groups.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = { review = true }) {
                        Text("중복 비교창 열기 · ${groups.size}그룹")
                    }
                }
            }
        }
        if (review) DuplicateReviewDialog(groups, keepers, recommended, omitted, busy, candidates,
            if (progress.deleting) progress.message else error.ifBlank { progress.error.ifBlank { message.ifBlank { progress.message } } },
            onKeep = { group, path -> keepers = keepers + (group to path) },
            onSelect = { group, checked -> omitted = if (checked) omitted - group else omitted + group },
            onOpen = { opened = it }, onExclude = { media -> scope.launch {
                runCatching { DuplicateScanService.exclude(context, media, options.rememberExclusions, options.target, report) }
                    .onFailure { error = it.message.orEmpty() }
            } }, onDelete = {
                plan = selected.associate { group -> group.id to (keepers[group.id] ?: recommended.getValue(group.id)) }
            }, onDismiss = { review = false })
        opened?.let { PostMediaViewer(it, null) { opened = null } }
        plan?.let { confirmed ->
            val plannedGroups = groups.filter { it.id in confirmed }
            AlertDialog(onDismissRequest = { plan = null }, title = { Text("중복 파일을 삭제할까요?") },
                text = { LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("${plannedGroups.sumOf { it.members.size - 1 }}개 파일을 영구 삭제합니다. 그룹마다 선택한 파일 1개는 보존합니다.") }
                    items(plannedGroups, key = { it.id }) { group -> Text("보존: " + group.members.first { it.path == confirmed[group.id] }.name) }
                    item { Text("변경되었거나 AI 처리 중인 파일은 건너뜁니다.") }
                } },
                confirmButton = { TextButton(enabled = !busy, onClick = {
                    runCatching { DuplicateScanService.startDelete(context, confirmed, report) }
                        .onSuccess { plan = null; message = ""; error = "" }
                        .onFailure { error = it.message.orEmpty(); plan = null }
                }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { plan = null }) { Text("취소") } })
        }
        if (clearExclusions) AlertDialog(onDismissRequest = { clearExclusions = false }, title = { Text("제외 기록을 초기화할까요?") },
            text = { Text("다음 분석에서 제외했던 파일도 다시 검사합니다. 미디어 파일은 변경하지 않습니다.") },
            confirmButton = { TextButton(onClick = { clearExclusions = false; scope.launch {
                runCatching { withContext(Dispatchers.IO) { DuplicateStore(context).use { it.clearExclusions() } } }
                    .onSuccess { message = "제외 기록을 초기화했습니다." }.onFailure { error = it.message.orEmpty() }
            } }) { Text("초기화") } }, dismissButton = { TextButton(onClick = { clearExclusions = false }) { Text("취소") } })
    }
}
