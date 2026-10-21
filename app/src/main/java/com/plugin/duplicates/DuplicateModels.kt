package com.plugin.duplicates

import android.content.Context
import com.plugin.compatibility.enhancement.EnhancementModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 화질 개선과 다운로드·해시 검증을 공유하되 중복 분석 모델은 별도 폴더에 보관합니다. */
object DuplicateModels {
    /** 공식 배포 버전·크기·해시를 고정해 임의 모델의 실행을 막습니다. */
    data class Model(val id: String, val label: String, val bytes: Long, val dimensions: Int,
        val recommendation: EnhancementModels.Recommendation)

    val models = listOf(
        model("small", "Small", 4_117_670, 1024, "bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40"),
        model("large", "Large", 10_889_458, 1280, "11af3c560dfeed7737cb4c03c23bf52a8403020784192d4dea0b74862a12828d")
    )

    /** 모델 용도를 포함한 설명과 Google의 고정 버전 주소를 구성합니다. */
    private fun model(id: String, label: String, bytes: Long, dimensions: Int, sha: String) = Model(
        id, "MobileNetV3 $label", bytes, dimensions, EnhancementModels.Recommendation(
            "mobilenet_v3_$id.tflite",
            "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_$id/float32/1/mobilenet_v3_$id.tflite",
            sha, "중복 분석용 특징 추출 모델"))

    /** 미디어 저장소 전환·화질 개선 모델 검색과 독립된 앱 전용 위치입니다. */
    fun root(context: Context): File = File(context.filesDir, "duplicate-models").apply { check(isDirectory || mkdirs()) { "중복 모델 폴더를 만들 수 없습니다." } }

    /** 이름·크기뿐 아니라 실제 해시가 일치하는 설치 파일만 반환합니다. 다운로드는 하지 않습니다. */
    fun requireFile(context: Context, id: String): File {
        val model = models.singleOrNull { it.id == id } ?: error("지원하지 않는 중복 분석 모델입니다.")
        val file = File(root(context), model.recommendation.name)
        check(file.isFile && file.length() == model.bytes && EnhancementModels.hash(file) == model.recommendation.sha256) {
            "${model.label} 모델을 먼저 다운로드하세요. 미설치 또는 검증되지 않은 파일입니다."
        }
        return file
    }

    /** 화면의 파일 검증은 IO 스레드에서 실행하며 불완전한 파일은 준비 목록에 넣지 않습니다. */
    suspend fun installed(context: Context): Set<String> = withContext(Dispatchers.IO) {
        models.filter { runCatching { requireFile(context, it.id) }.isSuccess }.map { it.id }.toSet()
    }

    /** 명시적 요청에서만 공통 다운로더의 취소·고정 해시·원자적 저장을 사용합니다. */
    suspend fun download(context: Context, model: Model, progress: (String) -> Unit) {
        require(model in models)
        EnhancementModels.download(context, model.recommendation.url, model.recommendation, progress)
        withContext(Dispatchers.IO) { requireFile(context, model.id) }
    }
}
