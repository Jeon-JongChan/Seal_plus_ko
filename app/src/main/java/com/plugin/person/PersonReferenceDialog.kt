package com.plugin.person

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

/** 추가 대상·주의대상의 사진을 별도로 분석하고 사용자가 선택한 얼굴만 등록합니다. */
@Composable
internal fun PersonReferenceDialog(role: String, model: String, factory: ViewModelProvider.Factory,
    onDismiss: () -> Unit, onAdd: (PersonReference) -> Unit) {
    val controller: PersonViewModel=viewModel(key="person-sort-registration-$role",factory=factory)
    val state by controller.state.collectAsStateWithLifecycle()
    val pages=state.firstFaces as? PersonPages
    val release=remember(pages?.identity) { pages?.retain() }
    DisposableEffect(release) { onDispose { release?.invoke() } }
    val activity=generateSequence(LocalContext.current) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    // 회전으로 다시 구성되는 동안에는 같은 ViewModel의 분석·선택 결과를 유지합니다.
    DisposableEffect(activity) { onDispose { if (activity?.isChangingConfigurations!=true) { controller.cancel(); controller.clearSamples() } } }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri!=null) controller.input(true,uri)
    }
    val selected=state.selectedFirst?.let { state.firstFaces.getOrNull(it) }
    AlertDialog(onDismissRequest={ if (!state.busy) onDismiss() },
        title={ Text(if (role=="target") "같은 사람의 대상 사진 추가" else "혼동되는 주의대상 추가") },
        text={ Column(Modifier.fillMaxWidth().heightIn(max=400.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("사진을 고르고 인식한 뒤 등록할 얼굴을 선택하세요.",style=MaterialTheme.typography.bodySmall)
            state.first?.let { AsyncImage(it,"등록할 예시 사진",Modifier.size(80.dp)) }
            OutlinedButton(enabled=!state.busy,onClick={ picker.launch(arrayOf("image/*")) }) { Text("사진 선택") }
            OutlinedButton(enabled=!state.busy && state.first!=null,onClick={ controller.analyze(model,true) }) { Text("얼굴 인식") }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.firstFaces.isNotEmpty()) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                itemsIndexed(state.firstFaces) { index,face -> OutlinedButton(enabled=!state.busy,onClick={ controller.select(true,index) }) {
                    Column { FacePreview(face,"등록할 얼굴 ${index+1}"); Text("얼굴 ${index+1}${if (state.selectedFirst==index) " · 선택됨" else ""}") }
                } }
            }
            selected?.issues?.forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
            if (state.message.isNotBlank()) Text(state.message,style=MaterialTheme.typography.bodySmall)
        } },
        confirmButton={ TextButton(enabled=!state.busy && selected!=null && selected.issues.isEmpty(),onClick={
            onAdd(PersonReference(state.first.toString(),requireNotNull(state.selectedFirst)))
        }) { Text("등록") } },
        dismissButton={ TextButton(onClick={ if (state.busy) controller.cancel() else onDismiss() }) { Text(if (state.busy) "인식 취소" else "닫기") } })
}
