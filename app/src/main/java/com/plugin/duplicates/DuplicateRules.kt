package com.plugin.duplicates

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** 화면과 백그라운드 분석이 공유하는 사용자 선택값입니다. */
@Serializable
data class DuplicateOptions(val model: String = "small", val preset: DuplicatePreset = DuplicatePreset.NORMAL,
    val priority: KeepPriority = KeepPriority.SIZE, val rememberExclusions: Boolean = true,
    val backend: DuplicateBackend = DuplicateBackend.AUTO)

/** 기본 자동 모드는 가속기 생성뿐 아니라 실제 추론 실패도 다음 경로로 대체합니다. */
@Serializable
enum class DuplicateBackend(val label: String, val routes: List<String>) {
    AUTO("NPU·GPU 자동 · 기본", listOf("NPU", "GPU", "CPU")),
    GPU("GPU 우선", listOf("GPU", "CPU")), CPU("CPU", listOf("CPU"))
}

/** 프리셋은 삭제 확정 기준이 아니라 검토할 후보의 폭을 조절합니다. */
@Serializable
enum class DuplicatePreset(val label: String, val distance: Int, val cosine: Double, val structure: Double) {
    STRICT("엄격", 2, 0.97, 0.995), SENSITIVE("민감", 6, 0.88, 0.965), NORMAL("보통", 4, 0.90, 0.98), LENIENT("관대", 10, 0.85, 0.94)
}

/** 시간 우선은 방향을 명시해 어떤 파일이 보존되는지 예측할 수 있게 합니다. */
@Serializable
enum class KeepPriority(val label: String) { SIZE("크기 우선"), RESOLUTION("해상도 우선"), NEWEST("최신 시간 우선"), OLDEST("오래된 시간 우선") }

/** 파일 변경을 검출할 메타데이터와 재검증용 전체 해시입니다. */
@Serializable
data class DuplicateMedia(val path: String, val name: String, val bytes: Long, val modified: Long,
    val width: Int, val height: Int, val durationMs: Long, val video: Boolean, val sha256: String) {
    val identity: String get() = "$path\n$sha256"
}

/** 동일 시간대에서 추출한 지각 해시와 AI 벡터를 한 번만 보관합니다. */
@Serializable
data class DuplicateFrame(val hash: Long, val vector: FloatArray, val contrast: Double, val pixels: FloatArray = floatArrayOf())

/** 선택한 모델·파일 버전별로 재사용할 분석 결과입니다. */
@Serializable
data class DuplicateFeature(val media: DuplicateMedia, val frames: List<DuplicateFrame>)

/** 모든 구성원이 보존 기준 파일과 직접 비교된 검토 그룹입니다. */
@Serializable
data class DuplicateGroup(val members: List<DuplicateMedia>, val exact: Boolean, val similarity: Double) {
    val id: String get() = members.first().identity
}

/** 프로세스가 종료되어도 사용자에게 다시 보여 줄 후보 목록입니다. */
@Serializable
data class DuplicateReport(val root: String = "", val groups: List<DuplicateGroup> = emptyList(),
    val scanned: Int = 0, val failures: Int = 0, val comparisons: Long = 0, val model: String = "small",
    val preset: DuplicatePreset = DuplicatePreset.NORMAL,
    val backend: DuplicateBackend = DuplicateBackend.AUTO, val engine: String = "이전 CPU 분석")

