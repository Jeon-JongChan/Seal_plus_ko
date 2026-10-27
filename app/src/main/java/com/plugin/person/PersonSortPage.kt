package com.plugin.person

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.junkfood.seal.ui.component.BackButton
import com.plugin.compatibility.enhancement.OptionHelp
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 예시 얼굴·대상 이름으로 폴더의 미디어를 분류하는 단일 실행 화면입니다. */
@OptIn(ExperimentalMaterial3Api::class,ExperimentalLayoutApi::class)
@Composable
fun PersonSortPage(onBack: () -> Unit) {
    val context=LocalContext.current.applicationContext
    val preferences=remember { context.getSharedPreferences("person-sort",0) }
    val enginePreferences=remember { context.getSharedPreferences("person-ai",0) }
    val factory=remember { object : ViewModelProvider.Factory {
        /** 구성 변경에도 예시 얼굴과 실행 중인 분석을 유지합니다. */
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PersonViewModel::class.java))
            return PersonViewModel(context) as T
        }
    } }
    val controller: PersonViewModel=viewModel(key="person-sort-reference",factory=factory)
    val reference by controller.state.collectAsStateWithLifecycle()
    // 설정 화면은 실행 여부만 구독하고 파일별 진행·로그는 별도 구성 함수에서 표시합니다.
    val running by remember { PersonSortService.state.map { it.running }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue=PersonSortService.state.value.running)
    var name by rememberSaveable { mutableStateOf(preferences.getString("name","").orEmpty()) }
    var source by rememberSaveable { mutableStateOf(preferences.getString("source","").orEmpty()) }
    var destination by rememberSaveable { mutableStateOf(preferences.getString("destination","").orEmpty()) }
    var model by rememberSaveable { mutableStateOf(preferences.getString("model","sface-lite").orEmpty().takeIf { id -> PersonModels.models.any { it.id==id } } ?: "sface-lite") }
    var strength by rememberSaveable { mutableIntStateOf(preferences.getInt("strength",3).coerceIn(1,5)) }
    var type by rememberSaveable { mutableStateOf(preferences.getString("type","all").orEmpty()) }
    var includeSubfolders by rememberSaveable { mutableStateOf(preferences.getBoolean("include-subfolders",true)) }
    var skipOtherNames by rememberSaveable { mutableStateOf(preferences.getBoolean("skip-other-names",false)) }
    val referenceJson=remember { Json { ignoreUnknownKeys=true; encodeDefaults=true } }
    var additionalJson by rememberSaveable { mutableStateOf(preferences.getString("additional-references","[]").orEmpty()) }
    var cautionsJson by rememberSaveable { mutableStateOf(preferences.getString("caution-references","[]").orEmpty()) }
    val additionalReferences=remember(additionalJson) { runCatching { referenceJson.decodeFromString<List<PersonReference>>(additionalJson) }.getOrDefault(emptyList()) }
    val cautions=remember(cautionsJson) { runCatching { referenceJson.decodeFromString<List<PersonReference>>(cautionsJson) }.getOrDefault(emptyList()) }
    var registration by rememberSaveable { mutableStateOf<String?>(null) }
    var backend by remember { mutableStateOf(enginePreferences.getString("backend","auto").orEmpty()) }
    var fallback by remember { mutableStateOf(enginePreferences.getBoolean("fallback",true)) }
    var installed by remember { mutableStateOf(emptySet<String>()) }
    var loadingModels by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var removeModel by remember { mutableStateOf<PersonModels.Model?>(null) }
    var removeCache by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf("") }
    var npuMessage by remember { mutableStateOf("") }
    val provider=remember { com.plugin.compatibility.enhancement.OptionalNpuProvider(context) }
    val supported=remember { runCatching { provider.isDeviceSupported() }.getOrDefault(false) }
    var ready by remember { mutableStateOf(provider.isLibraryReady()) }
    val scope=rememberCoroutineScope()
    val busy=running || reference.busy || operation!=null || loadingModels || registration!=null
    val validName=PersonSortService.validName(name.trim())
    val selected=reference.selectedFirst?.let { reference.firstFaces.getOrNull(it) }
    val pages=reference.firstFaces as? PersonPages
    val release=remember(pages?.identity) { pages?.retain() }
    DisposableEffect(release) { onDispose { release?.invoke() } }
    val activity=generateSequence(LocalContext.current) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    DisposableEffect(activity) { onDispose { if (activity?.isChangingConfigurations!=true) { controller.cancel(); controller.clearSamples() } } }
    LaunchedEffect(refresh) {
        loadingModels=true
        try {
            installed=PersonModels.installed(context)
            if (model !in installed) {
                model=PersonModels.models.firstOrNull { it.id in installed }?.id.orEmpty()
                preferences.edit().putString("model",model).apply()
                controller.clearSamples()
            }
            PersonSortService.restore(context)
        } finally {
            loadingModels=false
        }
    }
    LaunchedEffect(Unit) {
        if (reference.first==null) preferences.getString("reference",null)?.let { controller.input(true,Uri.parse(it)) }
    }
    LaunchedEffect(running) {
        if (!running && !reference.busy) preferences.getString("reference",null)?.let { uri ->
            if (reference.first?.toString()!=uri) controller.input(true,Uri.parse(uri))
        }
    }
    /** 폴더 권한은 선택 시 영속화하고 실패하면 실행 가능한 경로로 저장하지 않습니다. */
    fun folder(uri: Uri?, destinationFolder: Boolean) {
        if (uri==null) return
        try {
            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (destinationFolder) destination=uri.toString() else source=uri.toString()
            preferences.edit().putString(if (destinationFolder) "destination" else "source",uri.toString()).apply()
        } catch (failure: Exception) { message="폴더 권한을 유지할 수 없습니다: ${failure.message}" }
    }
    val sourcePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder(it,false) }
    val destinationPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folder(it,true) }
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri!=null) {
            // 기본 인물이 바뀌었는데 이전 예시가 과반수를 차지해 다른 이름으로 이동하는 것을 막습니다.
            if (uri!=reference.first && additionalReferences.isNotEmpty()) {
                additionalJson="[]"; preferences.edit().putString("additional-references","[]").apply()
            }
            controller.input(true,uri); preferences.edit().putString("reference",uri.toString()).apply()
        }
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message="알림 권한이 없어 작업 알림이 제한됩니다. 진행 상황은 이 화면에서도 확인할 수 있습니다."
    }
    registration?.let { role -> PersonReferenceDialog(role,model,factory,onDismiss={ registration=null }) { added ->
        val primary=reference.selectedFirst?.let { PersonReference(reference.first.toString(),it) }
        if (added==primary || added in additionalReferences || added in cautions) message="이미 등록한 원본 얼굴입니다. 다른 사진이나 얼굴을 선택하세요."
        else if (role=="target") {
            additionalJson=referenceJson.encodeToString(additionalReferences+added)
            preferences.edit().putString("additional-references",additionalJson).apply()
        } else {
            cautionsJson=referenceJson.encodeToString(cautions+added)
            preferences.edit().putString("caution-references",cautionsJson).apply()
        }
        registration=null
    } }
    Scaffold(modifier=Modifier.imePadding(),topBar={ TopAppBar(title={ Text("AI 동일인물 폴더 분류") },navigationIcon={ BackButton(onBack) }) },bottomBar={
        Surface(tonalElevation=3.dp) { Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=20.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            if (running) Button(modifier=Modifier.fillMaxWidth(),onClick={ PersonSortService.stop(context) }) { Text("분류 중지") }
            else if (reference.busy) Button(modifier=Modifier.fillMaxWidth(),onClick=controller::cancel) { Text("대상 인식 취소") }
            else if (operation!=null && removeModel==null) Button(modifier=Modifier.fillMaxWidth(),onClick={ operation?.cancel() }) { Text("다운로드 취소") }
            else Button(modifier=Modifier.fillMaxWidth(),enabled=!busy && validName && source.isNotBlank() && selected!=null && selected.issues.isEmpty() && model in installed,onClick={
                try {
                    message=""
                    PersonSortService.start(context,PersonSortRequest(name.trim(),reference.first.toString(),reference.selectedFirst!!,source,destination,type,model,strength,backend,fallback,includeSubfolders,skipOtherNames,additionalReferences,cautions))
                    if (android.os.Build.VERSION.SDK_INT>=33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } catch (failure: Exception) { message=failure.message.orEmpty() }
            }) { Text(if (loadingModels) "모델 확인 중" else "시작 · 백그라운드 분류") }
            if (!busy) {
                val hint=when {
                    !validName -> "분류할 대상의 올바른 명칭을 입력하세요."
                    model !in installed -> "얼굴 인식 모델을 설치하세요."
                    reference.first==null -> "대상 예시 사진을 선택하세요."
                    reference.firstFaces.isEmpty() -> "대상 얼굴 인식을 실행하세요."
                    selected==null -> "분류할 예시 얼굴을 선택하세요."
                    selected.issues.isNotEmpty() -> "품질 안내를 확인하고 다른 예시 사진을 선택하세요."
                    source.isBlank() -> "작업 폴더를 선택하세요."
                    else -> ""
                }
                if (hint.isNotBlank()) Text(hint,style=MaterialTheme.typography.bodySmall)
            }
        } }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=16.dp)) {
            item(key="progress") { PersonSortProgress() }
            if (message.isNotBlank()) item(key="message") { Text(message,style=MaterialTheme.typography.bodyMedium) }
            if (reference.busy) item(key="reference-progress") { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(reference.message,style=MaterialTheme.typography.bodySmall)
            } }
            item(key="acceleration") { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("추론 가속",style=MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "자동","npu" to "NPU 우선","gpu" to "GPU 우선","cpu" to "CPU 전용").forEach { (value,label) ->
                        FilterChip(selected=backend==value,enabled=!busy,onClick={ backend=value; enginePreferences.edit().putString("backend",value).apply() },label={ Text(label) })
                    }
                }
                PersonFallbackOption(fallback,!busy) { fallback=it; enginePreferences.edit().putBoolean("fallback",it).apply() }
                if (supported) {
                    Text("Qualcomm NPU를 사용하기 위한 실행 라이브러리를 내려받습니다. 모델 다운로드와 별개이며 GPU·CPU에는 필요하지 않습니다.",style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!busy && !ready,onClick={
                        npuMessage="NPU 실행 라이브러리 준비 시작"
                        operation=scope.launch {
                            try { provider.install { npuMessage=it }; ready=provider.isLibraryReady(); npuMessage="NPU 실행 라이브러리 준비 완료" }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { npuMessage="NPU 준비 실패: ${failure.message}" }
                            finally { operation=null }
                        }
                    }) { Text(if (ready) "NPU 실행 라이브러리 준비됨" else "NPU 실행 라이브러리 다운로드") }
                } else Text("현재 기기·APK는 Qualcomm NPU 실행을 지원하지 않습니다. 자동·GPU·CPU를 선택하세요.",style=MaterialTheme.typography.bodySmall)
                if (npuMessage.isNotBlank()) Text(npuMessage,style=MaterialTheme.typography.bodySmall)
            } } }
            item(key="strength") { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("판별 강도: $strength · ${listOf("매우 관대","관대","표준","엄격","매우 엄격")[strength-1]}",style=MaterialTheme.typography.titleMedium)
                Slider(strength.toFloat(),enabled=!busy,onValueChange={ strength=it.toInt(); preferences.edit().putInt("strength",strength).apply() },valueRange=1f..5f,steps=3)
                OptionHelp("",emptyList(),summary="판별 원리와 강도별 기준") {
                    Text("얼굴을 검출·5점 정렬한 뒤 같은 모델의 128차원 특징을 코사인 유사도로 비교합니다. 추가 예시가 있으면 엄격 과반수의 일치를 요구합니다. 주의대상 점수가 대상 점수와 기존 보류 구간 폭 이내로 가깝거나 더 높으면 보류합니다. 영상은 같은 트랙에서 250ms 이상 떨어진 최소 두 시점과 과반수 승인을 요구합니다. 근거 부족·품질 부족은 이동하지 않습니다. 추가 규칙은 보수적 보류 조건이며 사용자 자료의 정확도 검증값은 아직 없습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val thresholds=remember(model) { runCatching { PersonCalibration.load(context,PersonModels.models.single { it.id==model }) }.getOrNull() }
                    thresholds?.get(strength-1)?.let { Text("현재 거부 < ${"%.4f".format(it.reject)} · 승인 ≥ ${"%.4f".format(it.accept)}. 사진 LFW 검증 기준이며 영상 전용 정확도 검증은 아직 완료되지 않았습니다.",style=MaterialTheme.typography.bodySmall) }
                }
            } } }
            item(key="models-heading") { Text("AI 모델 고르기",style=MaterialTheme.typography.titleMedium) }
            PersonModels.models.forEach { candidate -> item(key=candidate.id) {
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=model==candidate.id,enabled=!busy && candidate.id in installed,onClick={ model=candidate.id; preferences.edit().putString("model",model).apply(); controller.clearSamples() })
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) { Text(candidate.label,style=MaterialTheme.typography.titleSmall); Text("${candidate.bytes/1_000_000}MB · ${if (candidate.id in installed) "설치됨" else "미설치"} · Apache-2.0",style=MaterialTheme.typography.bodySmall) }
                    }
                    Text(if (candidate.recommendation.name.endsWith(".tflite")) "NPU·GPU·CPU 요청 지원 · 실제 경로는 실행 로그에 표시" else "ONNX 모델 · CPU 실행",style=MaterialTheme.typography.bodySmall)
                    if (candidate.id !in installed) TextButton(enabled=!busy,onClick={
                        operation=scope.launch {
                            try { PersonModels.download(context,candidate) { message=it }; message="모델 설치 완료"; refresh++ }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Exception) { message="모델 설치 실패: ${failure.message}" }
                            finally { operation=null }
                        }
                    }) { Text(if (candidate.bundled) "모델 다시 설치" else "모델 다운로드") }
                    if (candidate.id in installed) TextButton(enabled=!busy,onClick={ removeCache=false; removeModel=candidate }) { Text("모델 삭제") }
                } }
            } }
            if (!loadingModels && installed.isEmpty()) item(key="models-empty") { Text("사용할 모델을 설치한 뒤 얼굴 인식과 분류를 시작할 수 있습니다.",style=MaterialTheme.typography.bodySmall) }
            item(key="classification") { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("분류 대상",style=MaterialTheme.typography.titleMedium)
                PersonSortOption("하위폴더 포함",includeSubfolders,!busy) {
                    includeSubfolders=it; preferences.edit().putBoolean("include-subfolders",it).apply()
                }
                PersonSortOption("다른 명칭으로 분류된 캐시 건너뛰기",skipOtherNames,!busy) {
                    skipOtherNames=it; preferences.edit().putBoolean("skip-other-names",it).apply()
                }
                OptionHelp("",emptyList(),summary="선택한 범위의 이미지·영상을 분류합니다.",title="분류 대상 설명",
                    note="전체 개수는 선택한 범위의 실제 파일 수이며 DB 저장 개수가 아닙니다. 현재 대상 결과 폴더는 제외합니다. 명칭 건너뛰기는 전체·처리 수에 포함되며 별도로 집계합니다.\n\n명칭 건너뛰기를 켜면 같은 모델로 다른 이름에 분류한 변경 없는 파일은 분석하지 않습니다. 여러 인물이 함께 등장하는 사진·영상도 건너뛰므로 다른 인물을 다시 찾으려면 끄세요. 이전 버전의 캐시는 태그가 없어 처음에는 정상 비교합니다.\n\n예시 얼굴 특징은 시작 시 한 번 고정합니다. 영상 트랙의 표본은 최대 다섯 개이며, 표본 확정 후 비교 대표값을 재계산하지 않습니다.")
                if (skipOtherNames) Text("여러 인물이 등장하는 파일도 건너뛸 수 있습니다.",style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "전체","video" to "영상","image" to "이미지").forEach { (value,label) ->
                        FilterChip(selected=type==value,enabled=!busy,onClick={ type=value; preferences.edit().putString("type",value).apply() },label={ Text(label) })
                    }
                }
                OutlinedTextField(name,onValueChange={
                    if (it.trim()!=name.trim() && additionalReferences.isNotEmpty()) {
                        additionalJson="[]"; preferences.edit().putString("additional-references","[]").apply()
                    }
                    name=it; preferences.edit().putString("name",it).apply()
                },enabled=!busy,singleLine=true,isError=name.isNotEmpty() && !validName,label={ Text("분류할 대상의 명칭") },supportingText={ Text(if (name.isNotEmpty() && !validName) "1~80자로 입력하고 경로 구분자·제어 문자·상대 경로를 제외하세요." else if (destination.isBlank()) "작업 폴더 안에 이 명칭으로 하위 폴더를 만듭니다." else "선택한 저장 위치 안에 이 명칭으로 하위 폴더를 만듭니다.") },modifier=Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled=!busy,onClick={ photoPicker.launch(arrayOf("image/*")) }) { Text("대상 예시 사진") }
                    OutlinedButton(enabled=!busy && reference.first!=null && model in installed,onClick={ controller.analyze(model,true) }) { Text("대상 얼굴 인식") }
                }
                if (reference.first!=null) Text("예시 사진 선택됨 · 여러 얼굴이면 아래에서 분류할 얼굴을 선택하세요.",style=MaterialTheme.typography.bodySmall)
                if (reference.firstFaces.isNotEmpty()) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(reference.firstFaces) { index,face -> OutlinedButton(enabled=!busy,onClick={
                        if (reference.selectedFirst!=index && additionalReferences.isNotEmpty()) {
                            additionalJson="[]"; preferences.edit().putString("additional-references","[]").apply()
                        }
                        controller.select(true,index)
                    }) { Column {
                        FacePreview(face,"대상 얼굴 ${index+1}")
                        Text("얼굴 ${index+1}${if (reference.selectedFirst==index) " · 선택됨" else ""}")
                    } } }
                }
                selected?.issues?.forEach { Text("예시 품질: $it",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                if (reference.message.isNotBlank()) Text(reference.message,style=MaterialTheme.typography.bodySmall)
                Text("추가 대상 예시 ${additionalReferences.size}/4 · 주의대상 ${cautions.size}/5",style=MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled=!busy && model in installed && additionalReferences.size<4,onClick={ registration="target" }) { Text("대상 사진 추가") }
                    OutlinedButton(enabled=!busy && model in installed && cautions.size<5,onClick={ registration="caution" }) { Text("주의대상 추가") }
                }
                additionalReferences.forEachIndexed { index,item -> Row(verticalAlignment=Alignment.CenterVertically) {
                    AsyncImage(preferences.getString("reference-remap:${item.uri}",item.uri),"추가 대상 사진 ${index+1}",Modifier.size(48.dp))
                    Text("추가 대상 ${index+1} · 얼굴 ${item.face+1}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!busy,onClick={ additionalJson=referenceJson.encodeToString(additionalReferences.filterIndexed { i,_ -> i!=index }); preferences.edit().putString("additional-references",additionalJson).apply() }) { Text("등록 해제") }
                } }
                cautions.forEachIndexed { index,item -> Row(verticalAlignment=Alignment.CenterVertically) {
                    AsyncImage(preferences.getString("reference-remap:${item.uri}",item.uri),"주의대상 사진 ${index+1}",Modifier.size(48.dp))
                    Text("주의대상 ${index+1} · 얼굴 ${item.face+1}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    TextButton(enabled=!busy,onClick={ cautionsJson=referenceJson.encodeToString(cautions.filterIndexed { i,_ -> i!=index }); preferences.edit().putString("caution-references",cautionsJson).apply() }) { Text("등록 해제") }
                } }
                OptionHelp("",emptyList(),summary="추가 예시·주의대상과 영상 근거 안내",
                    note="추가 대상에는 기본 예시와 같은 사람의 다른 각도·조명 사진을, 주의대상에는 혼동되는 다른 사람의 사진을 등록하세요. 모든 예시는 시작 시 한 번만 준비합니다. 명칭이나 기본 예시 사진을 바꾸면 추가 대상 예시는 자동 해제합니다. 주의대상은 직접 등록 해제할 때까지 유지합니다. 등록 해제는 사진 파일을 삭제하지 않습니다.\n\n영상은 트랙별 최대 세 시점의 특징을 추가로 캐시합니다. 기존 영상 캐시에 시점별 근거가 없으면 해당 파일을 처음 사용할 때 한 번 재분석하므로 첫 실행은 느릴 수 있습니다. 사진 캐시는 유지합니다. 짧은 등장이나 애매한 얼굴은 보류가 늘 수 있습니다.")
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled=!busy,onClick={ sourcePicker.launch(source.takeIf { it.isNotBlank() }?.let(Uri::parse)) }) { Text("작업 폴더 선택") }
                    OutlinedButton(enabled=!busy,onClick={ destinationPicker.launch(destination.ifBlank { source }.takeIf { it.isNotBlank() }?.let(Uri::parse)) }) { Text("다른 저장 위치 (선택)") }
                    if (destination.isNotBlank()) TextButton(enabled=!busy,onClick={
                        destination=""
                        preferences.edit().remove("destination").apply()
                    }) { Text("작업 폴더 사용") }
                }
                FolderName("작업 폴더",source)
                Text(if (destination.isBlank()) "저장 위치: 작업 폴더 사용 (기본값)" else "저장 위치: 별도 선택한 폴더 사용",style=MaterialTheme.typography.bodySmall)
                FolderName("이동 위치",destination.ifBlank { source },name.trim().ifBlank { "(명칭 입력 필요)" })
                OptionHelp("",emptyList(),summary="동일인물로 승인된 원본 파일을 이동합니다.",title="이동 위치와 캐시 설명",
                    note="저장 위치를 따로 고르지 않으면 작업 폴더 안에 명칭 하위 폴더를 만듭니다. 다른 저장 위치를 선택하면 그 안에 만듭니다. 같은 명칭의 폴더는 재사용하며, 같은 파일 이름은 번호를 붙여 보존합니다. 보류·불일치 파일은 이동하지 않습니다.\n\n분석 특징은 암호화 없이 기기 내부 캐시에 저장해 재사용합니다. 미디어 폴더 관리의 화질 구분 제외 이름은 별도로 직접 등록하세요.")
            } } }
        }
    }
    removeModel?.let { candidate -> AlertDialog(
        onDismissRequest={ if (!busy) removeModel=null },
        title={ Text("모델 삭제") },
        text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("${candidate.label} 설치 파일을 삭제합니다. 원본 사진·영상은 유지합니다.")
            if (candidate.bundled) Text("APK에 포함된 원본은 유지되며, 원할 때 오프라인으로 다시 설치할 수 있습니다.",style=MaterialTheme.typography.bodySmall)
            Row(modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=removeCache,enabled=!busy,role=Role.Checkbox,onValueChange={ removeCache=it }),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=removeCache,enabled=!busy,onCheckedChange=null)
                Text("이 모델의 분석 캐시도 삭제",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton={ TextButton(enabled=!busy,onClick={
            operation=scope.launch {
                try {
                    PersonModels.uninstall(context,candidate,removeCache)
                    message="모델 삭제 완료"
                    removeModel=null
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { message="모델 삭제 실패: ${failure.message}"; removeModel=null }
                finally { operation=null; refresh++ }
            }
        }) { Text("삭제") } },
        dismissButton={ TextButton(enabled=!busy,onClick={ removeModel=null }) { Text("취소") } }
    ) }
}

