package com.plugin.queue

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

private val IconButtonSize = 48.dp
private val IconSize = 28.dp
private val ActionButtonContainerColor: Color
    @Composable get() = LocalFixedColorRoles.current.onSecondaryFixed.copy(alpha = 0.68f)
private val ActionButtonContentColor: Color
    @Composable get() = LocalFixedColorRoles.current.secondaryFixed
private val LabelContainerColor: Color = Color.Black.copy(alpha = 0.68f)

/** 게시물 성공 수·혼합 개수·해상도 범위와 읽을 수 있는 평균 비트레이트를 표시합니다. */
@Composable
fun PostMediaDetails(state: Task.ViewState, overlay: Boolean = false, missingPages: Int = 0) {
    val text = buildList {
        add(stringResource(R.string.loc_page_success, state.completedCount, state.mediaCount))
        if (missingPages > 0) add(stringResource(R.string.loc_page_missing, missingPages))
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

@Composable
/** VideoCardV2에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun VideoCardV2(
    modifier: Modifier = Modifier,
    viewState: Task.ViewState,
    selected: Boolean = false,
    stateIndicator: @Composable (BoxScope.() -> Unit)? = null,
    actionButton: @Composable (BoxScope.() -> Unit)? = null,
    onButtonClick: () -> Unit,
) {
    with(viewState) {
        VideoCardV2(
            modifier = modifier,
            thumbnailModel = thumbnailUrl,
            selected = selected,
            title = title,
            uploader = uploader,
            duration = duration,
            fileSizeApprox = fileSizeApprox,
            isImage = isImage, imageCount = mediaCount, width = width, height = height,
            stateIndicator = stateIndicator,
            actionButton = actionButton,
            onButtonClick = onButtonClick,
        )
    }
}

@Composable
/** VideoListItem에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun VideoListItem(
    modifier: Modifier = Modifier,
    viewState: Task.ViewState,
    selected: Boolean = false,
    stateIndicator: @Composable (() -> Unit)? = null,
    onButtonClick: () -> Unit,
) {
    with(viewState) {
        VideoListItem(
            modifier = modifier,
            thumbnailModel = thumbnailUrl,
            selected = selected,
            title = title,
            uploader = uploader,
            duration = duration,
            fileSizeApprox = fileSizeApprox,
            isImage = isImage, imageCount = mediaCount, width = width, height = height,
            stateIndicator = stateIndicator,
            onButtonClick = onButtonClick,
        )
    }
}

@Composable
/** VideoListItem에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun VideoListItem(
    modifier: Modifier = Modifier,
    thumbnailModel: Any? = null,
    selected: Boolean = false,
    title: String = "",
    uploader: String = "",
    duration: Int = 0,
    fileSizeApprox: Double = .0,
    isImage: Boolean = false, imageCount: Int = 1, width: Int = 0, height: Int = 0,
    stateIndicator: @Composable (() -> Unit)? = null,
    onButtonClick: () -> Unit,
) {
    Row(modifier = modifier.background(if (selected) Color.Black.copy(alpha = 0.18f) else Color.Transparent).height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier) {
            ListItemImage(modifier = Modifier, thumbnailModel = thumbnailModel)
            if (!isImage) VideoInfoLabel(
                modifier = Modifier.align(Alignment.BottomEnd),
                duration = duration,
                fileSizeApprox = fileSizeApprox,
                isImage = isImage, imageCount = imageCount, width = width, height = height,
            )
        }
        Box {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                TitleText(
                    modifier = Modifier,
                    title = title,
                    uploader = uploader,
                    contentPadding = PaddingValues(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                stateIndicator?.invoke()
            }
            IconButton(
                onButtonClick,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 8.dp, y = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = stringResource(R.string.show_more_actions),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Preview
@Composable
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES)
/** VideoListItemPreview에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun VideoListItemPreview() {
    SealTheme {
        val fakeStateList =
            listOf(
                Running(Job(), "", 0.58f),
                Error(throwable = Throwable(), RestartableAction.Download),
                FetchingInfo(Job(), ""),
                Canceled(RestartableAction.Download),
                ReadyWithInfo,
                Idle,
                Completed(null),
            )

        var downloadState: Task.DownloadState by remember { mutableStateOf(Idle) }

        LaunchedEffect(Unit) {
            fakeStateList.forEach {
                delay(2000)
                downloadState = it
            }
        }

        Surface {
            VideoListItem(
                modifier = Modifier.padding(vertical = 8.dp, horizontal = 20.dp),
                thumbnailModel = R.drawable.sample3,
                title = stringResource(R.string.video_title_sample_text),
                uploader = stringResource(R.string.video_creator_sample_text),
                stateIndicator = {
                    ListItemStateText(
                        modifier = Modifier.padding(top = 3.dp),
                        downloadState = downloadState,
                    )
                },
            ) {}
        }
    }
}

@Composable
/** VideoCardV2에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun VideoCardV2(
    modifier: Modifier = Modifier,
    thumbnailModel: Any? = null,
    selected: Boolean = false,
    title: String = "",
    uploader: String = "",
    duration: Int = 0,
    fileSizeApprox: Double = .0,
    isImage: Boolean = false, imageCount: Int = 1, width: Int = 0, height: Int = 0,
    stateIndicator: @Composable (BoxScope.() -> Unit)? = null,
    actionButton: @Composable (BoxScope.() -> Unit)? = null,
    onButtonClick: () -> Unit,
) {
    val containerColor =
        MaterialTheme.colorScheme.run {
            if (LocalDarkTheme.current.isDarkTheme()) surfaceContainer else surfaceContainerLowest
        }

    Card(
        modifier = modifier.fillMaxWidth().background(if (selected) Color.Black.copy(alpha = 0.18f) else Color.Transparent),
        colors = CardDefaults.cardColors(containerColor = if (selected) containerColor.copy(alpha = 0.6f) else containerColor),
    ) {
        Column {
            Box(Modifier.fillMaxWidth()) {
                CardImage(modifier = Modifier, thumbnailModel = thumbnailModel)
                if (selected) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
                Box(Modifier.align(Alignment.TopStart)) { stateIndicator?.invoke(this) }
                Box(Modifier.align(Alignment.Center)) { actionButton?.invoke(this) }
                if (!isImage) VideoInfoLabel(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    duration = duration,
                    fileSizeApprox = fileSizeApprox,
                isImage = isImage, imageCount = imageCount, width = width, height = height,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TitleText(modifier = Modifier.weight(1f), title = title, uploader = uploader)
                IconButton(onButtonClick, modifier = Modifier.align(Alignment.CenterVertically)) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.show_more_actions),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
@Preview
@Preview(name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES)
/** VideoCardV2Preview에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun VideoCardV2Preview() {
    SealTheme {
        val downloadState = Error(throwable = Throwable(), action = RestartableAction.Download)
        VideoCardV2(
            thumbnailModel = R.drawable.sample3,
            title = stringResource(R.string.video_title_sample_text),
            uploader = stringResource(R.string.video_creator_sample_text),
            actionButton = { ActionButton(modifier = Modifier, downloadState = downloadState) {} },
            stateIndicator = {
                CardStateIndicator(modifier = Modifier, downloadState = downloadState)
            },
        ) {}
    }
}

@Composable
/** CardImage에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun CardImage(modifier: Modifier = Modifier, thumbnailModel: Any? = null) {
    if (thumbnailModel != null) {
        AsyncImageImpl(
            modifier =
                modifier
                    .padding()
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true),
            model = thumbnailModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier =
                modifier
                    .padding()
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {}
    }
}

@Composable
/** ListItemImage에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun ListItemImage(modifier: Modifier = Modifier, thumbnailModel: Any? = null) {
    if (thumbnailModel != null) {
        AsyncImageImpl(
            model = thumbnailModel,
            modifier =
                Modifier.width(160.dp)
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    .clip(MaterialTheme.shapes.extraSmall),
            contentScale = ContentScale.Crop,
            contentDescription = null,
        )
    } else {
        Box(
            modifier =
                modifier
                    .width(160.dp)
                    .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {}
    }
}

@Composable
/** TitleText에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun TitleText(
    modifier: Modifier = Modifier,
    title: String,
    uploader: String,
    contentPadding: PaddingValues = PaddingValues(12.dp),
) {
    Column(
        modifier = modifier.padding(contentPadding),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            modifier = Modifier.padding(top = 3.dp),
            text = uploader,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
/** VideoInfoLabel에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun VideoInfoLabel(modifier: Modifier = Modifier, duration: Int, fileSizeApprox: Double,
    isImage: Boolean = false, imageCount: Int = 1, width: Int = 0, height: Int = 0) {
    Surface(
        modifier = modifier.padding(4.dp),
        color = LabelContainerColor,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        val fileSizeText = fileSizeApprox.toFileSizeText()
        val durationText = duration.toDurationText()
        Text(
            modifier = Modifier.padding(horizontal = 4.dp),
            text = durationText,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

@Composable
/** CardStateIndicator에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun CardStateIndicator(modifier: Modifier = Modifier, downloadState: Task.DownloadState) {
    Surface(
        modifier = modifier.padding(vertical = 12.dp, horizontal = 8.dp),
        color = LabelContainerColor,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        CardItemStateText(
            modifier = Modifier.padding(horizontal = 4.dp),
            downloadState = downloadState,
        )
    }
}

@Composable
/** ListItemStateText에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun ListItemStateText(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = LocalDarkTheme.current.isDarkTheme(),
    downloadState: Task.DownloadState,
) {
    val sizeModifier = Modifier.size(14.dp)

    AnimatedContent(
        downloadState,
        transitionSpec = {
            materialSharedAxisY(initialOffsetY = { it / 5 }, targetOffsetY = { -it / 5 })
        },
        contentKey = { it::class.simpleName },
    ) { downloadState ->
        val text =
            when (downloadState) {
                is Canceled -> stringResource(R.string.status_canceled)
                is Completed -> stringResource(if (downloadState.missing) R.string.loc_deleted else R.string.status_downloaded)
                is Error -> stringResource(R.string.status_error)
                is FetchingInfo -> stringResource(R.string.status_fetching_video_info)
                Idle -> stringResource(R.string.status_enqueued)
                ReadyWithInfo -> stringResource(R.string.status_enqueued)
                is Running -> {
                    val progress = downloadState.progress
                    if (progress >= 0) {
                        "%.1f %%".format(downloadState.progress * 100)
                    } else {
                        stringResource(R.string.status_downloading)
                    }
                }
            }
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            when (downloadState) {
                is Canceled -> {
                    Icon(
                        imageVector = Icons.Outlined.Cancel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = sizeModifier,
                    )
                }
                is Completed -> {
                    val color = GreenTonalPalettes.accent1(if (isDarkTheme) 80.0 else 40.0)
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = color,
                        modifier = sizeModifier,
                    )
                }
                is Error -> {
                    Icon(
                        imageVector = Icons.Rounded.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = sizeModifier,
                    )
                }
                is FetchingInfo,
                Idle,
                ReadyWithInfo -> {
                    CircularProgressIndicator(modifier = sizeModifier, strokeWidth = 2.5.dp)
                }
                is Running -> {
                    val progress = downloadState.progress
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = sizeModifier,
                        strokeWidth = 2.5.dp,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = text,
                modifier = Modifier,
                style = MaterialTheme.typography.labelMedium.merge(letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
/** CardItemStateText에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun CardItemStateText(modifier: Modifier = Modifier, downloadState: Task.DownloadState) {
    val errorColor =
        MaterialTheme.colorScheme.run {
            if (LocalDarkTheme.current.isDarkTheme()) error else errorContainer
        }
    val textStyle = MaterialTheme.typography.labelSmall
    val contentColor = Color.White

    val text =
        when (downloadState) {
            is Canceled -> R.string.status_canceled
            is Completed -> if (downloadState.missing) R.string.loc_deleted else R.string.status_downloaded
            is Error -> R.string.status_error
            is FetchingInfo -> R.string.status_fetching_video_info
            Idle -> R.string.status_enqueued
            ReadyWithInfo -> R.string.status_enqueued
            is Running -> R.string.status_downloading
        }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (downloadState is Error) {
            Icon(
                imageVector = Icons.Rounded.Error,
                contentDescription = null,
                tint = errorColor,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = stringResource(id = text),
            modifier = Modifier,
            style = textStyle,
            color = contentColor,
        )
    }
}

/** 작업 상태에 맞는 취소·재시도·이미지 보기·영상 재생 버튼을 제공합니다. */
@Composable
fun ActionButton(
    modifier: Modifier = Modifier,
    downloadState: Task.DownloadState,
    isImage: Boolean = false,
    onActionPost: (UiAction) -> Unit,
) =
    when (downloadState) {
        is Error -> {
            RestartButton(modifier = modifier) { onActionPost(UiAction.Resume) }
        }
        is Canceled -> {
            ResumeButton(modifier = modifier, downloadState.progress) {
                onActionPost(UiAction.Resume)
            }
        }
        is Completed -> {
            if (!downloadState.missing) PlayVideoButton(modifier = modifier, isImage = isImage) {
                onActionPost(UiAction.OpenFile(downloadState.filePath))
            } else Unit
        }
        is FetchingInfo,
        ReadyWithInfo,
        Idle -> {
            ProgressButton(modifier = modifier, progress = -1f) { onActionPost(UiAction.Cancel) }
        }
        is Running -> {
            ProgressButton(modifier = modifier, progress = downloadState.progress) {
                onActionPost(UiAction.Cancel)
            }
        }
    }

@Composable
/** ResumeButton에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun ResumeButton(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onClick: () -> Unit,
) {
    val background = ActionButtonContainerColor

    Box(
        modifier =
            modifier
                .size(IconButtonSize)
                .clip(CircleShape)
                .drawBehind { drawCircle(background) }
                .clickable(onClickLabel = stringResource(R.string.cancel), onClick = onClick)
    ) {
        if (progress != null) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(IconButtonSize).align(Alignment.Center),
                color = ActionButtonContentColor,
                trackColor = Color.Transparent,
                gapSize = 0.dp,
            )
        }
        Icon(
            imageVector = Icons.Rounded.Download,
            contentDescription = stringResource(R.string.restart),
            modifier = Modifier.size(IconSize).align(Alignment.Center),
            tint = ActionButtonContentColor,
        )
    }
}

@Composable
/** RestartButton에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
fun RestartButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val background = ActionButtonContainerColor

    Box(
        modifier =
            modifier
                .size(IconButtonSize)
                .clip(CircleShape)
                .drawBehind { drawCircle(background) }
                .clickable(onClickLabel = stringResource(R.string.cancel), onClick = onClick)
    ) {
        Icon(
            imageVector = Icons.Rounded.RestartAlt,
            contentDescription = stringResource(R.string.restart),
            modifier = Modifier.size(IconSize).align(Alignment.Center),
            tint = ActionButtonContentColor,
        )
    }
}

@Composable
/** 이미지에는 보기 아이콘, 영상에는 재생 아이콘을 표시합니다. */
private fun PlayVideoButton(modifier: Modifier = Modifier, isImage: Boolean = false, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(IconButtonSize),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = ActionButtonContainerColor,
                contentColor = ActionButtonContentColor,
            ),
    ) {
        Icon(
            imageVector = if (isImage) Icons.Outlined.Image else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(R.string.open_file),
            modifier = Modifier.size(IconSize),
        )
    }
}

