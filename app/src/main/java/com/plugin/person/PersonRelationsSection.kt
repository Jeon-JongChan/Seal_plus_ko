package com.plugin.person

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 바깥 목록의 지연 구성을 유지하면서 보이는 후보·그룹만 그립니다. */
fun LazyListScope.personRelations(state: PersonUiState, thresholds: List<PersonThreshold>?, strength: Int, showGroups: Boolean,
    onSelect: (Boolean,Int?) -> Unit) {
    val relations = state.relations ?: return
    item(key="person-relations-summary") {
        Text("다중 인물 비교 · ${relations.comparisons}쌍 · ${relations.elapsedMs}ms",style=MaterialTheme.typography.titleMedium)
        Text("추적 ID는 단기 이동 연결입니다. 아래 그룹은 구성원 모든 쌍이 현재 승인 기준을 만족하는 후보이며 신원 확인을 뜻하지 않습니다.",style=MaterialTheme.typography.bodySmall)
        if (state.firstFaces.firstOrNull()?.trackId != null || state.secondFaces.firstOrNull()?.trackId != null)
            Text("현재 임계값은 LFW 사진 개발 검증 기준입니다. 영상 집계 특징의 별도 검증은 아직 완료되지 않았습니다.",style=MaterialTheme.typography.bodySmall)
    }
    if (!showGroups) {
        // 항목을 바깥 LazyColumn에 등록해 화면 밖 썸네일을 읽지 않습니다.
        item(key="person-candidates-controls") {
            CandidateControls(state,onSelect)
        }
        val candidates = if (state.selectedFirst == null) relations.candidates.flatten()
            else relations.candidates.getOrNull(state.selectedFirst).orEmpty()
        // ponytail: 입력 A의 각 얼굴당 정확한 상위 20개 B를 보관합니다. 더 큰 전체 검색은 저장된 얼굴 검색을 사용합니다.
        itemsIndexed(candidates,key={ _,pair -> "person-pair:${pair.first}:${pair.second}" }) { _,pair ->
            val first = state.firstFaces[pair.first]; val second = state.secondFaces[pair.second]
            val threshold = thresholds?.get(strength-1)
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    FacePreview(first,"입력 A 얼굴 ${pair.first+1}")
                    FacePreview(second,"입력 B 얼굴 ${pair.second+1}")
                }
                Text(faceCaption("A",pair.first,first))
                Text(faceCaption("B",pair.second,second))
                Text("유사도 ${"%.4f".format(pair.score)} · ${if (threshold == null) "판정 보류" else PersonMath.decide(pair.score,threshold.reject,threshold.accept,pair.quality)}")
                (first.issues+second.issues).distinct().forEach { Text("품질 확인: $it",style=MaterialTheme.typography.bodySmall) }
                TextButton(onClick={ onSelect(true,pair.first); onSelect(false,pair.second) }) { Text("이 후보 선택") }
            } }
        }
        return
    }
    val groups = relations.groups[strength-1]
    item(key="person-groups-title") { Text("반복 등장 후보 그룹 · ${groups.size}개",style=MaterialTheme.typography.titleMedium) }
    itemsIndexed(groups,key={ _,group -> "person-group:${group.first()}" }) { index,group ->
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            val representative = group.first()
            val fromFirst = representative < state.firstFaces.size
            val position = if (fromFirst) representative else representative-state.firstFaces.size
            val face = (if (fromFirst) state.firstFaces else state.secondFaces)[position]
            val appearances by produceState<List<List<PersonInterval>>>(emptyList(),group,
                (state.firstFaces as? PersonPages)?.identity ?: state.firstFaces,(state.secondFaces as? PersonPages)?.identity ?: state.secondFaces) {
                value = withContext(Dispatchers.IO) {
                    val sources = listOfNotNull(state.firstFaces as? PersonPages,state.secondFaces as? PersonPages).distinctBy { it.identity }
                    val releases = sources.mapNotNull { source -> try { source.retain() } catch (_: IllegalStateException) { null } }
                    try {
                        ensureActive()
                        if (releases.size != sources.size) return@withContext emptyList()
                        val intervals = linkedMapOf<String?,MutableList<PersonInterval>>()
                        for (member in group) {
                            ensureActive()
                            val sample = if (member < state.firstFaces.size) state.firstFaces[member] else state.secondFaces[member-state.firstFaces.size]
                            if (sample.trackId != null) intervals.getOrPut(sample.sourceUri) { mutableListOf() }.addAll(sample.intervals)
                        }
                        intervals.values.map(PersonVideo::mergeIntervals)
                    } finally { releases.forEach { it() } }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FacePreview(face,"후보 그룹 ${index+1} 대표 얼굴")
                Column { Text("후보 그룹 ${index+1} · ${group.size}개 얼굴·트랙")
                    Text(if (group.size > 1) "모든 구성원 쌍의 승인 기준 충족" else "독립 후보 · 다른 트랙과 합치지 않음",style=MaterialTheme.typography.bodySmall) }
            }
            if (appearances.isNotEmpty()) Text("영상 등장 표본 구간 ${appearances.sumOf { it.size }}회 · 같은 원본의 겹친 구간은 합산하지 않음",style=MaterialTheme.typography.bodySmall)
            var shown by remember(group) { mutableIntStateOf(8) }
            group.take(shown).forEach { member ->
                val isFirst = member < state.firstFaces.size
                val itemIndex = if (isFirst) member else member-state.firstFaces.size
                Text(faceCaption(if (isFirst) "A" else "B",itemIndex,(if (isFirst) state.firstFaces else state.secondFaces)[itemIndex]))
            }
            if (shown < group.size) TextButton(onClick={ shown += 8 }) { Text("등장 구간 더 보기 (${group.size-shown}개)") }
            TextButton(onClick={ onSelect(fromFirst,position) }) { Text("대표 얼굴 선택") }
        } }
    }
}

/** 전체 후보와 특정 얼굴의 후보를 전환하며 점수는 다시 계산하지 않습니다. */
@Composable
private fun CandidateControls(state: PersonUiState,onSelect: (Boolean,Int?) -> Unit) {
    if (state.secondFaces.isNotEmpty()) {
        Text(if (state.selectedFirst == null) "입력 A의 각 얼굴마다 상위 20개 B 후보" else "선택한 A 얼굴 ${state.selectedFirst+1}의 상위 20개 B 후보")
        if (state.selectedFirst != null) TextButton(onClick={ onSelect(true,null) }) { Text("전체 A 후보 보기") }
    }
}

/** 후보별 입력 위치·추적 ID·등장 구간을 한 곳에서 같은 형식으로 표시합니다. */
private fun faceCaption(input: String,index: Int,face: PersonSample): String = buildString {
    append("$input 얼굴 ${index+1}")
    face.trackId?.let { append(" · 추적 ID $it · ${face.frames}개 표본") }
    face.intervals.forEach { append(" · ${it.startMs/1000.0}–${it.endMs/1000.0}초") }
}
