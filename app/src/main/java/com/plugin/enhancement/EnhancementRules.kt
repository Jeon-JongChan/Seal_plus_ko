package com.plugin.enhancement

import java.net.URI
import kotlin.math.roundToInt

/** 비율·파일명·모델 입력 경계를 Android 없이 검사하는 규칙입니다. */
object EnhancementRules {
    const val MINIMUM = 10
    const val MAXIMUM = 11
    const val MAX_PIXELS = 16_777_216L
    const val MAX_MODEL_BYTES = 268_435_456L

    /** 양수는 배율, 음수는 짧은 변의 목표 해상도이며 원래 비율을 유지합니다. */
    fun target(width: Int, height: Int, option: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "이미지 크기를 확인할 수 없습니다." }
        require(option in listOf(1, 2, 4, 8, -720, -1080, -1440, -2160, MINIMUM, MAXIMUM)) { "지원하지 않는 해상도입니다." }
        val preset = option == MINIMUM || option == MAXIMUM
        val desired = when (option) {
            MINIMUM -> 2.0
            MAXIMUM -> (2160.0 / minOf(width, height)).coerceAtLeast(1.0)
            else -> if (option > 0) option.toDouble() else -option.toDouble() / minOf(width, height)
        }
        // 간편 설정은 비율을 유지하면서 출력 제한 안에서 가능한 크기까지만 확대합니다.
        val ratio = if (preset) minOf(desired, 8192.0 / maxOf(width, height),
            kotlin.math.sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height))) else desired
        require(ratio >= 1.0) { "이미지가 이미 선택한 해상도 이상입니다. 배율을 선택하세요." }
        val w = if (preset) (width * ratio).toInt() else (width * ratio).roundToInt()
        val h = if (preset) (height * ratio).toInt() else (height * ratio).roundToInt()
        require(w in 1..8192 && h in 1..8192 && w.toLong() * h <= MAX_PIXELS) {
            "결과가 너무 큽니다. 더 낮은 해상도나 배율을 선택하세요."
        }
        return w to h
    }

    /** 직접 파일 링크만 허용하고 Hugging Face의 보기 링크는 다운로드 링크로 바꿉니다. */
    fun modelUrl(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && uri.host == "huggingface.co" && uri.userInfo == null &&
            uri.port in listOf(-1, 443) && uri.path.endsWith(".onnx", true)) {
            "Hugging Face의 공개 .onnx 파일 링크를 입력하세요."
        }
        val path = uri.rawPath.replace("/blob/", "/resolve/")
        require("/resolve/" in path && !uri.path.split('/').any { it == ".." }) { "모델 파일의 다운로드 링크가 필요합니다." }
        return "https://huggingface.co$path"
    }

    /** 원본 미디어 ID를 파일 끝에 유지하면서 복제임을 표시합니다. */
    fun duplicateName(name: String, suffix: String): String {
        val stem = name.substringBeforeLast('.', name)
        val id = Regex(" \\[[A-Za-z0-9_-]+]$").find(stem)?.value.orEmpty()
        return stem.removeSuffix(id) + "-AI-$suffix" + id + ".png"
    }
}
