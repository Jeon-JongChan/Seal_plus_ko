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
    var sampling by remember(EnhancementPreset.reload) { mutableIntStateOf(EnhancementSampling.normalized(
        EnhancementSampling.KEY.getInt(EnhancementSampling.BILINEAR))) }
    Text("다운샘플링", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(EnhancementSampling.BILINEAR to "양선형 · 기본", EnhancementSampling.NEAREST to "최근접",
            EnhancementSampling.AREA to "영역 평균").forEach { (value, label) ->
            FilterChip(selected = sampling == value, enabled = enabled, onClick = {
                sampling = value; EnhancementSampling.KEY.updateInt(value); EnhancementPreset.edited()
            }, label = { Text(label) })
        }
    }
    OptionHelp(sampling.toString(), listOf(
        EnhancementSampling.BILINEAR.toString() to "양선형 · 기본: 이웃 4픽셀을 부드럽게 섞어 축소합니다. 대부분의 사진·영상에 무난합니다.",
        EnhancementSampling.NEAREST.toString() to "최근접: 가장 가까운 픽셀을 그대로 가져옵니다. 도트·픽셀아트의 경계가 번지지 않지만 사진은 계단이 생길 수 있습니다.",
        EnhancementSampling.AREA.toString() to "영역 평균: 줄어드는 영역 전체를 평균 냅니다. 큰 이미지를 많이 줄일 때 잔무늬(모아레)가 가장 적고 느립니다.",
    ), enabled, note = "처리 단계: AI 업스케일 단계에서 모델 입력 크기에 맞춰 줄이거나, 4배 모델의 출력을 목표 크기로 줄일 때. 방식: 앱 내장 CPU 리사이즈(AI·FFmpeg 아님)이며, FFmpeg 전처리 엔진의 선 축소 scale 필터에도 같은 방식(neighbor/bilinear/area)을 적용합니다.")
}
