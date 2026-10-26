package com.plugin.person

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 원본 연결·트랙·품질이 포함된 메모리 내 검색 후보입니다. */
private data class PersonHit(val source: PersonSource, val face: PersonStoredFace, val score: Double)

/** 저장 동의·영속 큐·암호화 캐시 검색을 같은 모델 설정에 연결합니다. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PersonStorageSection(modelId: String, query: PersonSample?, strength: Int, busy: Boolean) {
    if (com.plugin.compatibility.TemporarySession.root != null) {
        Text("임시 공간에서는 분석 결과를 현재 세션에서만 보관하며 영구 저장·검색을 사용하지 않습니다.")
        return
    }
    val context = LocalContext.current.applicationContext
    val preferences = remember { context.getSharedPreferences("person-ai",0) }
    val scope = rememberCoroutineScope()
    val progress by PersonIndexService.progress.collectAsStateWithLifecycle()
    var consent by remember { mutableStateOf(preferences.getBoolean("storage-consent",false)) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var statistics by remember { mutableStateOf("") }
    var failures by remember { mutableStateOf(emptyList<PersonPending>()) }
    var offset by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var priority by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf(emptyList<PersonHit>()) }
    var top by remember { mutableIntStateOf(20) }
    val model = remember(modelId) { PersonModels.models.single { it.id == modelId } }
    val thresholds = remember(modelId) { runCatching { PersonCalibration.load(context,model) }.getOrNull() }
    LaunchedEffect(refresh,progress.substringBefore(" · "),offset,consent) {
        try {
            if (consent) withContext(Dispatchers.IO) {
                PersonStore(context).use { store ->
                    statistics = "${store.statistics()}\n${store.counts()}"
                    failures = store.failed(offset)
                }
            } else { statistics = ""; failures = emptyList() }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { message = failure.message ?: "저장 상태 확인 실패" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && consent) scope.launch {
            working = true
            try {
                withContext(Dispatchers.IO) {
                    PersonStore(context).use { store ->
                        for (uri in uris.distinct()) {
                            context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            store.enqueue(uri.toString(),modelId,if (priority) 10 else 0)
                        }
                    }
                    PersonIndexService.resume(context)
                }
                refresh++
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { message = failure.message ?: "파일 등록 실패 · 지속 읽기 권한을 확인하세요." }
            finally { working = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("사전 분석·저장된 얼굴 검색",style = MaterialTheme.typography.titleMedium)
        Row {
            Checkbox(checked = consent,enabled = !busy && !working,onCheckedChange = { confirm = if (it) "consent" else "revoke" })
            Text("얼굴 특징·등장 구간의 암호화 영구 저장에 동의")
        }
        Text("저장에 동의하면 이후 비교는 같은 원본·모델의 특징을 재사용합니다. 원본 얼굴 이미지는 별도 보관하지 않으며 저장 데이터는 Android 백업에서 제외됩니다.",style = MaterialTheme.typography.bodySmall)
        if (consent) {
            Text(statistics,style = MaterialTheme.typography.bodySmall)
            Text(progress,style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(enabled = !busy && !working,onClick = { picker.launch(arrayOf("image/*","video/*")) }) { Text("파일 등록·증분 분석") }
                TextButton(enabled = !working,onClick = { PersonIndexService.pause(context); refresh++ }) { Text("중단") }
                TextButton(enabled = !working,onClick = {
                    runCatching { PersonIndexService.resume(context) }.onFailure { message = it.message.orEmpty() }
                }) { Text("재개") }
            }
            Row { Checkbox(priority,onCheckedChange = { priority = it }); Text("새 등록 요청 우선 처리") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = !busy && !working,onClick = { confirm = "model" }) { Text("현재 모델 분석 삭제") }
                TextButton(enabled = !busy && !working,onClick = { confirm = "all" }) { Text("모든 얼굴 데이터 삭제") }
            }
            Text("상위 후보 ${top}개")
            Slider(top.toFloat(),onValueChange = { top = it.toInt() },valueRange = 1f..100f,enabled = !working)
            Button(enabled = !busy && !working && query != null && query.fingerprint == model.fingerprint,onClick = {
                val selected = query ?: return@Button
                val vector = selected.vector.clone()
                working = true; hits = emptyList()
                scope.launch {
                    val start = System.nanoTime()
                    try {
                        hits = withContext(Dispatchers.IO) {
                            PersonStore(context).use { store ->
                                while (true) {
                                    val result = mutableListOf<PersonHit>(); var removed = false
                                    for (candidate in store.search(model.fingerprint,vector,top)) {
                                        var found = store.resolve(candidate.id(),model.fingerprint)
                                        while (found != null) {
                                            val source = found.first
                                            val metadata = runCatching { PersonAnalyzer.metadata(context,Uri.parse(source.uri)) }.getOrNull()
                                            if (metadata != null && metadata.first == source.bytes && metadata.second == source.modified) break
                                            store.forget(source.uri); removed = true
                                            found = store.resolve(candidate.id(),model.fingerprint)
                                        }
                                        if (found != null) result += PersonHit(found.first,found.second,candidate.score())
                                        else { store.pruneOrphans(); removed = true }
                                    }
                                    if (!removed) return@use result
                                }
                                @Suppress("UNREACHABLE_CODE") emptyList()
                            }
                        }
                        message = "검색 ${(System.nanoTime()-start)/1_000_000}ms · 후보 ${hits.size}개 · 전체 정확 검색·AI 재추론 없음"
                        refresh++
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { message = failure.message ?: "얼굴 검색 실패" }
                    finally { vector.fill(0f); working = false }
                }
            }) { Text("선택한 A 얼굴로 저장 데이터 검색") }
            if (hits.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits) { hit ->
                    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
                        Text(Uri.parse(hit.source.uri).lastPathSegment ?: "저장된 원본")
                        Text("유사도 ${"%.4f".format(hit.score)} · ${if (thresholds == null) "판정 보류 · 검증 기준 없음" else {
                            val value = thresholds[strength-1]
                            PersonMath.decide(hit.score,value.reject,value.accept,hit.face.issues.isEmpty() && query?.issues?.isEmpty() == true)
                        }}")
                        hit.face.trackId?.let { Text("추적 ID $it · ${hit.face.frames}개 표본") }
                        hit.face.intervals.forEach { Text("${it.startMs/1000.0}–${it.endMs/1000.0}초") }
                    } }
                }
            }
            if (failures.isNotEmpty()) {
                Text("실패 파일 · ${offset+1}부터",style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(failures,key = { it.id }) { failed -> Row {
                        Text("#${failed.id} ${failed.error}",Modifier.weight(1f))
                        TextButton(enabled = !working,onClick = { scope.launch {
                            withContext(Dispatchers.IO) { PersonStore(context).use { it.enqueue(failed.uri,failed.model,10,force = true) } }
                            PersonIndexService.resume(context); refresh++
                        } }) { Text("개별 재시도") }
                    } }
                }
                Row {
                    TextButton(enabled = offset > 0,onClick = { offset = maxOf(0,offset-50) }) { Text("이전 실패") }
                    TextButton(enabled = failures.size == 50,onClick = { offset += 50 }) { Text("다음 실패") }
                }
            }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message)
    }
    confirm?.let { action ->
        AlertDialog(onDismissRequest = { if (!working) confirm = null },title = { Text(if (action == "consent") "얼굴 분석 영구 저장" else "저장된 얼굴 분석 삭제") },
            text = { Text(when (action) {
                "consent" -> "얼굴 특징·파일 연결·영상 등장 구간을 이 기기의 백업 제외 영역에 암호화해 저장합니다. 이후 대량 검색과 반복 비교에 재사용하며, 여기서 언제든 삭제할 수 있습니다. 저장에 동의하시겠습니까?"
                "model" -> "현재 모델의 특징·검색 인덱스만 삭제합니다. 다른 모델의 분석과 원본 파일은 유지합니다."
                else -> "저장된 얼굴 특징·검색 인덱스·사전 분석 요청을 모두 삭제합니다. 원본 파일은 유지합니다."
            }) },
            confirmButton = { TextButton(enabled = !working,onClick = {
                if (action == "consent") {
                    preferences.edit().putBoolean("storage-consent",true).commit(); consent = true; confirm = null; refresh++
                } else scope.launch {
                    working = true
                    try {
                        PersonIndexService.pause(context)
                        if (action == "revoke") { preferences.edit().putBoolean("storage-consent",false).commit(); consent = false }
                        withContext(Dispatchers.IO) { PersonStore(context).use { it.clear(if (action == "model") model.fingerprint else null) } }
                        hits.forEach { it.face.vector.fill(0f) }; hits = emptyList(); confirm = null; refresh++
                    } catch (failure: Exception) { message = failure.message ?: "얼굴 데이터 삭제 실패" }
                    finally { working = false }
                }
            }) { Text(if (action == "consent") "동의·저장 허용" else "분석 데이터 삭제") } },
            dismissButton = { TextButton(enabled = !working,onClick = { confirm = null }) { Text("취소") } })
    }
}
