package com.plugin.person

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** 구간은 표본의 실제 타임스탬프 범위를 나타내며 연속 프레임 검증을 뜻하지 않습니다. */
@Serializable
data class PersonInterval(val startMs: Long, val endMs: Long)

/** 완료 프레임의 단기 연결 상태와 제한된 대표 특징만 저장합니다. */
@Serializable
data class PersonVideoTrackState(val id: Int, val bounds: FloatArray, val lastMs: Long,
    val intervals: List<PersonInterval>, val samples: List<PersonStoredFace>, val frames: Int, val completed: Boolean = false)

/** 원본·시간·장면 표본까지 묶어 중단 전과 동일한 분석 간격으로 재개합니다. */
@Serializable
data class PersonVideoCheckpoint(val source: PersonSource, val nextUs: Long, val stepMs: Long,
    val signature: FloatArray, val durationMs: Long, val tracks: List<PersonVideoTrackState>, val version: Int = 1,
    val trackCount: Int = tracks.size)

/** 프레임을 한 장씩 읽고 얼굴 이동·장면 변화를 고려해 분석 간격을 조절합니다. */
object PersonVideo {
    /** 겹친 표본 구간만 합치며 관측하지 않은 사이 시간을 임의로 채우지 않습니다. */
    fun mergeIntervals(intervals: List<PersonInterval>): List<PersonInterval> {
        require(intervals.all { it.startMs >= 0 && it.endMs >= it.startMs })
        val result = mutableListOf<PersonInterval>()
        for (interval in intervals.sortedWith(compareBy<PersonInterval> { it.startMs }.thenBy { it.endMs })) {
            val last = result.lastOrNull()
            if (last != null && interval.startMs <= last.endMs) result[result.lastIndex] = last.copy(endMs=maxOf(last.endMs,interval.endMs))
            else result += interval
        }
        return result
    }

    /** 사진 비교와 같은 엔진으로 여러 얼굴을 추적하고 각 트랙의 표본만 반환합니다. */
    suspend fun analyze(context: Context, uri: Uri, engine: PersonOnnx, fingerprint: String,
        store: PersonStore? = null, source: PersonSource? = null,
        progress: (Long, Long) -> Unit): List<PersonSample> {
        val decoder = PersonFrames(context,uri)
        var tracker: PersonTracks? = null
        try {
            val duration = decoder.durationMs
            val saved = if (store != null && source != null) store.videoCheckpoint(source,fingerprint) else null
            check(saved == null || saved.durationMs == duration) { "영상 체크포인트의 길이가 다릅니다." }
            val tracking = PersonTracks(uri.toString(),fingerprint,saved?.tracks.orEmpty()).also { tracker = it }
            var step = saved?.stepMs ?: 500L; var previous: FloatArray? = saved?.signature
            var checkpointUs = saved?.nextUs ?: -1L
            var checkpointWall = android.os.SystemClock.elapsedRealtime()
            decoder.read(saved?.nextUs ?: 0) { bitmap,time ->
                currentCoroutineContext().ensureActive()
                progress(time,duration)
                val thumbnail = signature(bitmap)
                val change = previous?.let { old -> old.indices.sumOf { kotlin.math.abs(old[it]-thumbnail[it]).toDouble() }/old.size } ?: 255.0
                val sceneCut = change > 35
                val faces = engine.detect(bitmap)
                tracking.beginFrame(time,step,sceneCut)
                for (face in faces) {
                    currentCoroutineContext().ensureActive()
                    val aligned = engine.align(bitmap,face)
                    var owned = false
                    var sample: PersonSample? = null
                    try {
                        val current = PersonSample(null,engine.embed(aligned),PersonQuality.issues(face,aligned),fingerprint,
                            sourceUri = uri.toString(),landmarks = face.landmarks,bounds = floatArrayOf(face.bounds.left,face.bounds.top,face.bounds.right,face.bounds.bottom),representativeMs = time)
                        sample = current
                        owned = tracking.offer(face,current)
                    } finally {
                        // 영상 전체의 정렬 이미지를 보관하지 않고 보이는 대표 얼굴만 나중에 복원합니다.
                        aligned.recycle()
                        if (!owned) sample?.vector?.fill(0f)
                    }
                }
                previous = thumbnail
                // 사람이 없고 장면도 안정적이면 간격을 늘리되 최대 1초마다 다시 검출합니다.
                step = if (faces.isEmpty() && !sceneCut) minOf(1000,step+250) else 250
                val nextUs = Math.addExact(decoder.timeUs,step*1000)
                val now = android.os.SystemClock.elapsedRealtime()
                if (store != null && source != null && (checkpointUs < 0 || nextUs-checkpointUs >= 30_000_000 || now-checkpointWall >= 10_000)) {
                    // 중간 프레임 처리 실패 시 이전 확정 지점을 유지하며 부분 변경된 추적 상태는 저장하지 않습니다.
                    val state = PersonVideoCheckpoint(source,nextUs,step,thumbnail,duration,tracking.snapshot())
                    store.saveVideoCheckpoint(fingerprint,state)
                    checkpointUs = nextUs; checkpointWall = now
                }
                step
            }
            progress(duration,duration)
            // ponytail: 종료 시 전체 결과를 한 번 펼칩니다. 분석 중 메모리 상한을 위해 종료 트랙 즉시 스풀로 교체해야 합니다.
            val result = tracking.results()
            try { return PersonPages.create(context,uri.toString(),fingerprint,result) }
            finally { PersonPages.release(result,true) }
        } finally {
            try { decoder.close() } finally { tracker?.close() }
        }
    }

    /** 작은 명암 그리드만 유지하여 이전 전체 프레임을 메모리에 남기지 않습니다. */
    private fun signature(bitmap: Bitmap): FloatArray {
        val small = Bitmap.createScaledBitmap(bitmap,16,16,true)
        val pixels = IntArray(256)
        try { small.getPixels(pixels,0,16,0,0,16,16) } finally { if (small !== bitmap) small.recycle() }
        return FloatArray(256) { i -> ((pixels[i] ushr 16 and 255)+(pixels[i] ushr 8 and 255)+(pixels[i] and 255))/3f }
    }

}
