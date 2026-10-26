package com.plugin.person

import android.content.Context
import com.plugin.compatibility.enhancement.EnhancementModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 고정 해시·버전의 얼굴 인식 모델과 기본 오프라인 가중치를 관리합니다. */
object PersonModels {
    /** 서로 다른 버전·양자화 모델의 임베딩 캐시를 구분하는 계약입니다. */
    data class Model(val id: String, val label: String, val bytes: Long,
        val bundled: Boolean, val recommendation: EnhancementModels.Recommendation, val version: String = "2021dec") {
        val fingerprint: String get() = "$id:$version:${recommendation.sha256}:sface-five-landmark-similarity-v1:${PersonModels.detector.recommendation.sha256}:track-v4-consensus:quality-v2-eye-axis"
    }

    val models = listOf(
        Model("sface-lite", "SFace MobileFaceNet FP16 · LiteRT", 19_296_152, true,
            EnhancementModels.Recommendation("sface-lite.tflite", "https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface",
                "c0195bb9be3b6797aabbb19126baf26c2f46e308b1e658263f36212b05fb0861", "얼굴 인식 · Apache-2.0 · FP32 원본에서 변환 · RGB 112×112"),version = "2021dec-fp16-v1"),
        model("sface-int8", "SFace MobileFaceNet INT8 · 2021dec", 9_896_933, true,
            "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a", "face_recognition_sface_2021dec_int8.onnx"),
        model("sface-fp32", "SFace MobileFaceNet FP32 · 2021dec", 38_696_353, false,
            "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79", "face_recognition_sface_2021dec.onnx")
    )
    private val detector = Model("yunet-person", "YuNet 얼굴 검출", 240_104, true,
        EnhancementModels.Recommendation("yunet-person.tflite", "https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet",
            "0c41ce007ed8295b32dbb9a03363eb6fbd14da958809c80348158f2f088e33c9", "얼굴 좌표·다섯 랜드마크 · MIT · BGR 640×640"))

    /** 출처·상업적 재배포 라이선스를 확인한 공식 모델만 등록합니다. */
    private fun model(id: String, label: String, bytes: Long, bundled: Boolean, sha: String, filename: String) =
        Model(id, label, bytes, bundled, EnhancementModels.Recommendation("$id.onnx",
            "https://media.githubusercontent.com/media/opencv/opencv_zoo/refs/heads/main/models/face_recognition_sface/$filename?download=1",
            sha, "얼굴 인식 · Apache-2.0 · 입력 RGB 112×112 · 출력 128차원"))

    /** 생체정보와 독립된 앱 전용 모델 저장소입니다. */
    fun root(context: Context): File = File(context.filesDir, "person-models").apply {
        check(isDirectory || mkdirs()) { "얼굴 모델 폴더를 만들 수 없습니다." }
    }

    /** 내장 모델도 처음 사용할 때 복사·크기·해시 검증 후 원자적으로 설치합니다. */
    fun requireFile(context: Context, id: String): File {
        val model = models.singleOrNull { it.id == id } ?: error("지원하지 않는 얼굴 모델입니다.")
        return requireFile(context,model)
    }

    /** 검출기도 기본 제공 모델과 같은 고정 해시·원자적 복구 절차를 사용합니다. */
    fun requireDetector(context: Context): File = requireFile(context,detector)

    /** 모든 내장·다운로드 모델 파일은 검증된 설치본만 반환합니다. */
    @Synchronized
    private fun requireFile(context: Context, model: Model, restore: Boolean = false): File {
        val preferences=context.getSharedPreferences("person-models",0)
        check(restore || !preferences.getBoolean(model.id,false)) { "삭제한 모델입니다. 모델 목록에서 다시 설치하세요." }
        val file = File(root(context), model.recommendation.name)
        if (model.bundled && (!file.isFile || file.length() != model.bytes || EnhancementModels.hash(file) != model.recommendation.sha256)) {
            val atomic = android.util.AtomicFile(file)
            val output = atomic.startWrite()
            try {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                var total = 0L
                context.assets.open("person-ai/${model.recommendation.name}").use { input ->
                    val buffer = ByteArray(64*1024)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; check(total <= model.bytes) { "내장 얼굴 모델 크기가 다릅니다." }
                        digest.update(buffer,0,count); output.write(buffer,0,count)
                    }
                }
                // 검증하지 않은 파일은 최종 설치본으로 확정하지 않습니다. 이전 손상 설치본도 복구합니다.
                check(total == model.bytes && digest.digest().joinToString("") { "%02x".format(it) } == model.recommendation.sha256) {
                    "내장 얼굴 모델 무결성 검증에 실패했습니다."
                }
                atomic.finishWrite(output)
            } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        }
        check(file.isFile && file.length() == model.bytes && EnhancementModels.hash(file) == model.recommendation.sha256) {
            "얼굴 모델이 없거나 무결성 검증에 실패했습니다. 기본 모델을 선택하거나 다시 다운로드하세요."
        }
        // 명시적 재설치가 검증까지 끝난 경우에만 사용자가 지정한 삭제 상태를 해제합니다.
        if (restore) check(preferences.edit().remove(model.id).commit()) { "모델 설치 상태를 저장할 수 없습니다." }
        return file
    }

    /** 화면 검증은 UI 스레드를 막지 않으며 기본 모델은 오프라인으로 준비합니다. */
    suspend fun installed(context: Context): Set<String> = withContext(Dispatchers.IO) {
        models.filter { runCatching { requireFile(context,it.id) }.isSuccess }.map { it.id }.toSet()
    }

    /** 내장 모델은 오프라인으로 복원하고 추가 모델은 공통 검증 다운로더로 재설치합니다. */
    suspend fun download(context: Context, model: Model, progress: (String) -> Unit) {
        require(model in models)
        if (!model.bundled) EnhancementModels.download(context,model.recommendation.url,model.recommendation,progress)
        withContext(Dispatchers.IO) { requireFile(context,model,restore=true) }
    }

    /** 설치본을 제거하고 자동 복원을 막으며 요청한 모델의 캐시만 선택적으로 지웁니다. */
    suspend fun uninstall(context: Context, model: Model, clearCache: Boolean) = withContext(Dispatchers.IO) {
        require(model in models)
        // 분석기 생성과 같은 잠금에서 사용 여부를 확인해 삭제 도중 새 분석이 시작되지 않게 합니다.
        synchronized(com.plugin.compatibility.TemporarySession) {
            synchronized(this@PersonModels) {
                check(!PersonIndexService.isRunning() && !PersonSortService.state.value.running && !PersonOnnx.isBusy()) { "얼굴 분석·분류를 중단한 뒤 모델을 삭제하세요." }
                val file = File(root(context),model.recommendation.name)
                check(!file.exists() || file.delete()) { "모델 파일을 삭제할 수 없습니다." }
                check(context.getSharedPreferences("person-models",0).edit().putBoolean(model.id,true).commit()) { "모델 삭제 상태를 저장할 수 없습니다." }
                if (clearCache) PersonStore(context).use { it.clear(model.fingerprint) }
            }
        }
    }
}
