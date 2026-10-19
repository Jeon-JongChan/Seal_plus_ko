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
            }, label = { Text(label) })
        }
    }
    Text("AV1 우선은 미지원 시 원본 코덱으로 저장합니다. H.264·H.265를 직접 선택하면 해당 코덱을 지원하는 기기에서만 처리합니다.", style = MaterialTheme.typography.bodySmall)
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
    Text("GPU가 없거나 초기화에 실패하면 오류를 표시하며 보간을 몰래 생략하지 않습니다. NPU·GPU 사용률은 기기·모델·발열에 따라 달라지며 100%를 보장하지 않습니다.",
        style = MaterialTheme.typography.bodySmall)
}
