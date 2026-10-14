package com.plugin.compatibility.enhancement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean

/** 설정 화면과 작업 상세에서 영상의 저장 코덱 선택을 공유합니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoEncodingSettings(enabled: Boolean = true) {
    var original by remember { mutableStateOf(EnhancementModels.VIDEO_ORIGINAL_CODEC.getBoolean(false)) }
    Text("저장 코덱", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!original, enabled = enabled, onClick = { original = false; EnhancementModels.VIDEO_ORIGINAL_CODEC.updateBoolean(false) },
            label = { Text("AV1 우선 · 기본") })
        FilterChip(original, enabled = enabled, onClick = { original = true; EnhancementModels.VIDEO_ORIGINAL_CODEC.updateBoolean(true) },
            label = { Text("원본 코덱 유지") })
    }
    Text("AV1은 압축 효율을 높이지만 용량은 영상에 따라 달라집니다. 지원되지 않으면 원본 코덱으로 저장합니다.", style = MaterialTheme.typography.bodySmall)
}

/** 설정과 작업 상세 화면이 동일한 프레임 유지·증가 선택을 사용합니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FrameSettings(enabled: Boolean = true) {
    var fps by remember { mutableIntStateOf(EnhancementModels.VIDEO_FPS.getInt(0).takeIf { it in VideoRules.frameRates } ?: 0) }
    Text("프레임", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoRules.frameRates.forEach { value ->
            FilterChip(fps == value, enabled = enabled, onClick = { fps = value; EnhancementModels.VIDEO_FPS.updateInt(value) },
                label = { Text(if (value == 0) "원본 유지" else "$value fps") })
        }
    }
    Text("원본보다 높은 FPS를 선택할 때 RIFE Vulkan GPU로 보간합니다. GPU 미지원 시 원본 유지를 선택하세요. 모델은 처음 사용할 때 약 10MB를 다운로드합니다.",
        style = MaterialTheme.typography.bodySmall)
}
