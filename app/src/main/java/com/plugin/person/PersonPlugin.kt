package com.plugin.person

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 이미지·영상에서 같은 모델과 판정 설정을 공유하는 얼굴 비교 진입점입니다. */
object PersonPlugin : FeaturePlugin() {
    override val id = PluginId.PERSON_AI
    override val route = "person_comparison"
    override val titleResource = R.string.plugin_person_ai
    override val icon = Icons.Outlined.Face

    /** 원본 화면을 수정하지 않고 앱의 공통 플러그인 탐색에 참여합니다. */
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        PersonSortPage(onBack)
    }
}

/** 얼굴 선택·모델 다운로드·측정 결과를 기존 Material 화면 규칙에 맞게 제공합니다. */
@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
private fun PersonPage(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val activity = generateSequence(LocalContext.current) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    val factory = remember(context) { object : ViewModelProvider.Factory {
        /** 분석 상태는 구성 변경에도 유지하고 앱 컨텍스트만 참조합니다. */
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PersonViewModel::class.java))
            return PersonViewModel(context) as T
        }
    } }
    val session = com.plugin.compatibility.TemporarySession.root
    val controller: PersonViewModel = viewModel(key = "person:${session ?: "normal"}",factory = factory)
    DisposableEffect(session,activity) { onDispose {
        // 구성 변경은 같은 ViewModel을 이어 쓰고, 실제 화면 이탈·저장소 전환은 기존처럼 정리합니다.
        if (activity?.isChangingConfigurations != true || com.plugin.compatibility.TemporarySession.root != session) {
            controller.cancel(); controller.clearSamples()
        }
    } }
    val state by controller.state.collectAsStateWithLifecycle()
    val firstPages = state.firstFaces as? PersonPages
    val secondPages = state.secondFaces as? PersonPages
    val releasePages = remember(firstPages?.identity,secondPages?.identity) {
        listOfNotNull(firstPages,secondPages).distinctBy { it.identity }.map { it.retain() }
    }
    DisposableEffect(releasePages) { onDispose { releasePages.forEach { it() } } }
    val selectedA = remember(firstPages?.identity ?: state.firstFaces,state.selectedFirst) { state.selectedFirst?.let { state.firstFaces.getOrNull(it) } }
    val selectedB = remember(secondPages?.identity ?: state.secondFaces,state.selectedSecond) { state.selectedSecond?.let { state.secondFaces.getOrNull(it) } }
    val firstName = inputName(state.first)
    val secondName = inputName(state.second)
    var showGroups by rememberSaveable(state.relations) { mutableStateOf(state.secondFaces.isEmpty()) }
    var model by remember { mutableStateOf("person_ai_model".getString("sface-lite").takeIf { id -> PersonModels.models.any { it.id == id } } ?: "sface-lite") }
    var strength by remember { mutableIntStateOf("person_ai_strength".getInt(3).coerceIn(1,5)) }
    var installed by remember { mutableStateOf(emptySet<String>()) }
    var download by remember { mutableStateOf<Job?>(null) }
    var downloadMessage by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var removeModel by remember { mutableStateOf<PersonModels.Model?>(null) }
    var removeCache by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val calibration = remember(model) { runCatching { PersonCalibration.load(context,PersonModels.models.single { it.id == model }) } }
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("person-ai",0) }
    var backend by remember { mutableStateOf(preferences.getString("backend","auto") ?: "auto") }
    var fallback by remember { mutableStateOf(preferences.getBoolean("fallback",true)) }
    val npuSupported = remember { runCatching { com.plugin.compatibility.enhancement.OptionalNpuProvider(context).isDeviceSupported() }.getOrDefault(false) }
    LaunchedEffect(refresh) {
        installed = PersonModels.installed(context)
        if (model !in installed) {
            model = PersonModels.models.firstOrNull { it.id in installed }?.id.orEmpty()
            "person_ai_model".updateString(model)
            controller.clearSamples()
        }
    }
    val first = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { controller.input(true,it) } }
    val second = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { controller.input(false,it) } }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.plugin_person_ai)) },navigationIcon = { BackButton(onBack) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding = PaddingValues(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("얼굴 분석은 기기 안에서만 수행합니다. 기본 결과는 현재 화면에서 확인하며, 저장을 켜면 다음 분석에서도 재사용합니다.") }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = !state.busy,onClick = { first.launch(arrayOf("image/*","video/*")) }) { Text("사진·영상 A") }
                OutlinedButton(enabled = !state.busy,onClick = { second.launch(arrayOf("image/*","video/*")) }) { Text("사진·영상 B") }
            } }
            item { Text("A: $firstName\nB: $secondName") }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = !state.busy && !removing && model in installed && state.first != null && state.second != null,onClick = { controller.analyze(model) }) { Text("두 입력 비교") }
                OutlinedButton(enabled = !state.busy && !removing && model in installed && state.first != null,
                    onClick = { controller.analyze(model,true) }) { Text("A만 분석·추적") }
                if (state.busy) TextButton(onClick = controller::cancel) { Text("분석 취소") }
                else if (state.firstFaces.isNotEmpty() || state.secondFaces.isNotEmpty()) TextButton(onClick = controller::clearSamples) { Text("화면 결과 지우기") }
            } }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.message.isNotBlank()) item { Text(state.message) }
            if (state.firstFaces.isNotEmpty()) item { FaceChoices("입력 A",state.firstFaces,state.selectedFirst) { controller.select(true,it) } }
            if (state.secondFaces.isNotEmpty()) item { FaceChoices("입력 B",state.secondFaces,state.selectedSecond) { controller.select(false,it) } }
            val a = selectedA
            val b = selectedB
            if (a != null && b != null) item {
                check(a.fingerprint == b.fingerprint) { "서로 다른 모델의 얼굴 특징은 비교할 수 없습니다." }
                val score = remember(a,b) { PersonMath.cosine(a.vector,b.vector) }
                Text("코사인 유사도: ${"%.4f".format(score)} · 확률이 아닙니다.")
                val threshold = calibration.getOrNull()?.get(strength-1)
                if (threshold == null) Text("판정 보류 · ${calibration.exceptionOrNull()?.message ?: "이 모델의 5단계 검증 임계값이 아직 등록되지 않았습니다."}")
                else {
                    Text(PersonMath.decide(score,threshold.reject,threshold.accept,a.issues.isEmpty() && b.issues.isEmpty()),style = MaterialTheme.typography.titleLarge)
                    Text("검증 기준: 거부 < ${"%.4f".format(threshold.reject)} · 승인 ≥ ${"%.4f".format(threshold.accept)}")
                    Text("LFW 별도 검증 세트: FAR ${"%.4f".format(threshold.far)} · FRR ${"%.4f".format(threshold.frr)} · TAR ${"%.4f".format(threshold.tar)} · 보류 ${"%.4f".format(threshold.hold)}",style = MaterialTheme.typography.bodySmall)
                    Text("공개 데이터 개발 검증값입니다. 현재 사진의 동일인물 확률이나 실제 사용 환경의 정확도를 뜻하지 않습니다.",style = MaterialTheme.typography.bodySmall)
                }
                (a.issues+b.issues).distinct().forEach { Text("품질 확인: $it",color = MaterialTheme.colorScheme.error) }
            }
            if (state.metrics.isNotBlank()) item {
                var details by remember(state.metrics) { mutableStateOf(false) }
                Text("전체 ${state.elapsedMs}ms · ${state.metrics.lineSequence().first()}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={ details = !details }) { Text(if (details) "실행 정보 접기" else "실행 정보 자세히") }
                if (details) Text(state.metrics,style=MaterialTheme.typography.bodySmall)
            }
            item { Text("판별 강도: $strength · ${listOf("매우 관대","관대","표준","엄격","매우 엄격")[strength-1]}")
                Slider(value = strength.toFloat(),onValueChange = { strength = it.toInt(); "person_ai_strength".updateInt(strength) },valueRange = 1f..5f,steps = 3)
            }
            if (state.relations != null) item {
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if (state.secondFaces.isNotEmpty()) FilterChip(selected=!showGroups,onClick={ showGroups=false },label={ Text("A·B 후보") })
                    FilterChip(selected=showGroups,onClick={ showGroups=true },label={ Text("반복 등장 그룹") })
                }
            }
            personRelations(state,calibration.getOrNull(),strength,showGroups,controller::select)
            item {
                Text("추론 가속",style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "자동","npu" to "NPU 우선","gpu" to "GPU 우선","cpu" to "CPU 전용").forEach { (value,label) ->
                        FilterChip(selected = backend == value,enabled = !state.busy,onClick = {
                            backend = value; preferences.edit().putString("backend",value).apply()
                        },label = { Text(label) })
                    }
                }
                PersonFallbackOption(checked = fallback,enabled = !state.busy,onCheckedChange = {
                    fallback = it; preferences.edit().putBoolean("fallback",it).apply()
                })
                if (!npuSupported) Text("이 기기·APK에서는 Qualcomm NPU 실행 환경을 사용할 수 없습니다.",style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = npuSupported && download == null && !state.busy,onClick = {
                    download = scope.launch {
                        try { com.plugin.compatibility.enhancement.OptionalNpuProvider(context).install { downloadMessage = it } }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { downloadMessage = failure.message ?: "NPU 실행 환경 설치 실패" }
                        finally { download = null }
                    }
                }) { Text("Qualcomm NPU 실행 환경 준비") }
            }
            item { Text("AI 모델 · Apache-2.0",style = MaterialTheme.typography.titleMedium) }
            PersonModels.models.forEach { candidate -> item(key = candidate.id) {
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(candidate.label)
                    Text("${candidate.bytes/1_000_000.0} MB · ${if (candidate.id in installed) "설치됨" else if (candidate.bundled) "미설치 · 오프라인 재설치 가능" else "미설치 · 다운로드 필요"}")
                    Text(if (candidate.recommendation.name.endsWith(".tflite")) "LiteRT NPU·GPU·CPU 요청 지원 · 실제 연산 분배는 기기 검증 필요"
                        else "ONNX CPU 지원 · NPU/GPU 미지원",style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !state.busy && !removing && candidate.id in installed,onClick = {
                            model = candidate.id; "person_ai_model".updateString(model); controller.clearSamples()
                        }) { Text(if (candidate.id == model) "선택됨" else "선택") }
                        if (!candidate.bundled || candidate.id !in installed) TextButton(enabled = download == null && !state.busy && !removing,onClick = {
                            download = scope.launch {
                                try { PersonModels.download(context,candidate) { downloadMessage = it }; downloadMessage = "모델 설치 완료"; refresh++ }
                                catch (cancel: CancellationException) { downloadMessage = "다운로드 취소"; throw cancel }
                                catch (failure: Exception) { downloadMessage = failure.message ?: "모델 다운로드 실패" }
                                finally { download = null }
                            }
                        }) { Text(if (candidate.bundled) "다시 설치" else if (candidate.id in installed) "검증 후 다시 설치" else "다운로드") }
                        if (candidate.id in installed) TextButton(enabled = !state.busy && !removing && download == null,
                            onClick = { removeCache = false; removeModel = candidate }) { Text("모델 삭제") }
                    }
                } }
            } }
            if (downloadMessage.isNotBlank()) item { Text(downloadMessage) }
            if (download != null) item { TextButton(onClick = { download?.cancel() }) { Text("다운로드 취소") } }
            if (model in installed) item { key(model,a) { PersonStorageSection(model,a,strength,state.busy) } }
        }
    }
    removeModel?.let { candidate -> AlertDialog(onDismissRequest = { if (!removing) removeModel = null },
        title = { Text("모델 삭제") },text = { Column {
            Text("${candidate.label} 설치 파일을 삭제합니다. 원본 사진·영상은 유지합니다.")
            if (candidate.bundled) Text("APK에 포함된 원본은 유지되며 오프라인으로 다시 설치할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
            Row { Checkbox(removeCache,enabled=!removing,onCheckedChange = { removeCache = it }); Text("이 모델의 분석 캐시도 삭제") }
        } },confirmButton = { TextButton(enabled = !removing,onClick = {
            removing = true
            scope.launch {
                try {
                    PersonModels.uninstall(context,candidate,removeCache)
                    removeModel = null
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { downloadMessage = failure.message ?: "모델 삭제 실패" }
                finally { removing = false; refresh++ }
            }
        }) { Text("모델 삭제") } },dismissButton = { TextButton(enabled = !removing,onClick = { removeModel = null }) { Text("취소") } }) }
}

/** 다중 얼굴은 정렬 썸네일을 보고 직접 선택하며 첫 얼굴을 자동 선택하지 않습니다. */
@Composable
private fun FaceChoices(title: String, faces: List<PersonSample>, selected: Int?, onSelect: (Int) -> Unit) {
    Column {
        Text("$title · ${faces.size}개 얼굴",style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(faces) { index, face ->
                OutlinedButton(onClick = { onSelect(index) }) { Column {
                    FacePreview(face,"$title 얼굴 ${index+1}")
                    Text("얼굴 ${index+1}${if (selected == index) " · 선택됨" else ""}")
                    face.trackId?.let { Text("추적 ID $it · ${face.frames}개 표본") }
                    face.intervals.forEach { Text("${it.startMs/1000.0}–${it.endMs/1000.0}초") }
                } }
            }
        }
    }
}

/** 문서 제공자의 내부 URI 대신 실제 파일 이름을 읽고 입력이 바뀔 때만 갱신합니다. */
@Composable
private fun inputName(uri: android.net.Uri?): String {
    val context = LocalContext.current.applicationContext
    val fallback = uri?.lastPathSegment?.substringAfterLast('/') ?: "미선택"
    val name by produceState(fallback,uri) {
        if (uri != null) value = withContext(Dispatchers.IO) {
            try { androidx.documentfile.provider.DocumentFile.fromSingleUri(context,uri)?.name ?: fallback }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fallback }
        }
    }
    return name
}

/** 영구 캐시의 얼굴 썸네일은 보이는 항목만 원본에서 복원합니다. */
@Composable
internal fun FacePreview(face: PersonSample, description: String) {
    val context = LocalContext.current.applicationContext
    // 페이지 재조회로 객체가 바뀌어도 같은 원본·트랙·표본의 썸네일은 다시 디코딩하지 않습니다.
    val preview by produceState<android.graphics.Bitmap?>(face.preview,face.sourceUri,face.fingerprint,face.trackId,face.representativeMs,face.landmarks.contentHashCode()) {
        if (value == null) value = withContext(Dispatchers.IO) {
            try { PersonAnalyzer.preview(context,face) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }
    val image = preview
    if (image != null) Image(image.asImageBitmap(),description,Modifier.size(80.dp))
    else Icon(Icons.Outlined.Face,description,Modifier.size(80.dp))
}
