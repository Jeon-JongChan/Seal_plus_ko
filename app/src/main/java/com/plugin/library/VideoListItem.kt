package com.plugin.library

import com.junkfood.seal.ui.component.*

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.AsyncImageImpl
import com.junkfood.seal.util.toFileSizeText
import com.plugin.compatibility.MediaRules



@OptIn(ExperimentalFoundationApi::class)
@Composable
/** MediaListItem에 필요한 파일 표시와 사용자 동작을 연결합니다. */
fun MediaListItem(
    modifier: Modifier = Modifier,
    title: String = "",
    author: String = "",
    thumbnailUrl: String = "",
    videoPath: String = "",
    videoUrl: String = "",
    videoFileSize: Long = 0L,
    postIndicator: (@Composable () -> Unit)? = null,
    isSelectEnabled: () -> Boolean = { false },
    isSelected: () -> Boolean = { false },
    onSelect: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    onShowContextMenu: () -> Unit = {},
    onWallpaperClick: (() -> Unit)? = null,
    onInstagramClick: (() -> Unit)? = null,
) {
    val isAudio = MediaRules.mediaKind(videoPath) == 3
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val isFileAvailable = videoFileSize != 0L
    val fileSizeText = videoFileSize.toFileSizeText()

    Box(
        modifier =
            with(modifier) {
                    if (!isSelectEnabled())
                        combinedClickable(
                            enabled = true,
                            onClick = { onClick() },
                            onClickLabel = stringResource(R.string.open_file),
                            onLongClick = {
                                onLongClick()
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onLongClickLabel = stringResource(R.string.multiselect_mode),
                        )
                    else selectable(selected = isSelected(), onClick = onSelect)
                }
                .fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(if (isAudio) 4f else 16f / 9f)) {
                MediaImage(modifier = Modifier.fillMaxWidth(), imageModel = thumbnailUrl, isAudio = isAudio)
                Box(Modifier.align(Alignment.BottomStart)) { postIndicator?.invoke() }
                if (isSelectEnabled()) Checkbox(isSelected(), onCheckedChange = { onSelect() },
                    modifier = Modifier.align(Alignment.TopStart))
                else com.plugin.ui.MediaQuickActions(Modifier.align(Alignment.TopEnd),
                    onWallpaper = if (isAudio) null else onWallpaperClick,
                    onInstagram = if (isAudio || !com.plugin.compatibility.WallpaperHelper.isInstagramUrl(videoUrl)) null else onInstagramClick,
                    onMore = onShowContextMenu)
            }
            Column(
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.Top,
            ) {
                Text(
                    text = com.plugin.ui.postCardTitle(title, author),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (author.isNotBlank() && author != "null" && !com.plugin.PluginState.isEnabled(com.plugin.PluginId.POST_INFO) && !title.startsWith("$author ["))
                    Text(
                        modifier = Modifier.padding(top = 3.dp),
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                Text(
                    modifier = Modifier.padding(top = 3.dp),
                    text =
                        if (isFileAvailable) fileSizeText else stringResource(R.string.unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        with(MaterialTheme.colorScheme) {
                            if (isFileAvailable) onSurfaceVariant else error
                        },
                    maxLines = 1,
                )
                if (videoUrl.isNotBlank()) {
                    com.plugin.ui.PostInfoBadge(
                        postUrl = videoUrl,
                        modifier = Modifier.padding(top = 2.dp),
                        fallbackAuthor = author
                    )
                }
            }
        }

    }
}

@Composable
/** MediaImage에 필요한 파일 표시와 사용자 동작을 연결합니다. */
fun MediaImage(
    modifier: Modifier = Modifier,
    imageModel: String,
    isAudio: Boolean = false,
    contentDescription: String? = null,
) {
    AsyncImageImpl(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(if (isAudio) 4f else 16f / 9f)
                .clip(MaterialTheme.shapes.extraSmall),
        model = imageModel,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
    )
}