/** Android 없이도 검증할 후보 판정·보존 규칙입니다. */
object DuplicateRules {
    /** 정렬된 축소 RGB 픽셀의 채널별 SSIM을 함께 검사해 AI의 의미 유사도 오탐을 막습니다. */
    fun structure(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || a.size != b.size || a.size % 3 != 0) return -1.0
        var lowest = 1.0
        for (channel in 0..2) {
            val count = a.size / 3
            var sumA = 0.0; var sumB = 0.0
            for (index in channel until a.size step 3) {
                if (!a[index].isFinite() || !b[index].isFinite()) return -1.0
                sumA += a[index]; sumB += b[index]
            }
            val meanA = sumA / count; val meanB = sumB / count
            var varianceA = 0.0; var varianceB = 0.0; var covariance = 0.0
            for (index in channel until a.size step 3) {
                val da = a[index] - meanA; val db = b[index] - meanB
                varianceA += da * da; varianceB += db * db; covariance += da * db
            }
            varianceA /= count; varianceB /= count; covariance /= count
            val score = ((2 * meanA * meanB + 6.5025) * (2 * covariance + 58.5225)) /
                ((meanA * meanA + meanB * meanB + 6.5025) * (varianceA + varianceB + 58.5225))
            lowest = minOf(lowest, score)
        }
        return lowest.coerceIn(-1.0, 1.0)
    }

    /** 크기·해상도 동률에서도 경로까지 비교해 보존 추천을 고정합니다. */
    fun keeper(members: List<DuplicateMedia>, priority: KeepPriority): DuplicateMedia = members.minWith(
        compareByDescending<DuplicateMedia> { when (priority) {
            KeepPriority.SIZE -> it.bytes
            KeepPriority.RESOLUTION -> it.width.toLong() * it.height
            KeepPriority.NEWEST -> it.modified
            KeepPriority.OLDEST -> -it.modified
        } }.thenByDescending { it.width.toLong() * it.height }.thenByDescending { it.bytes }.thenBy { it.path }
    )

    /** 비정상·영벡터는 유사하다고 판단하지 않습니다. */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || a.size != b.size) return -1.0
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (index in a.indices) {
            if (!a[index].isFinite() || !b[index].isFinite()) return -1.0
            dot += a[index].toDouble() * b[index]; aa += a[index].toDouble() * a[index]; bb += b[index].toDouble() * b[index]
        }
        return if (aa <= 0 || bb <= 0) -1.0 else (dot / sqrt(aa * bb)).coerceIn(-1.0, 1.0)
    }

    /** 다른 길이·낮은 정보량·시간 순서가 다른 영상은 전체 중복 후보에서 제외합니다. */
    fun similarity(a: DuplicateFeature, b: DuplicateFeature, preset: DuplicatePreset): Double? {
        if (a.media.video != b.media.video) return null
        if (a.media.sha256 == b.media.sha256 && a.media.bytes == b.media.bytes) return 1.0
        if (a.media.video && kotlin.math.abs(a.media.durationMs - b.media.durationMs) > maxOf(250L, a.media.durationMs / 100)) return null
        if (a.frames.isEmpty() || a.frames.size != b.frames.size) return null
        var score = 0.0
        // 모든 대표 프레임이 일치해야 후보로 제안하며 의미 유사도만으로는 통과시키지 않습니다.
        for (index in a.frames.indices) {
            val left = a.frames[index]; val right = b.frames[index]
            if (minOf(left.contrast, right.contrast) < 4 || java.lang.Long.bitCount(left.hash xor right.hash) > preset.distance) return null
            val cosine = cosine(left.vector, right.vector)
            if (cosine < preset.cosine || structure(left.pixels, right.pixels) < preset.structure) return null
            score += cosine
        }
        return score / a.frames.size
    }
}

/** 64비트 지각 해시의 거리로 후보를 줄이는 BK 트리입니다. */
class DuplicateHashIndex {
    /** 같은 해시의 항목은 같은 노드에 모읍니다. */
    private class Node(val hash: Long, val ids: MutableList<Int>, val children: MutableMap<Int, Node> = mutableMapOf())
    private var root: Node? = null

    /** 삽입은 재귀 호출 없이 처리해 편향된 자료에서도 스택을 넘지 않습니다. */
    fun add(hash: Long, id: Int) {
        var node = root ?: run { root = Node(hash, mutableListOf(id)); return }
        while (true) {
            val distance = java.lang.Long.bitCount(hash xor node.hash)
            if (distance == 0) { node.ids.add(id); return }
            val next = node.children[distance]
            if (next == null) { node.children[distance] = Node(hash, mutableListOf(id)); return }
            node = next
        }
    }

    /** 삼각 부등식으로 지정 거리 밖의 하위 트리는 비교하지 않습니다. */
    fun candidates(hash: Long, radius: Int): List<Int> {
        require(radius in 0..64)
        val found = mutableListOf<Int>(); val waiting = java.util.ArrayDeque<Node>()
        root?.let(waiting::add)
        while (waiting.isNotEmpty()) {
            val node = waiting.removeLast(); val distance = java.lang.Long.bitCount(hash xor node.hash)
            if (distance <= radius) found.addAll(node.ids)
            node.children.forEach { (edge, child) -> if (edge in distance - radius..distance + radius) waiting.add(child) }
        }
        return found
    }
}
