package com.plugin.person

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.plugin.faces.FaceImages
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest

/** UI 비교와 사전 분석 큐가 모델·해시·품질·영구 캐시 흐름을 공유합니다. */
class PersonAnalyzer(private val context: Context, private val engine: PersonOnnx,
    private val model: String, private val store: PersonStore? = null) {
    private val fingerprint = PersonModels.models.single { it.id == model }.fingerprint
    var reused = false
        private set

    companion object {
        private val previews = Semaphore(2)
        private val imageMaximum = com.plugin.faces.FaceRules.resolutions.max()

        /** 저장된 좌표로 보이는 얼굴만 재디코딩하며 AI 추론은 반복하지 않습니다. */
        suspend fun preview(context: Context, sample: PersonSample): Bitmap? = previews.withPermit {
            val uri = sample.sourceUri?.let(Uri::parse) ?: return@withPermit null
            if (sample.landmarks.size != 10) return@withPermit null
            val bitmap = if (sample.trackId == null) FaceImages.decode(context,uri.toString(),imageMaximum) else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context,uri)
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return@withPermit null
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return@withPermit null
                    val scale = minOf(1.0,640.0/maxOf(width,height))
                    if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(sample.representativeMs*1000,
                        MediaMetadataRetriever.OPTION_CLOSEST,maxOf(1,(width*scale).toInt()),maxOf(1,(height*scale).toInt()))
                    else null
                } finally { retriever.release() }
            } ?: return@withPermit null
            try {
                val matrix = PersonMath.alignment(sample.landmarks)
                val transform = Matrix().apply { setValues(floatArrayOf(matrix[0],matrix[1],matrix[2],matrix[3],matrix[4],matrix[5],0f,0f,1f)) }
                Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888).also {
                    Canvas(it).drawBitmap(bitmap,transform,Paint(Paint.FILTER_BITMAP_FLAG))
                }
            } finally { bitmap.recycle() }
        }

        /** 검색 시 삭제·권한 철회·명백한 파일 변경을 확인할 수 있게 메타데이터를 읽습니다. */
        fun metadata(context: Context, uri: Uri): Triple<Long,Long,Boolean> {
            require(uri.scheme in setOf("file","content")) { "기기 내부 파일만 분석할 수 있습니다." }
            val document = if (uri.scheme == "content") DocumentFile.fromSingleUri(context,uri) else null
            val file = if (uri.scheme == "file") File(requireNotNull(uri.path)) else null
            if (file != null) {
                check(file.isFile && file.canRead()) { "원본 파일 접근 권한이 없거나 삭제되었습니다." }
            } else {
                // 사진 선택기 등 일반 content 제공자는 문서 메타데이터가 없어도 읽을 수 있습니다.
                // 실제 열기를 확인하며, 읽을 수 없는 원본을 캐시만으로 처리하지 않습니다.
                context.contentResolver.openInputStream(uri)?.use { }
                    ?: error("원본 파일을 열 수 없습니다. 사진이나 작업 폴더를 다시 선택하세요.")
            }
            val name = file?.name ?: document?.name.orEmpty()
            val video = context.contentResolver.getType(uri)?.startsWith("video/") == true ||
                name.substringAfterLast('.').lowercase() in setOf("mp4","mkv","mov","webm","avi","3gp","m4v","mts")
            return Triple(file?.length() ?: document!!.length(),file?.lastModified() ?: document!!.lastModified(),video)
        }
    }

    /** 큰 원본은 스트리밍 해시로 중복 분석을 방지하며 취소를 청크마다 확인합니다. */
    private suspend fun digest(uri: Uri): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val input = if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
            else context.contentResolver.openInputStream(uri) ?: error("원본 파일을 열 수 없습니다.")
        input.use {
            val buffer = ByteArray(128*1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = it.read(buffer); if (size < 0) break
                hash.update(buffer,0,size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    /** 같은 모델·전처리·원본 내용은 재추론 없이 불러오고 신규 파일만 분석합니다. */
    suspend fun analyze(uri: Uri, progress: (String) -> Unit): List<PersonSample> {
        reused = false
        val (bytes,modified,video) = metadata(context,uri)
        val previous = store?.source(uri.toString())
        // ponytail: 정상 파일의 크기·수정 시각을 재사용합니다. 시각 보존 변조까지 지원해야 하면 전체 해시 강제 옵션을 추가합니다.
        val content = if (previous != null && modified > 0 && previous.bytes == bytes && previous.modified == modified) previous.content
            else if (store != null) digest(uri) else ""
        // 동일 내용의 재분석·캐시 적중은 사용자 분류 태그를 보존하고 변경된 내용은 초기화합니다.
        val source = if (previous?.content == content) previous.copy(bytes=bytes,modified=modified,video=video)
            else PersonSource(uri.toString(),content,bytes,modified,video)
        store?.load(content,fingerprint)?.let { record ->
            // 사진 캐시는 유지하고 시점 근거가 없는 구버전 영상만 실제 사용 시 한 번 갱신합니다.
            if (video && record.videoEvidenceVersion != 1) {
                progress("기존 영상 캐시의 시점별 근거 갱신 중 · 최초 한 번 재분석")
                return@let
            }
            store.bind(source); reused = true; progress("저장된 얼굴 특징 재사용 · AI 추론 생략")
            val result = record.faces.asSequence().map { face -> PersonSample(null,face.vector,face.issues,fingerprint,face.trackId,face.intervals,
                face.frames,uri.toString(),face.landmarks,face.bounds,face.representativeMs,face.evidence) }.asIterable()
            return PersonPages.create(context,uri.toString(),fingerprint,result)
        }
        val samples = if (video) PersonVideo.analyze(context,uri,engine,fingerprint,store,source) { done,total -> progress("영상 분석 ${done/1000}/${total/1000}초") }
            else {
                progress("이미지 얼굴 검출·정렬·특징 추출 중")
                val bitmap = FaceImages.decode(context,uri.toString(),imageMaximum)
                try {
                    val coroutine = currentCoroutineContext()
                    val result = sequence {
                        coroutine.ensureActive()
                        for (face in engine.detect(bitmap)) {
                            coroutine.ensureActive()
                            val aligned = engine.align(bitmap,face)
                            val sample = try { PersonSample(null,engine.embed(aligned),PersonQuality.issues(face,aligned),fingerprint,
                                sourceUri=uri.toString(),landmarks=face.landmarks,
                                bounds=floatArrayOf(face.bounds.left,face.bounds.top,face.bounds.right,face.bounds.bottom)) }
                            finally { aligned.recycle() }
                            yield(sample)
                        }
                    }
                    PersonPages.create(context,uri.toString(),fingerprint,result.asIterable())
                } finally { bitmap.recycle() }
            }
        try {
            currentCoroutineContext().ensureActive()
            if (store != null) {
                // 분석 중 바뀐 원본에 이전 프레임의 임베딩을 연결하지 않습니다.
                val after = metadata(context,uri)
                check(after.first == bytes && after.second == modified) { "분석 중 원본 파일이 변경되었습니다. 다시 등록하세요." }
                store.commit(source,PersonRecord(fingerprint,video,PersonPages.stored(samples),
                    modelId = model,modelVersion = PersonModels.models.single { it.id == model }.version,
                    videoEvidenceVersion=if (video) 1 else 0))
            }
            return samples
        } catch (failure: Throwable) { PersonPages.release(samples,true); throw failure }
    }
}
