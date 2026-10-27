package com.plugin.compatibility.enhancement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateString

/** 설정 화면과 작업 상세에서 영상의 저장 코덱 선택을 공유합니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoEncodingSettings(enabled: Boolean = true) {
    var original by remember { mutableStateOf(EnhancementModels.VIDEO_ORIGINAL_CODEC.getBoolean(false)) }
    var codec by remember { mutableStateOf(EnhancementModels.VIDEO_CODEC.getString(if (original) "original" else "auto")) }
    Text("저장 코덱", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("auto" to "AV1 우선 · 기본", "original" to "원본 코덱 유지", "h264" to "H.264", "h265" to "H.265").forEach { (value, label) ->
            FilterChip(codec == value, enabled = enabled, onClick = {
                codec = value; original = value == "original"
                EnhancementModels.VIDEO_CODEC.updateString(value)
                EnhancementModels.VIDEO_ORIGINAL_CODEC.updateBoolean(original)
                EnhancementPreset.edited()
            }, label = { Text(label) })
        }
    }
    OptionHelp(codec, listOf(
        "auto" to "AV1 우선 · 기본: 같은 화질에서 파일이 가장 작습니다. AV1 인코더가 없거나(Android 14 미만 등) 해상도를 지원하지 않으면 원본 코덱 → H.265 → H.264 순으로 자동 대체합니다.",
        "original" to "원본 코덱 유지: 원본이 H.264면 결과도 H.264, H.265면 H.265로 저장해 재생 호환성이 가장 높습니다. 기기 인코더가 목표 해상도를 지원하지 않으면 다른 코덱으로 대체합니다.",
        "h264" to "H.264: 거의 모든 기기·앱에서 재생됩니다. 파일은 큰 편이며 일부 기기는 1080p 초과 해상도를 인코딩하지 못해 H.265로 대체될 수 있습니다.",
        "h265" to "H.265: H.264보다 약 30~40% 작게 저장합니다. 오래된 기기·일부 앱에서는 재생이 안 될 수 있습니다.",
    ), note = "처리 단계: 마지막(인코딩). 방식: 기기 내장 인코더(MediaCodec, AI·FFmpeg 아님)가 프레임을 바로 인코딩해 저장하고, FFmpeg는 그 뒤 오디오를 합칠 때만 영상 복사(재인코딩 없음)로 씁니다. 선택한 코덱이 목표 해상도를 지원하지 않으면 결과 화면의 엔진 정보에 대체된 코덱이 표시됩니다. 기기 한계를 넘는 해상도는 지원 범위까지 낮춰 저장합니다.")
}

/**
 * 선택한 옵션의 설명을 강조하고 나머지는 흐리게 나열해, 각 옵션의 결과를 선택 전에 비교할 수 있게 합니다.
 * 기본은 한 줄 요약과 도움말 아이콘만 표시하며 펼친 경우에만 상세 내용과 추가 UI를 구성합니다.
 */
@Composable
internal fun OptionHelp(selected: String, items: List<Pair<String, String>>, enabled: Boolean = true, note: String? = null,
    summary: String = "옵션 설명", title: String = summary, content: @Composable () -> Unit = {}) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.Button,onClickLabel=if (open) "$title 접기" else "$title 펼치기") { open=!open }
        .semantics { stateDescription=if (open) "펼쳐짐" else "접힘" },
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.HelpOutline,null,tint=MaterialTheme.colorScheme.primary)
        Text(summary,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
        Text(if (open) "접기" else "보기",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
    }
    if (!open) return
    // 상위 화면의 큰 간격 대신 한 묶음으로 보이도록 촘촘하게 배치합니다.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (value, text) ->
            val chosen = value == selected && enabled
            Text("${if (chosen) "● " else "○ "}$text", style = MaterialTheme.typography.bodySmall,
                color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        content()
    }
}