@Composable
/** ProgressButton에 필요한 화면 구성 또는 처리 규칙을 제공합니다. */
private fun ProgressButton(modifier: Modifier = Modifier, progress: Float, onClick: () -> Unit) {
    val animatedProgress by
        animateFloatAsState(
            progress,
            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
            label = "progress",
        )
    val background = ActionButtonContainerColor

    Box(
        modifier =
            modifier
                .size(IconButtonSize)
                .clip(CircleShape)
                .drawBehind { drawCircle(background) }
                .clickable(onClickLabel = stringResource(R.string.cancel), onClick = onClick)
    ) {
        if (progress < 0) {
            CircularProgressIndicator(
                modifier = Modifier.size(IconButtonSize).align(Alignment.Center),
                color = ActionButtonContentColor,
                trackColor = Color.Transparent,
            )
        } else {
            CircularProgressIndicator(
                { animatedProgress },
                modifier = Modifier.size(IconButtonSize).align(Alignment.Center),
                color = ActionButtonContentColor,
                gapSize = 0.dp,
                trackColor = Color.Transparent,
            )
        }
        Icon(
            imageVector = Icons.Rounded.Pause,
            contentDescription = null,
            modifier = Modifier.align(Alignment.Center).size(IconSize),
            tint = ActionButtonContentColor,
        )
    }
}
