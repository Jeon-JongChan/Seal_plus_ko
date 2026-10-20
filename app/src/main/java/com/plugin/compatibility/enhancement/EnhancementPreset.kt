package com.plugin.compatibility.enhancement

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.updateString

/**
 * 보정 옵션 묶음(커스텀·속도·균형·품질·최고)입니다.
 * 커스텀은 개별 옵션을 직접 지정하는 상태이고, 나머지 단계는 선택하면 묶음 값을 개별 옵션에 채웁니다.
 * 단계의 값은 사용자가 개별 옵션을 바꾼 뒤 저장할 수 있고, 저장하지 않았다면 아래 기본값을 사용합니다.
 * 프레임·저장 코덱·크기는 결과물의 성격이 달라지는 선택이라 단계에 포함하지 않습니다.
 */
object EnhancementPreset {
    const val CUSTOM = "custom"

    /** 선택지 이름과 표시 문구입니다. */
    val names = listOf(CUSTOM to "커스텀", "speed" to "속도", "balanced" to "균형", "quality" to "품질", "best" to "최고")

    /** 단계별 한 줄 성격입니다. */
    private val intents = mapOf("speed" to "가장 빠름", "balanced" to "기본 권장", "quality" to "디테일 우선", "best" to "시간이 걸려도 최상")

    /** 단계가 채우는 옵션 값입니다. 이미지는 [model]·[shrink]·[engine]을 쓰지 않습니다(빈 모델·끔·내장으로 고정). */
    data class Values(val model: String, val denoise: String, val engine: String, val strength: String, val shrink: String, val sharpen: String, val sampling: Int) {
        /** 저장 문자열로 직렬화합니다. 모델 경로에 쓰이지 않는 탭을 구분자로 사용합니다. */
        fun encode() = listOf(model, denoise, engine, strength, shrink, sharpen, sampling).joinToString("\t")

