package com.plugin.library

import com.junkfood.seal.ui.page.downloadv2.*
import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.junkfood.seal.R
import com.plugin.queue.PluginTask as Task
import com.plugin.queue.PluginTask.DownloadState.Canceled
import com.plugin.queue.PluginTask.DownloadState.Completed
import com.plugin.queue.PluginTask.DownloadState.Error
import com.plugin.queue.PluginTask.DownloadState.FetchingInfo
import com.plugin.queue.PluginTask.DownloadState.Idle
import com.plugin.queue.PluginTask.DownloadState.ReadyWithInfo
import com.plugin.queue.PluginTask.DownloadState.Running
import com.plugin.queue.PluginTask.RestartableAction
import com.junkfood.seal.ui.common.AsyncImageImpl
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.LocalFixedColorRoles
import com.junkfood.seal.ui.common.motion.materialSharedAxisY
import com.junkfood.seal.ui.component.GreenTonalPalettes
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.toDurationText
import com.junkfood.seal.util.toFileSizeText
import com.plugin.compatibility.MediaRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

private val LabelContainerColor: Color = Color.Black.copy(alpha = 0.68f)

/** 파일 목록 자체의 미디어 상세 정보를 표시합니다. */
@Composable
fun PostMediaDetails(state: Task.ViewState, overlay: Boolean = false) {
    val text = buildList {
        add(stringResource(R.string.loc_page_success, state.completedCount, state.mediaCount))
        if (state.imageCount > 0 && state.videoCount > 0)
            add(stringResource(R.string.loc_instagram_media_count, state.videoCount, state.imageCount))
        if (state.width > 0 && state.height > 0) {
            val max = "${state.width}×${state.height}"
            add(if (state.minWidth > 0 && (state.minWidth != state.width || state.minHeight != state.height))
                "${state.minWidth}×${state.minHeight} – $max" else max)
        }
        state.averageMbps?.let { add("%.2f Mbps".format(java.util.Locale.ROOT, it)) }
        if (state.fileSizeApprox > 0) add(state.fileSizeApprox.toFileSizeText())
        val current = state.codec.ifBlank { state.originalCodec }.split(" / ").map { MediaRules.codecLabel(it) }.filter { it.isNotBlank() }.distinct().joinToString(" / ")
        if (current.isNotBlank()) add(current)
        if (state.av1Mode > 0 && state.originalCodec.isNotBlank())
            add(stringResource(R.string.loc_original_codec, state.originalCodec.split(" / ").map { MediaRules.codecLabel(it) }.distinct().joinToString(" / ")))
        if (state.av1Mode > 0) add(stringResource(if (state.av1Mode == 2) R.string.loc_av1_copied else R.string.loc_av1_replaced))
    }.joinToString(" · ")
    Surface(color = if (overlay) LabelContainerColor else Color.Transparent,
        contentColor = if (overlay) Color.White else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall,
            maxLines = if (overlay) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
    }
}