/** 저화질 영상의 업스케일 전 노이즈 제거 사용 방식을 선택합니다. 기본은 저비트레이트 영상에만 자동 적용입니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DenoiseSettings(enabled: Boolean = true, image: Boolean = false) {
    // 설정 단계(속도·균형 등)를 적용하면 저장값을 다시 읽도록 reload를 키로 사용합니다.
    var mode by remember(EnhancementPreset.reload) { mutableStateOf(PreDenoise.mode(image)) }
    val key = if (image) EnhancementModels.IMAGE_DENOISE else EnhancementModels.VIDEO_DENOISE
    Text("노이즈 제거 전처리", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PreDenoise.AUTO to "자동 · 기본", PreDenoise.OFF to "끔", PreDenoise.ALWAYS to "항상").forEach { (value, label) ->
            FilterChip(mode == value, enabled = enabled, onClick = { mode = value; key.updateString(value); EnhancementPreset.edited() },
                label = { Text(label) })
        }
    }
    val target = if (image) "이미지" else "영상"
    OptionHelp(mode, listOf(
        PreDenoise.AUTO to "자동 · 기본: 압축이 심해 블록·모스키토 노이즈가 많은 ${target}(${if (image) "픽셀당 약 1.5비트 미만" else "픽셀당 약 0.1비트 미만"})에만 적용합니다. 고화질 원본은 디테일 손실 없이 그대로 확대합니다.",
        PreDenoise.OFF to "끔: 전처리 없이 바로 확대합니다. 가장 빠르고 원본 질감이 그대로 남지만, 저화질 원본의 노이즈도 함께 선명해질 수 있습니다.",
        PreDenoise.ALWAYS to "항상: 모든 ${target}에 적용합니다. 깨끗한 원본은 미세한 질감이 약간 뭉개지고 처리 시간이 늘어납니다.",
    ), enabled, note = if (image) "처리 단계: 디코드 직후, AI 모델에 넣기 전. 방식: 앱 내장 3x3 양방향 필터(Tomasi & Manduchi 1998; 거리·밝기 차이 가중)를 CPU로 실행합니다(AI·FFmpeg 아님)."
        else "처리 단계: 디코드 직후, AI 모델에 넣기 전. AI 모델이 아니라 규칙 기반 필터이며, 어떤 엔진으로 처리할지는 아래 '노이즈 제거 엔진'에서 고릅니다.")
    if (!image) {
        var engine by remember(EnhancementPreset.reload) { mutableStateOf(PreDenoise.engine()) }
        Text("노이즈 제거 엔진", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PreDenoise.BUILTIN to "내장 필터 · 기본", PreDenoise.FFMPEG to "FFmpeg").forEach { (value, label) ->
                FilterChip(engine == value, enabled = enabled && mode != PreDenoise.OFF,
                    onClick = { engine = value; EnhancementModels.VIDEO_DENOISE_ENGINE.updateString(value); EnhancementPreset.edited() },
                    label = { Text(label) })
            }
        }
        OptionHelp(engine, listOf(
            PreDenoise.BUILTIN to "내장 필터 · 기본: 디코드한 프레임을 하나씩 비트맵으로 바꿔 3x3 양방향 필터(거리·밝기 차이 가중, 앱 내장, CPU 병렬, AI·FFmpeg 아님)를 적용합니다. 프레임 한 장 안의 이웃 픽셀만 보며 임시 파일이 필요 없습니다.",
            PreDenoise.FFMPEG to "FFmpeg: 업스케일 전에 영상 전체를 FFmpeg의 hqdn3d 필터로 한 번에 처리해 임시 H.264(CRF 12) 영상을 만들고, 이후 단계가 그 영상을 읽습니다. 앞뒤 프레임까지 보는 시간축 필터라 깜빡이는 블록 노이즈에 더 효과적이고 프레임마다의 비트맵 복사가 줄지만, 임시 파일과 처리 시간이 추가됩니다. 저화질 선 축소를 켜면 같은 패스에서 함께 처리하며, 실패하면 내장 필터로 자동 대체하고 결과 화면에 사유를 표시합니다.",
        ), enabled && mode != PreDenoise.OFF)
    }
    // 내장 필터 전용 옵션: 영상은 FFmpeg 엔진이면 쓰이지 않으므로 비활성화합니다.
    var distance by remember { mutableStateOf(EnhancementModels.DENOISE_DISTANCE.getBoolean(true)) }
    val builtin = image || PreDenoise.engine() == PreDenoise.BUILTIN
    Text("거리 가중치 (내장 필터)", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(true to "거리·밝기 가중 · 기본", false to "밝기 차이만").forEach { (value, label) ->
            FilterChip(distance == value, enabled = enabled && mode != PreDenoise.OFF && builtin,
                onClick = { distance = value; EnhancementModels.DENOISE_DISTANCE.updateBoolean(value); EnhancementPreset.edited() }, label = { Text(label) })
        }
    }
    OptionHelp(distance.toString(), listOf(
        "true" to "거리·밝기 가중 · 기본: 3x3 이웃 중 가까운 상하좌우를 모서리보다 더 크게 반영하는 정통 양방향 필터입니다. 윤곽과 디테일이 더 잘 남습니다.",
        "false" to "밝기 차이만: 이웃을 거리와 관계없이 같은 비중으로 보고 밝기가 비슷한 것만 평균합니다(sigma filter). 조금 더 매끈해지지만 질감이 더 뭉개집니다.",
    ), enabled && mode != PreDenoise.OFF && builtin,
        note = "처리 단계: 내장 필터 안에서 이웃 픽셀에 곱하는 가중치입니다. 계산량 차이는 거의 없습니다. FFmpeg 엔진(hqdn3d)에는 적용되지 않습니다.")
    var strength by remember(EnhancementPreset.reload) { mutableStateOf(EnhancementModels.DENOISE_STRENGTH.getString("normal")) }
    Text("노이즈 제거 강도", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("light" to "약하게", "normal" to "보통 · 기본", "strong" to "강하게").forEach { (value, label) ->
            FilterChip(strength == value, enabled = enabled && mode != PreDenoise.OFF,
                onClick = { strength = value; EnhancementModels.DENOISE_STRENGTH.updateString(value); EnhancementPreset.edited() }, label = { Text(label) })
        }
    }
    OptionHelp(strength, listOf(
        "light" to "약하게(σ 8): 색 차이가 큰 경계만 지키며 아주 작은 노이즈만 지웁니다. 질감·머리카락 같은 세부가 거의 유지됩니다.",
        "normal" to "보통 · 기본(σ 14): 블록 노이즈와 잔 얼룩을 줄이면서 윤곽을 유지하는 균형 설정입니다.",
        "strong" to "강하게(σ 22): 심한 압축 노이즈도 지우지만 피부·벽 같은 질감이 매끈하게 뭉개질 수 있습니다.",
    ), enabled && mode != PreDenoise.OFF,
        note = "위에서 고른 엔진의 세기입니다. 내장 필터는 σ(8/14/22)를, FFmpeg는 같은 눈금으로 환산한 hqdn3d 인자(보통 = 4:3:6:4.5)를 씁니다(이미지는 내장 필터만). σ가 클수록 색이 비슷한 이웃을 더 넓게 평균 내어 노이즈가 줄고 질감도 함께 줄어듭니다. 끔 상태에서는 적용되지 않습니다.")
}

/** 저비트레이트 영상을 모델 입력 전에 75%로 줄이는 다운스케일 트릭의 사용 방식을 선택합니다. 기본은 끔입니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShrinkSettings(enabled: Boolean = true) {
    var mode by remember(EnhancementPreset.reload) { mutableStateOf(EnhancementModels.VIDEO_SHRINK.getString(PreDenoise.OFF)) }
    Text("저화질 선 축소 (실험)", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PreDenoise.OFF to "끔 · 기본", PreDenoise.AUTO to "자동", PreDenoise.ALWAYS to "항상").forEach { (value, label) ->
            FilterChip(mode == value, enabled = enabled, onClick = { mode = value; EnhancementModels.VIDEO_SHRINK.updateString(value); EnhancementPreset.edited() },
                label = { Text(label) })
        }
    }
    OptionHelp(mode, listOf(
        PreDenoise.OFF to "끔 · 기본: 원본 크기 그대로 모델에 전달합니다. 가장 선명한 디테일을 유지합니다.",
        PreDenoise.AUTO to "자동: 압축이 심한 영상(픽셀당 약 0.1비트 미만)만 75%로 줄여 블록 노이즈를 평탄화한 뒤 확대합니다. 보간 연산량도 줄어 더 빠릅니다.",
        PreDenoise.ALWAYS to "항상: 모든 영상을 75%로 줄입니다. 깨끗한 원본은 디테일이 줄 수 있습니다.",
    ), enabled, note = "처리 단계: 노이즈 제거 직후, AI 모델 입력 전. 방식: 줄이는 일은 AI가 아닌 단순 리사이즈입니다. 내장 엔진이면 프레임마다 앱 내장 CPU 리사이즈(다운샘플링 설정 적용)로, 노이즈 제거 엔진이 FFmpeg면 같은 FFmpeg 패스의 scale 필터로 처리합니다. 줄인 프레임은 AI 모델이 다시 확대해 목표 크기에 맞추며, 노이즈 제거와 함께 쓰면 효과가 큽니다.")
}

/** 업스케일 후 윤곽 샤프닝 강도를 선택합니다. 기본은 끔이며 이미지·영상이 같은 값을 공유합니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SharpenSettings(enabled: Boolean = true) {
    var level by remember(EnhancementPreset.reload) { mutableStateOf(EnhancementModels.SHARPEN.getString(PostSharpen.OFF)) }
    Text("샤프닝 후처리", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(PostSharpen.OFF to "끔 · 기본", "light" to "약하게", "normal" to "보통", "strong" to "강하게").forEach { (value, label) ->
            FilterChip(level == value, enabled = enabled, onClick = { level = value; EnhancementModels.SHARPEN.updateString(value); EnhancementPreset.edited() },
                label = { Text(label) })
        }
    }
    OptionHelp(level, listOf(
        PostSharpen.OFF to "끔 · 기본: 모델 출력을 그대로 저장합니다. 가장 빠릅니다.",
        "light" to "약하게(0.3): 흐릿한 윤곽을 살짝 세웁니다. 노이즈가 거의 늘지 않습니다.",
        "normal" to "보통(0.6): 업스케일로 부드러워진 결과에 알맞은 선명도입니다.",
        "strong" to "강하게(1.0): 윤곽이 뚜렷하지만 잔노이즈·압축 흔적도 함께 선명해질 수 있습니다.",
    ), enabled, note = "처리 단계: 업스케일·보간이 끝난 최종 크기에서 인코딩 직전. 방식: 앱 내장 CPU 필터(제한형 언샤프, AI·FFmpeg 아님)를 프레임마다 한 번 적용하며 윤곽 주변의 흰 테두리를 막도록 이웃 밝기 범위로 제한합니다. FFmpeg의 unsharp·cas는 인코딩이 끝난 영상을 다시 인코딩해야 해 화질이 떨어지므로 쓰지 않습니다. 영상은 프레임마다 적용되어 처리 시간이 늘어납니다.")
}

/** 설정과 작업 상세 화면이 동일한 프레임 유지·증가 선택을 사용합니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FrameSettings(enabled: Boolean = true) {
    var fps by remember { mutableIntStateOf(EnhancementModels.VIDEO_FPS.getInt(0).takeIf { it in VideoRules.frameRates } ?: 0) }
    Text("프레임", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoRules.frameRates.forEach { value ->
            FilterChip(fps == value, enabled = enabled, onClick = { fps = value; EnhancementModels.VIDEO_FPS.updateInt(value); EnhancementPreset.edited() },
                label = { Text(if (value == 0) "원본 유지" else "$value fps") })
        }
    }
    var low by remember { mutableStateOf(EnhancementModels.VIDEO_INTERP_LOW.getBoolean(false)) }
    Text("보간 시점", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "자동 · 기본", true to "저해상도에서 먼저 보간").forEach { (value, label) ->
            FilterChip(low == value, enabled = enabled && fps != 0,
                onClick = { low = value; EnhancementModels.VIDEO_INTERP_LOW.updateBoolean(value); EnhancementPreset.edited() }, label = { Text(label) })
        }
    }
    OptionHelp(low.toString(), listOf(
        "false" to "자동 · 기본: 크기를 유지하면 원본 프레임을 AI로 보정한 뒤 최종 크기에서 보간하고, 확대하면 저해상도에서 먼저 보간합니다. 크기를 유지할 때 보간 화질이 가장 좋습니다.",
        "true" to "저해상도에서 먼저 보간: 원본(또는 선 축소한 크기)에서 보간한 뒤 보간 프레임까지 모두 AI로 보정합니다. RIFE가 처리하는 화소가 줄어 빨라질 수 있지만, AI 보정이 보간 프레임에도 실행되어 늘어난 프레임 수만큼 AI 시간이 늡니다(예: 30→60fps는 AI 2배). 기기에 따라 오히려 느릴 수 있어 실측이 필요합니다.",
    ), enabled && fps != 0, note = "처리 단계: AI 보정과 RIFE 보간의 순서를 정합니다. 원본 FPS 유지에서는 적용되지 않습니다. 선 축소를 함께 켜면 RIFE가 더 작은 크기에서 동작합니다.")
    OptionHelp("", emptyList(), note = "처리 단계: 크기를 유지하면 원본 프레임을 AI로 보정한 뒤 그 사이를 보간하고, 확대하면 저해상도에서 먼저 보간한 뒤 각 프레임을 AI로 확대합니다. 방식: 원본보다 높은 FPS를 선택할 때 AI 보간 모델 RIFE를 Vulkan GPU로 실행합니다(FFmpeg 아님). GPU 미지원 시 원본 유지를 선택하세요. 모델은 처음 사용할 때 약 10MB를 다운로드합니다.\n" +
        "GPU가 없거나 초기화에 실패하면 오류를 표시하며 보간을 몰래 생략하지 않습니다. NPU·GPU 사용률은 기기·모델·발열에 따라 달라지며 100%를 보장하지 않습니다.")
}

/**
 * 설정 단계(커스텀·속도·균형·품질·최고)를 고르는 메뉴입니다.
 * 단계를 고르면 아래 개별 옵션이 그 값으로 채워지고, 옵션을 바꾼 뒤 '저장'하면 그 단계의 값으로 남습니다.
 * [image]가 true면 이미지용(모델·선 축소 제외) 단계와 저장소를 사용합니다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PresetSettings(enabled: Boolean = true, image: Boolean = false) {
    // 개별 옵션 변경·저장·적용이 있을 때만 다시 비교합니다(저장소 읽기는 가볍지만 매 프레임 하지 않습니다).
    val edits = EnhancementPreset.edits; val reload = EnhancementPreset.reload
    // 시작 버튼 등 다른 곳에서 단계를 골라도 이 메뉴의 선택 표시가 따라오도록 같은 신호로 다시 읽습니다.
    var name by remember(image, edits, reload) { mutableStateOf(EnhancementPreset.selected(image)) }
    val modified = remember(name, edits, reload) { name != EnhancementPreset.CUSTOM && EnhancementPreset.current(image) != EnhancementPreset.values(name, image) }
    val saved = remember(name, edits, reload) { name != EnhancementPreset.CUSTOM && EnhancementPreset.hasSaved(name, image) }
    val label = EnhancementPreset.names.firstOrNull { it.first == name }?.second.orEmpty()
    Text("설정 단계", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnhancementPreset.names.forEach { (value, text) ->
            FilterChip(name == value, enabled = enabled, onClick = { name = value; EnhancementPreset.select(value, image) }, label = { Text(text) })
        }
    }
    if (name != EnhancementPreset.CUSTOM) {
        if (modified) Text("'${label}'에서 변경됨 · 저장하면 이 단계의 값이 바뀝니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = enabled && modified, onClick = { EnhancementPreset.save(name, image) }) { Text("'${label}'에 저장") }
            TextButton(enabled = enabled && (modified || saved), onClick = { EnhancementPreset.reset(name, image) }) { Text("기본값으로 되돌리기") }
        }
    }
    OptionHelp(name, EnhancementPreset.help(image), enabled,
        note = "프레임·저장 코덱·크기는 단계에 포함되지 않습니다. 이미지와 영상은 노이즈 강도·샤프닝·다운샘플링 값을 공유합니다.")
    // 아래 옵션 묶음이 이 순서로 배치되어 있어, 각 옵션이 어느 단계에서 동작하는지 한눈에 보이게 합니다.
    Text(if (image) "처리 순서: 노이즈 제거 → AI 업스케일 → 샤프닝" else "처리 순서: 디코드 → 노이즈 제거 → 선 축소 → AI 업스케일 → 프레임 보간 → 샤프닝 → 인코딩 → 오디오 합치기",
        style = MaterialTheme.typography.bodySmall)
}

/**
 * 보정을 시작하기 전에 지금 적용될 옵션을 한곳에 보여 줍니다(상세 조정을 펼치지 않아도 보입니다).
 * 단계·옵션이 바뀌면 [EnhancementPreset.edits]·[EnhancementPreset.reload]로 다시 읽습니다.
 */
