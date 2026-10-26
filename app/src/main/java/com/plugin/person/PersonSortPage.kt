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
import kotlinx.coroutines.*

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
    val progress by PersonSortService.state.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(preferences.getString("name","").orEmpty()) }
    var source by rememberSaveable { mutableStateOf(preferences.getString("source","").orEmpty()) }
    var destination by rememberSaveable { mutableStateOf(preferences.getString("destination","").orEmpty()) }
    var model by rememberSaveable { mutableStateOf(preferences.getString("model","sface-lite").orEmpty().takeIf { id -> PersonModels.models.any { it.id==id } } ?: "sface-lite") }
    var strength by rememberSaveable { mutableIntStateOf(preferences.getInt("strength",3).coerceIn(1,5)) }
    var type by rememberSaveable { mutableStateOf(preferences.getString("type","all").orEmpty()) }
    var backend by remember { mutableStateOf(enginePreferences.getString("backend","auto").orEmpty()) }
    var fallback by remember { mutableStateOf(enginePreferences.getBoolean("fallback",true)) }
    var explanation by rememberSaveable { mutableStateOf(false) }
    var installed by remember { mutableStateOf(emptySet<String>()) }
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
    val busy=progress.running || reference.busy || operation!=null
    val selected=reference.selectedFirst?.let { reference.firstFaces.getOrNull(it) }
    val pages=reference.firstFaces as? PersonPages
    val release=remember(pages?.identity) { pages?.retain() }
    DisposableEffect(release) { onDispose { release?.invoke() } }
    val activity=generateSequence(LocalContext.current) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    DisposableEffect(activity) { onDispose { if (activity?.isChangingConfigurations!=true) { controller.cancel(); controller.clearSamples() } } }
    LaunchedEffect(refresh) {
        installed=PersonModels.installed(context)
        if (model !in installed) {
            model=PersonModels.models.firstOrNull { it.id in installed }?.id.orEmpty()
            preferences.edit().putString("model",model).apply()
            controller.clearSamples()
        }
        PersonSortService.restore(context)
    }
    LaunchedEffect(Unit) {
        if (reference.first==null) preferences.getString("reference",null)?.let { controller.input(true,Uri.parse(it)) }
    }
    LaunchedEffect(progress.running) {
        if (!progress.running && !reference.busy) preferences.getString("reference",null)?.let { uri ->
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
            controller.input(true,uri); preferences.edit().putString("reference",uri.toString()).apply()
        }
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) message="알림 권한이 없어 작업 알림이 제한됩니다. 진행 상황은 이 화면에서도 확인할 수 있습니다."
    }
    Scaffold(topBar={ TopAppBar(title={ Text("AI 동일인물 폴더 분류") },navigationIcon={ BackButton(onBack) }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=16.dp)) {
            item { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
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
            item { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("판별 강도: $strength · ${listOf("매우 관대","관대","표준","엄격","매우 엄격")[strength-1]}",style=MaterialTheme.typography.titleMedium)
                Slider(strength.toFloat(),enabled=!busy,onValueChange={ strength=it.toInt(); preferences.edit().putInt("strength",strength).apply() },valueRange=1f..5f,steps=3)
                TextButton(onClick={ explanation=!explanation }) { Text(if (explanation) "판별 로직 접기" else "판별 로직 설명") }
                if (explanation) {
                    Text("얼굴을 검출·5점 정렬한 뒤 같은 모델의 128차원 특징을 코사인 유사도로 비교합니다. 1단계는 더 관대하고 5단계는 더 엄격하며, 검증된 모델별 승인 기준 이상인 파일만 이동합니다. 거부·승인 사이와 품질 부족 얼굴은 보류하고 원본 위치에 둡니다. 강도 변경은 저장 특징을 다시 추론하지 않습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val thresholds=remember(model) { runCatching { PersonCalibration.load(context,PersonModels.models.single { it.id==model }) }.getOrNull() }
                    thresholds?.get(strength-1)?.let { Text("현재 거부 < ${"%.4f".format(it.reject)} · 승인 ≥ ${"%.4f".format(it.accept)}. 사진 LFW 검증 기준이며 영상 전용 정확도 검증은 아직 완료되지 않았습니다.",style=MaterialTheme.typography.bodySmall) }
                }
            } } }
            item { Text("AI 모델 고르기",style=MaterialTheme.typography.titleMedium) }
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
            if (installed.isEmpty()) item { Text("사용할 모델을 설치한 뒤 얼굴 인식과 분류를 시작할 수 있습니다.",style=MaterialTheme.typography.bodySmall) }
            item { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("분류 대상",style=MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "전체","video" to "영상","image" to "이미지").forEach { (value,label) ->
                        FilterChip(selected=type==value,enabled=!busy,onClick={ type=value; preferences.edit().putString("type",value).apply() },label={ Text(label) })
                    }
                }
                OutlinedTextField(name,onValueChange={ name=it; preferences.edit().putString("name",it).apply() },enabled=!busy,singleLine=true,label={ Text("분류할 대상의 명칭") },supportingText={ Text("기본적으로 작업 폴더 안에 이 명칭으로 하위 폴더를 만듭니다.") },modifier=Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled=!busy,onClick={ photoPicker.launch(arrayOf("image/*")) }) { Text("대상 예시 사진") }
                    OutlinedButton(enabled=!busy && reference.first!=null && model in installed,onClick={ controller.analyze(model,true) }) { Text("대상 얼굴 인식") }
                }
                if (reference.first!=null) Text("예시 사진 선택됨 · 여러 얼굴이면 아래에서 분류할 얼굴을 선택하세요.",style=MaterialTheme.typography.bodySmall)
                if (reference.firstFaces.isNotEmpty()) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(reference.firstFaces) { index,face -> OutlinedButton(enabled=!busy,onClick={ controller.select(true,index) }) { Column {
                        FacePreview(face,"대상 얼굴 ${index+1}")
                        Text("얼굴 ${index+1}${if (reference.selectedFirst==index) " · 선택됨" else ""}")
                    } } }
                }
                selected?.issues?.forEach { Text("예시 품질: $it",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
                if (reference.message.isNotBlank()) Text(reference.message,style=MaterialTheme.typography.bodySmall)
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
                Text("저장 위치를 따로 고르지 않으면 작업 폴더 안에 명칭 하위 폴더를 만들고 승인된 원본 파일을 이동합니다. 다른 저장 위치를 선택하면 그 안에 만듭니다. 같은 명칭의 폴더는 재사용하며, 같은 파일 이름은 번호를 붙여 보존합니다. 보류·불일치 파일은 이동하지 않습니다.",style=MaterialTheme.typography.bodySmall)
                Text("분석 특징은 암호화 없이 기기 내부 캐시에 저장해 재사용합니다. 미디어 폴더 관리의 화질 구분 제외 이름은 별도로 직접 등록하세요.",style=MaterialTheme.typography.bodySmall)
            } } }
            item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(modifier=Modifier.fillMaxWidth(),enabled=!busy && name.isNotBlank() && source.isNotBlank() && selected!=null && selected.issues.isEmpty() && model in installed,onClick={
                    try {
                        PersonSortService.start(context,PersonSortRequest(name.trim(),reference.first.toString(),reference.selectedFirst!!,source,destination,type,model,strength,backend,fallback))
                        if (android.os.Build.VERSION.SDK_INT>=33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } catch (failure: Exception) { message=failure.message.orEmpty() }
                }) { Text("시작 · 백그라운드 분류") }
                if (progress.running) TextButton(onClick={ PersonSortService.stop(context) }) { Text("분류 중지") }
                if (reference.busy) TextButton(onClick=controller::cancel) { Text("대상 인식 취소") }
                if (operation!=null && removeModel==null) TextButton(onClick={ operation?.cancel() }) { Text("다운로드 취소") }
            } }
            item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if (progress.running || reference.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("전체 ${progress.total} · 처리 ${progress.done} · 남은 ${maxOf(0,progress.total-progress.done)} · 이동 ${progress.moved} · 캐시 재사용 ${progress.reused} · 보류 ${progress.held} · 오류 ${progress.failed}",style=MaterialTheme.typography.bodySmall)
                if (progress.message.isNotBlank()) Text(progress.message,style=MaterialTheme.typography.bodyMedium)
                if (progress.route.isNotBlank()) Text("실행 경로: ${progress.route}",style=MaterialTheme.typography.bodySmall)
                progress.logs.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                if (message.isNotBlank()) Text(message,style=MaterialTheme.typography.bodySmall)
            } }
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
