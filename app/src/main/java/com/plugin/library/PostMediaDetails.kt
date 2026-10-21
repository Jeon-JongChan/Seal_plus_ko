package com.plugin.library

import com.junkfood.seal.ui.page.downloadv2.*
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.plugin.queue.PluginTask as Task
import com.junkfood.seal.util.toFileSizeText
import com.plugin.compatibility.MediaRules

private val LabelContainerColor: Color = Color.Black.copy(alpha = 0.68f)

/** 파일 목록 자체의 미디어 상세 정보를 표시합니다. */
@Composable
fun PostMediaDetails(state: Task.ViewState, overlay: Boolean = false, compact: Boolean = false) {
    val text = buildList {
        add(stringResource(R.string.loc_page_success, state.completedCount, state.mediaCount))
        if (state.imageCount > 0 && state.videoCount > 0)
            add(stringResource(R.string.loc_instagram_media_count, state.videoCount, state.imageCount))
        if (!compact) {
        if (state.width > 0 && state.height > 0) {
            val max = "${state.width}×${state.height}"
            add(if (state.minWidth > 0 && (state.minWidth != state.width || state.minHeight != state.height))
                "${state.minWidth}×${state.minHeight} – $max" else max)
        }
        state.averageMbps?.let { add("%.2f MB/s".format(java.util.Locale.ROOT, it / 8)) }
        if (state.fileSizeApprox > 0) add(state.fileSizeApprox.toFileSizeText())
        val codec = if (state.av1Mode > 0) state.originalCodec.ifBlank { state.codec } else state.codec.ifBlank { state.originalCodec }
        val labels = codec.split(" / ").map { MediaRules.codecLabel(it) }.filter(String::isNotBlank).distinct().joinToString(" / ")
        if (labels.isNotBlank()) add(labels)
        if (state.av1Mode > 0) add(stringResource(if (state.av1Mode == 2)
            R.string.revision5_encoded_keep else R.string.revision5_encoded_replace))
        }
    }.joinToString(" · ")
    Surface(color = if (overlay) LabelContainerColor else Color.Transparent,
        contentColor = if (overlay) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall,
            maxLines = if (overlay) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
}