@Composable
internal fun SelectedOptionsSummary(image: Boolean = false) {
    val edits = EnhancementPreset.edits; val reload = EnhancementPreset.reload
    val lines = remember(image, edits, reload) {
        val preset = EnhancementPreset.selected(image)
        val label = EnhancementPreset.names.firstOrNull { it.first == preset }?.second.orEmpty()
        val modified = preset != EnhancementPreset.CUSTOM && EnhancementPreset.current(image) != EnhancementPreset.values(preset, image)
        val current = EnhancementPreset.current(image)
        buildList {
            add("설정 단계: $label" + if (modified) " (변경됨)" else "")
            add("출력 크기: " + sizeOptions(image).first { it.first == savedSize(image) }.second)
            if (image) add("모델: " + EnhancementModels.SELECTED.getString(EnhancementModels.DEFAULT_MODEL).ifBlank { EnhancementModels.DEFAULT_MODEL }
                .substringAfterLast('/').substringBeforeLast('.') + if (EnhancementModels.GOOGLE_FIRST.getBoolean()) " · Google 보정 우선" else "")
            add(EnhancementPreset.describe(current, image))
            // 내장 필터를 쓰는 경우에만 거리 가중치가 의미 있습니다.
            if (current.denoise != PreDenoise.OFF && current.engine == PreDenoise.BUILTIN)
                add("노이즈 제거 방식: 내장 ${if (PreDenoise.distanceWeight()) "거리·밝기 가중" else "밝기 차이만"}")
            if (!image) {
                val fps = EnhancementModels.VIDEO_FPS.getInt(0).takeIf { it in VideoRules.frameRates } ?: 0
                add(if (fps == 0) "프레임: 원본 유지" else "프레임: ${fps}fps · 보간 시점 ${if (EnhancementModels.VIDEO_INTERP_LOW.getBoolean(false)) "저해상도 먼저" else "자동"}")
                val codec = EnhancementModels.VIDEO_CODEC.getString("auto")
                add("저장 코덱: " + mapOf("auto" to "AV1 우선", "original" to "원본 코덱 유지", "h264" to "H.264", "h265" to "H.265")[codec].orEmpty().ifBlank { "AV1 우선" })
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("현재 선택된 옵션", style = MaterialTheme.typography.titleSmall)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** 시작 창에서 고른 출력 크기의 저장 키입니다. */
private fun sizeKey(image: Boolean) = if (image) "image_ai_start_size" else "video_ai_start_size"

/**
 * 시작 창의 출력 크기 선택지입니다. 2배·4K는 간편 규칙([EnhancementRules.MINIMUM]·[EnhancementRules.MAXIMUM])을 써서
 * 이미 큰 원본도 거부하지 않고 출력 제한 안에서 가능한 만큼만 확대합니다.
 */
internal fun sizeOptions(image: Boolean): List<Pair<Int, String>> =
    listOf(EnhancementRules.MINIMUM to "2배 · 기본", 4 to "4배") + (if (image) listOf(8 to "8배") else emptyList()) +
        listOf(-720 to "720p", -1080 to "1080p", -1440 to "2K", EnhancementRules.MAXIMUM to "4K", 1 to "크기 유지")

/** 저장된 출력 크기이며, 알 수 없는 값은 기본(2배)으로 돌립니다. */
internal fun savedSize(image: Boolean): Int =
    sizeKey(image).getInt(EnhancementRules.MINIMUM).takeIf { v -> sizeOptions(image).any { it.first == v } } ?: EnhancementRules.MINIMUM

/** 시작 창의 출력 크기 칩입니다. 고른 값은 저장되어 다음에 창을 열어도 유지됩니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SizeSettings(image: Boolean, enabled: Boolean = true) {
    var size by remember(image) { mutableIntStateOf(savedSize(image)) }
    Text("출력 크기", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sizeOptions(image).forEach { (value, label) ->
            FilterChip(size == value, enabled = enabled, onClick = { size = value; sizeKey(image).updateInt(value); EnhancementPreset.edited() },
                label = { Text(label) })
        }
    }
    Text("원본 비율을 유지합니다. 2배·4K는 출력 제한 안에서 가능한 만큼만 확대하고, 720p~2K는 원본이 이미 그 이상이면 시작하지 않습니다.",
        style = MaterialTheme.typography.bodySmall)
}

/**
 * 설정 단계 버튼을 누르면 그 단계를 적용하고 바로 시작합니다. 커스텀은 지금 옵션 그대로 시작합니다.
 * 상세 조정의 '설정 단계' 칩과 같은 [EnhancementPreset.select]를 써서 두 곳의 선택이 항상 같습니다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PresetStartButtons(image: Boolean, enabled: Boolean = true, onStart: () -> Unit) {
    val selected = remember(image, EnhancementPreset.edits, EnhancementPreset.reload) { EnhancementPreset.selected(image) }
    Text("설정 단계를 눌러 시작", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EnhancementPreset.names.forEach { (value, label) ->
            // 단계를 적용한 뒤 시작합니다(현재 단계는 채운 버튼으로 표시).
            val start = { EnhancementPreset.select(value, image); onStart() }
            if (value == selected) Button(onClick = start, enabled = enabled) { Text(label) }
            else OutlinedButton(onClick = start, enabled = enabled) { Text(label) }
        }
    }
    Text("속도 → 최고로 갈수록 느리지만 디테일이 좋아집니다. 커스텀은 아래 '상세 조정'의 현재 옵션 그대로 시작합니다.",
        style = MaterialTheme.typography.bodySmall)
}

/** 처리 창에 지금 실행 중인 단계(준비·전처리·AI 업스케일·보간·후처리·저장)를 표시합니다. 이미지·영상 창이 같이 씁니다. */
@Composable
internal fun ProcessingStage() {
    val stage by EnhancementClient.stage.collectAsState()
    if (stage.isNotBlank()) Text("현재 작업: $stage", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

/** 옵션 묶음 제목입니다. 설정 화면이 처리 순서대로 배치되도록 단계 번호와 위치를 보여 줍니다. */
@Composable
internal fun StageHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}