        companion object {
            /** 저장 문자열을 읽고, 형식이 깨졌거나 알 수 없는 값이 있으면 null을 돌려 기본값을 쓰게 합니다. */
            fun decode(text: String): Values? {
                val parts = text.split("\t")
                if (parts.size != 7) return null
                val sampling = parts[6].toIntOrNull() ?: return null
                val ok = parts[1] in listOf(PreDenoise.AUTO, PreDenoise.OFF, PreDenoise.ALWAYS) &&
                    parts[2] in listOf(PreDenoise.BUILTIN, PreDenoise.FFMPEG) &&
                    parts[3] in PreDenoise.strengths.map { it.first } &&
                    parts[4] in listOf(PreDenoise.AUTO, PreDenoise.OFF, PreDenoise.ALWAYS) &&
                    parts[5] in PostSharpen.strengths.map { it.first } &&
                    sampling in EnhancementSampling.NEAREST..EnhancementSampling.AREA
                return if (ok) Values(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], sampling) else null
            }
        }
    }

    private val small = EnhancementModels.SMALL_MODEL
    private val medium = EnhancementModels.MEDIUM_MODEL
    private const val BILINEAR = EnhancementSampling.BILINEAR
    private const val AREA = EnhancementSampling.AREA

    /** 영상 기본값입니다. 균형은 기존 앱 기본 설정과 같습니다. */
    private val videoDefaults = mapOf(
        "speed" to Values(small, PreDenoise.OFF, PreDenoise.BUILTIN, "light", PreDenoise.AUTO, PostSharpen.OFF, BILINEAR),
        "balanced" to Values(small, PreDenoise.AUTO, PreDenoise.BUILTIN, "normal", PreDenoise.OFF, PostSharpen.OFF, BILINEAR),
        "quality" to Values(medium, PreDenoise.AUTO, PreDenoise.FFMPEG, "normal", PreDenoise.OFF, "light", AREA),
        "best" to Values(medium, PreDenoise.ALWAYS, PreDenoise.FFMPEG, "light", PreDenoise.OFF, "normal", AREA),
    )

    /** 이미지 기본값입니다. 모델과 선 축소는 단계에 포함하지 않습니다. */
    private val imageDefaults = videoDefaults.mapValues { (_, v) -> v.copy(model = "", shrink = PreDenoise.OFF, engine = PreDenoise.BUILTIN) }

    /** 저장소를 거치지 않는 단계의 기본값입니다(테스트와 되돌리기에서 사용). */
    internal fun defaultValues(name: String, image: Boolean): Values = (if (image) imageDefaults else videoDefaults).getValue(name)

    /** 사용자가 개별 옵션을 바꿀 때마다 올라가 "변경됨" 표시를 다시 계산하게 합니다. */
    var edits by mutableIntStateOf(0); private set

    /** 단계 값을 적용할 때마다 올라가, 각 옵션 칩이 저장값을 다시 읽게 합니다. */
    var reload by mutableIntStateOf(0); private set

    /** 개별 옵션 변경을 알립니다. */
    fun edited() { edits++ }

    private fun selectedKey(image: Boolean) = if (image) "image_ai_preset" else "video_ai_preset"
    private fun savedKey(name: String, image: Boolean) = "ai_preset_${if (image) "image" else "video"}_$name"

    /** 현재 선택한 단계를 읽으며 알 수 없는 값은 커스텀으로 취급합니다. */
    fun selected(image: Boolean): String = selectedKey(image).getString(CUSTOM).takeIf { id -> names.any { it.first == id } } ?: CUSTOM

    /** 저장된 값이 있으면 그것을, 없거나 깨졌으면 기본값을 돌려줍니다. */
    fun values(name: String, image: Boolean): Values = savedKey(name, image).getString("").let { Values.decode(it) }
        ?: (if (image) imageDefaults else videoDefaults).getValue(name)

    /** 사용자가 저장한 값이 있는지 여부입니다. */
    fun hasSaved(name: String, image: Boolean): Boolean = Values.decode(savedKey(name, image).getString("")) != null

    /** 지금 저장돼 있는 개별 옵션 값을 묶음으로 읽습니다. 알 수 없는 값은 앱 기본값으로 보정합니다. */
    fun current(image: Boolean): Values = Values(
        model = if (image) "" else EnhancementModels.VIDEO_SELECTED.getString(small).ifBlank { small },
        denoise = PreDenoise.mode(image),
        engine = if (image) PreDenoise.BUILTIN else PreDenoise.engine(),
        strength = EnhancementModels.DENOISE_STRENGTH.getString("normal").takeIf { id -> PreDenoise.strengths.any { it.first == id } } ?: "normal",
        shrink = if (image) PreDenoise.OFF else EnhancementModels.VIDEO_SHRINK.getString(PreDenoise.OFF)
            .takeIf { it == PreDenoise.AUTO || it == PreDenoise.ALWAYS } ?: PreDenoise.OFF,
        sharpen = EnhancementModels.SHARPEN.getString(PostSharpen.OFF).takeIf { id -> PostSharpen.strengths.any { it.first == id } } ?: PostSharpen.OFF,
        sampling = EnhancementSampling.normalized(EnhancementSampling.KEY.getInt(BILINEAR)),
    )

    /** 값을 개별 옵션 저장소에 쓰고 화면이 다시 읽도록 알립니다. 이미지는 모델·선 축소를 건드리지 않습니다. */
    private fun apply(values: Values, image: Boolean) {
        (if (image) EnhancementModels.IMAGE_DENOISE else EnhancementModels.VIDEO_DENOISE).updateString(values.denoise)
        EnhancementModels.DENOISE_STRENGTH.updateString(values.strength)
        EnhancementModels.SHARPEN.updateString(values.sharpen)
        EnhancementSampling.KEY.updateInt(values.sampling)
        if (!image) {
            EnhancementModels.VIDEO_SHRINK.updateString(values.shrink)
            EnhancementModels.VIDEO_DENOISE_ENGINE.updateString(values.engine)
            EnhancementModels.VIDEO_SELECTED.updateString(values.model)
        }
        reload++; edits++
    }

    /** 단계를 고릅니다. 커스텀은 값을 바꾸지 않고, 나머지는 해당 단계의 값을 적용합니다. */
    fun select(name: String, image: Boolean) {
        selectedKey(image).updateString(name)
        if (name == CUSTOM) edits++ else apply(values(name, image), image)
    }

    /** 현재 개별 옵션 값을 이 단계의 값으로 저장합니다. */
    fun save(name: String, image: Boolean) {
        savedKey(name, image).updateString(current(image).encode()); edits++
    }

    /** 저장한 값을 지우고 이 단계의 기본값으로 되돌려 적용합니다. */
    fun reset(name: String, image: Boolean) {
        savedKey(name, image).updateString("")
        apply((if (image) imageDefaults else videoDefaults).getValue(name), image)
    }

    /** 사용자가 단계 값과 설명을 한눈에 비교할 수 있게 값을 한 줄로 요약합니다. */
    fun describe(v: Values, image: Boolean): String {
        val denoise = mapOf(PreDenoise.AUTO to "자동", PreDenoise.OFF to "끔", PreDenoise.ALWAYS to "항상")
        val level = mapOf("light" to "약", "normal" to "보통", "strong" to "강")
        val sharpen = mapOf(PostSharpen.OFF to "끔", "light" to "약", "normal" to "보통", "strong" to "강")
        val sampling = mapOf(EnhancementSampling.NEAREST to "최근접", EnhancementSampling.BILINEAR to "양선형", EnhancementSampling.AREA to "영역 평균")
        val model = when (v.model) { small -> "Small"; medium -> "Medium"; else -> v.model.substringAfterLast('/') }
        return buildList {
            if (!image) add("모델 $model")
            add("노이즈 ${denoise[v.denoise]}" + if (v.denoise == PreDenoise.OFF) "" else "·${level[v.strength]}" + if (!image && v.engine == PreDenoise.FFMPEG) "(FFmpeg)" else "")
            if (!image) add("선 축소 ${denoise[v.shrink]}")
            add("샤프닝 ${sharpen[v.sharpen]}"); add(sampling[v.sampling].orEmpty())
        }.joinToString(" · ")
    }

    /** 단계별 한 줄 성격과 현재(저장 또는 기본) 값 요약을 설명 목록으로 만듭니다. */
    fun help(image: Boolean): List<Pair<String, String>> = listOf(CUSTOM to "커스텀: 아래 옵션을 직접 지정합니다. 단계를 고르면 옵션이 그 값으로 채워지고, 바꾼 값은 '저장'으로 그 단계에 남길 수 있습니다.") +
        names.drop(1).map { (id, label) -> id to "$label(${intents[id]}): ${describe(values(id, image), image)}" }
}

