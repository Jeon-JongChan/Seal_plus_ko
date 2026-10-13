package com.plugin.enhancement

import java.net.URI
import kotlin.math.roundToInt

/** 비율·파일명·모델 입력 경계를 Android 없이 검사하는 규칙입니다. */
object EnhancementRules {
    const val MINIMUM = 10
    const val MAXIMUM = 11
    const val MAX_PIXELS = 16_777_216L
    const val MAX_MODEL_BYTES = 268_435_456L

    /** 게시글의 중복·동영상·얼굴 미검출 폴더를 제외하고 이미지 순서를 유지합니다. */
    fun postImages(paths: List<String>): List<String> = paths.distinct().filter {
        !com.plugin.compatibility.MediaRules.isNoFacePath(it) && com.plugin.compatibility.MediaRules.mediaKind(it) == 2
    }

    /** 지원하는 단일 파일 형식을 경로와 다운로드 검증에서 공유합니다. */
    fun modelFile(name: String): Boolean = name.endsWith(".onnx", true) || name.endsWith(".tflite", true)

    /** 심볼릭 링크와 상위 경로를 통한 모델 폴더 밖 삭제를 차단합니다. */
    fun managedFile(file: java.io.File, roots: List<java.io.File>): Boolean =
        file.isFile && modelFile(file.name) && roots.any {
            file.absoluteFile.parentFile == it.absoluteFile && file.canonicalFile.parentFile == it.canonicalFile
        }

    /** 다운로드 여유를 남기도록 논리 CPU 수의 절반까지만 선택합니다. */
    fun cpuThreadLimit(processors: Int): Int = (processors / 2).coerceAtLeast(1)

    /** 저장값 또는 서비스 요청을 현재 기기에서 허용하는 범위로 제한합니다. */
    fun cpuThreads(requested: Int, processors: Int): Int = requested.coerceIn(1, cpuThreadLimit(processors))

    /** 네이티브 프로세스가 종료되면 다른 프로세스에서 더 보수적인 경로를 한 번씩 시도합니다. */
    fun recoveryRoutes(backend: String, accelerated: Boolean, bundled: Boolean, npuFirst: Boolean = true): List<String> = when {
        !accelerated -> listOf("cpu")
        backend == "litert" && bundled -> if (npuFirst) listOf("automatic", "gpu", "cpu") else listOf("gpu", "cpu")
        else -> listOf("automatic", "cpu")
    }

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
            uri.port in listOf(-1, 443) && modelFile(uri.path)) {
            "Hugging Face의 공개 .onnx 또는 .tflite 파일 링크를 입력하세요."
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
