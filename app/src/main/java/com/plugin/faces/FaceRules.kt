package com.plugin.faces

import com.plugin.compatibility.MediaRules
import java.util.Locale

/** 얼굴 판별의 폴더 경계·해상도·임계값을 저장소와 분리한 규칙입니다. */
object FaceRules {
    val resolutions = listOf(320, 640, 960, 1280, 0)
    private val images = setOf("jpg", "jpeg", "png", "bmp", "webp", "avif", "gif", "heic", "heif")

    /** 영상 전용 폴더·기존 분류 결과·임시 작업 폴더에는 들어가지 않습니다. */
    fun excluded(name: String, videoFolder: String): Boolean = name.lowercase(Locale.ROOT) in
        setOf("noface", "tmp", "videos", "video", "movies", "동영상", "영상", videoFolder.lowercase(Locale.ROOT)).filter(String::isNotBlank)

    /** 폴더가 섞여 있어도 이미지 확장자만 분석하며 영상·음원은 건드리지 않습니다. */
    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in images

    /** 화질 폴더는 검색하되 NOFACE 생성 기준은 가장 가까운 일반 상위 폴더입니다. */
    fun destinationParent(parts: List<String>, perFolder: Boolean): List<String> =
        if (!perFolder) emptyList() else parts.dropLastWhile(MediaRules::isQualityFolder)

    /** 원본 크기 선택도 메모리 한계를 넘으면 이동 없이 실패하도록 합니다. */
    fun analysisSize(width: Int, height: Int, maximum: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maximum in resolutions) { "분석 해상도를 확인하세요." }
        val scale = if (maximum == 0) 1.0 else minOf(1.0, maximum.toDouble() / maxOf(width, height))
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        require(w.toLong() * h <= 4_194_304L) { "원본이 너무 큽니다. 분석 해상도를 줄여주세요." }
        return w to h
    }

    /** YuNet 공식 후처리의 클래스·객체 점수 결합을 동일하게 사용합니다. */
    fun score(classScore: Float, objectScore: Float): Float {
        require(classScore.isFinite() && objectScore.isFinite()) { "모델 결과에 유효하지 않은 점수가 있습니다." }
        return kotlin.math.sqrt(classScore.coerceIn(0f, 1f) * objectScore.coerceIn(0f, 1f))
    }
}
