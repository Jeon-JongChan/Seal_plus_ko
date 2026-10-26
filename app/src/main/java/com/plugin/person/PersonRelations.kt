package com.plugin.person

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.PriorityQueue

/** 입력 A·B의 얼굴 위치와 정확한 점수를 보존하며 추적 ID와 동일인물 결론을 구분합니다. */
data class PersonPair(val first: Int, val second: Int, val score: Double, val quality: Boolean)

/** 그룹 계산 동안만 특징과 품질 판정만 유지하고 원본 메타데이터는 디스크에 둡니다. */
private data class PersonFeature(val vector: FloatArray,val quality: Boolean)

/** 강도 변경 때 재추론·유사도 재계산 없이 후보와 완전 연결 그룹을 재사용합니다. */
data class PersonRelations(val candidates: List<List<PersonPair>>, val groups: List<List<IntArray>>,
    val comparisons: Long, val elapsedMs: Long) {
    companion object {
        /** 각 A의 상위 20개 B와 5단계 그룹만 남겨 큰 실수 행렬을 보관하지 않습니다. */
        suspend fun build(first: List<PersonSample>, second: List<PersonSample>, thresholds: List<PersonThreshold>?): PersonRelations {
            val started = System.nanoTime()
            require(thresholds == null || thresholds.size == 5)
            val faces = ArrayList<PersonFeature>()
            var fingerprint: String? = null
            for (source in listOf(first,second)) for (sample in source) {
                currentCoroutineContext().ensureActive()
                if (fingerprint == null) fingerprint = sample.fingerprint
                require(sample.fingerprint == fingerprint) { "서로 다른 모델의 특징을 비교할 수 없습니다." }
                require(sample.vector.size == 128); PersonMath.cosine(sample.vector,sample.vector)
                faces += PersonFeature(sample.vector,sample.issues.isEmpty() && (sample.trackId == null || sample.frames >= 2))
            }
            // 이전 얼굴의 그룹 소속만 있으면 현재 얼굴이 어느 그룹에 들어갈지 한 번의 쌍 비교로 결정할 수 있습니다.
            val groups = Array(5) { mutableListOf<MutableList<Int>>() }
            val membership = Array(5) { IntArray(faces.size) }
            val rejected = Array(5) { BooleanArray(faces.size) }
            val queues = Array(first.size) { PriorityQueue<PersonPair>(compareBy<PersonPair> { it.score }.thenByDescending { it.second }) }
            var comparisons = 0L
            for (i in faces.indices) {
                currentCoroutineContext().ensureActive()
                groups.indices.forEach { level -> java.util.Arrays.fill(rejected[level],0,groups[level].size,false) }
                for (j in 0 until i) {
                    val score = PersonMath.cosine(faces[i].vector,faces[j].vector)
                    comparisons++
                    val quality = faces[i].quality && faces[j].quality
                    for (level in groups.indices) {
                        if (!quality || thresholds == null || score < thresholds[level].accept)
                            rejected[level][membership[level][j]] = true
                    }
                    if (i >= first.size && j < first.size) {
                        val queue = queues[j]; val b = i-first.size; val worst = queue.peek()
                        if (queue.size < 20 || worst == null || score > worst.score || score == worst.score && b < worst.second) {
                            if (queue.size == 20) queue.poll()
                            queue += PersonPair(j,b,score,quality)
                        }
                    }
                }
                for (level in groups.indices) {
                    // 한 구성원이라도 거절한 그룹은 배제하므로 기존 완전 연결·순서 정책과 정확히 같습니다.
                    val selected = groups[level].indices.firstOrNull { !rejected[level][it] } ?: -1
                    val group = if (selected < 0) groups[level].size else selected
                    if (selected < 0) groups[level] += mutableListOf(i) else groups[level][group] += i
                    membership[level][i] = group
                }
            }
            return PersonRelations(queues.map { it.sortedWith(compareByDescending<PersonPair> { pair -> pair.score }.thenBy { pair -> pair.second }) },
                groups.map { level -> level.map { it.toIntArray() } },comparisons,(System.nanoTime()-started)/1_000_000)
        }
    }
}
