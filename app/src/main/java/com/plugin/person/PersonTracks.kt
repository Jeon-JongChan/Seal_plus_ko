package com.plugin.person

import android.graphics.RectF

/** 활성 트랙의 제한된 표본과 종료 트랙의 대표 특징을 구분합니다. */
private data class PersonTrack(val id: Int, var bounds: RectF, var lastMs: Long,
    val intervals: MutableList<PersonInterval>, val samples: MutableList<PersonSample>, var frames: Int = 1,
    var completed: Boolean = false)

/** 단기 연결만 활성 목록에서 검사하고 종료된 표본은 대표 벡터 하나로 축약합니다. */
internal class PersonTracks(private val uri: String, private val fingerprint: String,
    saved: List<PersonVideoTrackState> = emptyList()) : AutoCloseable {
    private val tracks = mutableListOf<PersonTrack>()
    private val active = mutableListOf<PersonTrack>()
    private val used = mutableSetOf<Int>()
    private var time = 0L
    private var step = 500L
    /** 최근 프레임의 연결 후보 수이며 총 영상 길이에 따라 늘어나지 않습니다. */
    val activeCount: Int get() = active.size
    /** 검사에서 종료 표본 축약을 확인하는 실제 보유 벡터 수입니다. */
    val retainedVectors: Int get() = tracks.sumOf { it.samples.size }

    init {
        saved.forEach { track ->
            val restored = PersonTrack(track.id,RectF(track.bounds[0],track.bounds[1],track.bounds[2],track.bounds[3]),track.lastMs,
                track.intervals.toMutableList(),track.samples.map { face -> PersonSample(null,face.vector,face.issues,fingerprint,
                    sourceUri=uri,landmarks=face.landmarks,bounds=face.bounds,representativeMs=face.representativeMs) }.toMutableList(),
                track.frames,track.completed)
            tracks += restored
            if (!restored.completed) active += restored
        }
    }

    /** 장면이 바뀌거나 최대 연결 창 2초를 벗어난 트랙은 다시 연결하지 않습니다. */
    fun beginFrame(timestamp: Long, interval: Long, sceneCut: Boolean) {
        require(timestamp >= time && interval in 1..1000)
        time = timestamp; step = interval; used.clear()
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val track = iterator.next()
            if (sceneCut || time-track.lastMs > 2000) { complete(track); iterator.remove() }
        }
    }

    /** 단일 후보만 재사용하며 반환값이 참일 때만 전달한 벡터의 소유권을 인수합니다. */
    fun offer(face: PersonFace, sample: PersonSample): Boolean {
        require(sample.fingerprint == fingerprint)
        // ponytail: IoU와 근접 프레임 특징은 단기 연결 휴리스틱입니다. ID 교환 실측이 나쁘면 검증된 추적기로 교체합니다.
        val candidates = active.filter { track ->
            track.id !in used && time-track.lastMs <= step*2 && intersection(track.bounds,face.bounds) > .3f &&
                PersonMath.cosine(representative(track.samples).vector,sample.vector) > .5
        }
        val track = candidates.singleOrNull()
        if (track == null) {
            val created = PersonTrack(Math.addExact(tracks.size,1),RectF(face.bounds),time,
                mutableListOf(PersonInterval(time,time)),mutableListOf(sample))
            tracks += created; active += created; used += created.id
            return true
        }
        track.bounds = RectF(face.bounds); track.lastMs = time; track.frames = Math.addExact(track.frames,1)
        val last = track.intervals.last()
        track.intervals[track.intervals.lastIndex] = last.copy(endMs=time)
        used += track.id
        if (sample.issues.isEmpty() && track.samples.size < 5) { track.samples += sample; return true }
        return false
    }

    /** 활성 표본과 이미 축약된 트랙을 구분해 저장하므로 재개 후 다시 평균하지 않습니다. */
    fun snapshot(): List<PersonVideoTrackState> = tracks.map { track ->
        PersonVideoTrackState(track.id,floatArrayOf(track.bounds.left,track.bounds.top,track.bounds.right,track.bounds.bottom),
            track.lastMs,track.intervals.toList(),track.samples.map { sample -> PersonStoredFace(sample.vector,sample.issues,
                sample.landmarks,sample.bounds,representativeMs=sample.representativeMs) },track.frames,track.completed)
    }

    /** 호출자 소유 벡터로 반환하여 추적기 종료와 화면 결과의 수명을 분리합니다. */
    fun results(): List<PersonSample> {
        active.forEach(::complete); active.clear()
        return tracks.map { track -> track.samples.single().copy(vector=track.samples.single().vector.clone(),
            trackId=track.id,intervals=track.intervals.toList(),frames=track.frames) }
    }

    /** 품질을 통과한 표본 중 다른 표본과 가장 일관된 실제 얼굴을 선택합니다. */
    private fun representative(samples: List<PersonSample>): PersonSample {
        val good = samples.filter { it.issues.isEmpty() }.ifEmpty { samples }
        // 첫 프레임에 고정하지 않고 최대 다섯 표본의 합의 중심을 사용합니다.
        return good.maxBy { candidate -> good.sumOf { PersonMath.cosine(candidate.vector,it.vector) } }
    }

    /** 합의 중심 주변 표본만 평균하고 불필요한 원본 벡터를 비웁니다. */
    private fun complete(track: PersonTrack) {
        if (track.completed) return
        val good = track.samples.filter { it.issues.isEmpty() }
        val representatives = good.ifEmpty { track.samples }
        val center = representative(representatives)
        val vectors = representatives.filter { PersonMath.cosine(it.vector,center.vector) > .5 }
        val mean = FloatArray(128) { i -> (vectors.sumOf { it.vector[i].toDouble() }/vectors.size).toFloat() }
        val issues = if (vectors.size < 2) (center.issues+"다중 프레임 근거 부족").distinct() else center.issues
        val result = center.copy(vector=PersonMath.normalize(mean),issues=issues)
        track.samples.forEach { it.vector.fill(0f) }; track.samples.clear(); track.samples += result
        track.completed = true
    }

    /** 단기 위치 연결용 IoU이며 동일인물 승인 기준과 분리합니다. */
    private fun intersection(a: RectF,b: RectF): Float {
        val area = maxOf(0f,minOf(a.right,b.right)-maxOf(a.left,b.left))*maxOf(0f,minOf(a.bottom,b.bottom)-maxOf(a.top,b.top))
        val union = a.width()*a.height()+b.width()*b.height()-area
        return if (union>0) area/union else 0f
    }

    /** 미반환 중간 벡터만 비우고 복사해 반환한 결과는 유지합니다. */
    override fun close() {
        tracks.forEach { track -> track.samples.forEach { it.vector.fill(0f) } }
        tracks.clear(); active.clear(); used.clear()
    }
}
