package com.plugin.person

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugin.faces.FaceImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** 화면·결과 페이지·저장소가 공통으로 사용하는 얼굴 특징과 원본 참조입니다. */
data class PersonSample(val preview: Bitmap?, val vector: FloatArray, val issues: List<String>, val fingerprint: String,
    val trackId: Int? = null, val intervals: List<PersonInterval> = emptyList(), val frames: Int = 1,
    val sourceUri: String? = null, val landmarks: FloatArray = floatArrayOf(), val bounds: FloatArray = floatArrayOf(),
    val representativeMs: Long = 0, val evidence: List<PersonEvidence> = emptyList())

/** 현재 선택한 두 이미지의 상태와 추론 측정값입니다. */
data class PersonUiState(val first: Uri? = null, val second: Uri? = null,
    val firstFaces: List<PersonSample> = emptyList(), val secondFaces: List<PersonSample> = emptyList(),
    val selectedFirst: Int? = null, val selectedSecond: Int? = null,
    val busy: Boolean = false, val message: String = "", val elapsedMs: Long = 0,
    val metrics: String = "", val relations: PersonRelations? = null)

/** 회전에도 분석을 유지하고 모델 실행·해제를 동일한 전용 스레드에 직렬화합니다. */
class PersonViewModel(private val context: Context) : ViewModel() {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mutable = MutableStateFlow(PersonUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    /** 사용자가 새 입력을 선택하면 이전 이미지의 임베딩을 함께 무효화합니다. */
    fun input(first: Boolean, uri: Uri) {
        if (mutable.value.busy) return
        if (uri.scheme == "content") {
            // 직접 비교 뒤 보관한 원본도 앱 재시작 후 읽을 수 있게 문서 제공자의 허용 범위에서 권한을 유지합니다.
            try { context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { /* 영속 권한 미지원 제공자는 현재 세션의 읽기 권한으로 분석합니다. */ }
        }
        clearSamples()
        mutable.value = if (first) mutable.value.copy(first = uri) else mutable.value.copy(second = uri)
    }

    /** 한 이미지에 여러 얼굴이 있으면 사용자 선택 전까지 대상을 확정하지 않습니다. */
    fun select(first: Boolean, index: Int?) {
        val value = mutable.value
        require(index == null || index in (if (first) value.firstFaces else value.secondFaces).indices)
        mutable.value = if (first) value.copy(selectedFirst = index) else value.copy(selectedSecond = index)
    }

    /** 디코딩·검출·정렬·추출을 UI와 분리하고 중복 실행과 네이티브 동시 사용을 막습니다. */
    fun analyze(model: String, single: Boolean = false) {
        val value = mutable.value
        if (value.busy || value.first == null || !single && value.second == null) return
        clearSamples()
        mutable.update { it.copy(busy = true, message = "얼굴 모델 준비 중") }
        job = viewModelScope.launch(dispatcher) {
            var first: List<PersonSample> = emptyList(); var second: List<PersonSample> = emptyList()
            val start = System.nanoTime()
            try {
                val preferences = context.getSharedPreferences("person-ai",0)
                val store = if (com.plugin.compatibility.TemporarySession.root == null) PersonStore(context) else null
                try { PersonOnnx(context,model,preferences.getString("backend","auto") ?: "auto",preferences.getBoolean("fallback",true)).use { engine ->
                    val analyzer = PersonAnalyzer(context,engine,model,store)
                    val inputs = if (single) listOf(value.first) else listOf(value.first,value.second!!)
                    for ((index,uri) in inputs.withIndex()) {
                        ensureActive()
                        if (index == 1 && uri == value.first) { second = first; continue }
                        mutable.update { it.copy(message = if (index == 0) "입력 A 분석 중" else "입력 B 분석 중") }
                        val samples = analyzer.analyze(uri) { message -> mutable.update { it.copy(message = message) } }
                        if (index == 0) first = samples else second = samples
                    }
                    ensureActive()
                    mutable.update { it.copy(message = if (single) "예시 얼굴 정리 중" else "여러 얼굴 후보·반복 등장 그룹 비교 중") }
                    // 예시 얼굴 등록에서는 사용하지 않는 전체 얼굴 쌍·5단계 그룹 계산을 생략합니다.
                    val relations = if (single) null else PersonRelations.build(first,second,
                        PersonCalibration.load(context,PersonModels.models.single { it.id == model }))
                    ensureActive()
                    mutable.update { it.copy(firstFaces = first,secondFaces = second,
                        selectedFirst = if (first.size == 1) 0 else null,
                        selectedSecond = if (second.size == 1) 0 else null,
                        elapsedMs = (System.nanoTime()-start)/1_000_000,relations = relations,
                        metrics = "${engine.backend}\n초기화 ${engine.initializationNs/1_000_000}ms · 검출 ${engine.detectionNs/1_000_000}ms · 정렬 ${engine.alignmentNs/1_000_000}ms · 임베딩 ${engine.embeddingNs/1_000_000}ms",
                        message = if (first.isEmpty() || !single && second.isEmpty()) "얼굴이 없는 입력이 있습니다. 다시 선택하세요."
                            else if (single) "단일 입력 분석 완료 · 추적 ID는 동일인물 확정을 뜻하지 않습니다." else "각 입력에서 비교할 얼굴을 선택하세요.") }
                    first = emptyList(); second = emptyList()
                } } finally { store?.close() }
            } catch (cancel: CancellationException) { mutable.update { it.copy(message = "분석을 취소했습니다.") }; throw cancel }
            catch (failure: Exception) { mutable.update { it.copy(message = failure.message ?: "이미지 분석 실패") } }
            catch (failure: LinkageError) { mutable.update { it.copy(message = "이 기기에서 모델 런타임을 불러올 수 없습니다.") } }
            finally {
                PersonPages.release(first,second,true)
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    /** 네이티브 실행 중 취소는 현재 호출 반환 직후 반영합니다. */
    fun cancel() { job?.cancel() }

    /** 화면 결과의 소유권을 반환하고 표시 중인 항목 해제 후 임시 결과를 정리합니다. */
    fun clearSamples() {
        if (mutable.value.busy) return
        val old = mutable.value
        mutable.value = old.copy(firstFaces = emptyList(),secondFaces = emptyList(),selectedFirst = null,selectedSecond = null,
            message = "",metrics = "",elapsedMs = 0,relations = null)
        // Compose가 이전 프레임을 그릴 수 있으므로 노출한 비트맵은 참조 해제 후 GC에 맡깁니다.
        PersonPages.release(old.firstFaces,old.secondFaces)
    }

    /** 화면 수명 종료 시 작업을 취소하고 보관한 생체정보를 정리합니다. */
    override fun onCleared() {
        job?.cancel()
        val old = mutable.value
        PersonPages.release(old.firstFaces,old.secondFaces)
        dispatcher.close()
    }
}
