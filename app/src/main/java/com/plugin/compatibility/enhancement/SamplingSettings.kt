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

/** 처리 전에 저장한 축소 방식을 이미지·영상의 상세 설정에서 공유합니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SamplingSettings(enabled: Boolean = true) {
    var sampling by remember { mutableIntStateOf(EnhancementSampling.normalized(
        EnhancementSampling.KEY.getInt(EnhancementSampling.BILINEAR))) }
    Text("다운샘플링", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(EnhancementSampling.BILINEAR to "양선형 · 기본", EnhancementSampling.NEAREST to "최근접",
            EnhancementSampling.AREA to "영역 평균").forEach { (value, label) ->
            FilterChip(selected = sampling == value, enabled = enabled, onClick = {
                sampling = value; EnhancementSampling.KEY.updateInt(value)
            }, label = { Text(label) })
        }
    }
    Text("양선형은 기존 방식입니다. 최근접은 픽셀 경계를 유지하며, 영역 평균은 축소할 때 잔무늬를 줄입니다.",
        style = MaterialTheme.typography.bodySmall)
}
