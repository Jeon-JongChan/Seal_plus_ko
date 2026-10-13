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