/** 파일별 변경은 진행 카드 안에서만 구독하고 집계·최근 로그를 읽기 쉽게 표시합니다. */
@Composable
private fun PersonSortProgress() {
    val progress by PersonSortService.state.collectAsStateWithLifecycle()
    var showLogs by rememberSaveable { mutableStateOf(false) }
    if (!progress.running && progress.message.isBlank() && progress.total==0) return
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(if (progress.running) "분류 진행" else "최근 분류 결과",style=MaterialTheme.typography.titleMedium)
        if (progress.running) {
            if (progress.total > 0) LinearProgressIndicator(progress={ (progress.done.toFloat()/progress.total).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Text("전체 ${progress.total} · 처리 ${progress.done} · 남은 ${maxOf(0,progress.total-progress.done)}",style=MaterialTheme.typography.bodyMedium)
        Text("이동 ${progress.moved} · 캐시 재사용 ${progress.reused} · 명칭 건너뛰기 ${progress.skipped}",style=MaterialTheme.typography.bodySmall)
        Text("보류 ${progress.held} · 오류 ${progress.failed}",style=MaterialTheme.typography.bodySmall)
        if (progress.message.isNotBlank()) Text(progress.message,style=MaterialTheme.typography.bodyMedium)
        if (progress.route.isNotBlank()) Text("실행 경로: ${progress.route}",style=MaterialTheme.typography.bodySmall)
        if (progress.logs.isNotEmpty()) {
            TextButton(onClick={ showLogs=!showLogs }) { Text(if (showLogs) "최근 기록 접기" else "최근 기록 보기 (${progress.logs.size})") }
            if (showLogs) progress.logs.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
        }
    } }
}

/** 분류 범위·캐시 선택을 행 전체로 전환하며 실행 중 변경을 막습니다. */
@Composable
private fun PersonSortOption(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange=onCheckedChange),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked=checked,enabled=enabled,onCheckedChange=null)
        Text(label,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
    }
}

/** 제공자의 폴더 이름을 IO 스레드에서 읽고 명칭 하위 폴더까지 이동 위치를 표시합니다. */
@Composable
private fun FolderName(label: String, uri: String, childName: String? = null) {
    val context=LocalContext.current.applicationContext
    val name by produceState("미선택",uri) {
        value=if (uri.isBlank()) "미선택" else withContext(Dispatchers.IO) {
            runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(context,Uri.parse(uri))?.name ?: "선택한 폴더" }.getOrDefault("폴더 권한 확인 필요")
        }
    }
    Text("$label: $name${if (uri.isNotBlank() && childName!=null) "/$childName" else ""}",style=MaterialTheme.typography.bodySmall)
}

/** 비교·분류 화면에서 체크박스와 여러 줄 설명을 가운데 정렬하고 행 전체로 전환합니다. */
@Composable
internal fun PersonFallbackOption(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange=onCheckedChange).padding(vertical=4.dp),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        Checkbox(checked=checked,enabled=enabled,onCheckedChange=null)
        Text("가속기 실패 시 다음 경로 사용",modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,
            color=MaterialTheme.colorScheme.onSurface.copy(alpha=if (enabled) 1f else 0.38f))
    }
}
