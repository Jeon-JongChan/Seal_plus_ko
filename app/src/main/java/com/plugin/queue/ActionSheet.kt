package com.plugin.queue

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R

import com.plugin.queue.PluginTask as Task
import com.plugin.queue.PluginTask.*
import com.plugin.queue.PluginTask.DownloadState.Canceled
import com.plugin.queue.PluginTask.DownloadState.Completed
import com.plugin.queue.PluginTask.DownloadState.Error
import com.plugin.queue.PluginTask.DownloadState.FetchingInfo
import com.plugin.queue.PluginTask.DownloadState.Idle
import com.plugin.queue.PluginTask.DownloadState.ReadyWithInfo
import com.plugin.queue.PluginTask.DownloadState.Running
import com.junkfood.seal.ui.common.LocalFixedColorRoles
import com.junkfood.seal.ui.component.ActionSheetItem
import com.junkfood.seal.ui.component.ActionSheetPrimaryButton
import com.junkfood.seal.ui.theme.ErrorTonalPalettes
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.toBitrateText
import com.junkfood.seal.util.toDurationText
import com.junkfood.seal.util.toFileSizeText
import com.junkfood.seal.util.toLocalizedString

@Composable
/** ShareButton의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
private fun ShareButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionSheetPrimaryButton(
        modifier = modifier,
        containerColor = LocalFixedColorRoles.current.secondaryFixed,
        contentColor = LocalFixedColorRoles.current.onSecondaryFixedVariant,
        imageVector = Icons.Rounded.Share,
        text = stringResource(R.string.share),
        onClick = onClick,
    )
}

@Composable
/** PlayButton의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
private fun PlayButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionSheetPrimaryButton(
        modifier = modifier,
        containerColor = LocalFixedColorRoles.current.primaryFixed,
        contentColor = LocalFixedColorRoles.current.onPrimaryFixedVariant,
        imageVector = Icons.Rounded.PlayArrow,
        text = stringResource(R.string.open_file),
        onClick = onClick,
    )
}

@Composable
/** ResumeButton의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
private fun ResumeButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionSheetPrimaryButton(
        modifier = modifier,
        containerColor = LocalFixedColorRoles.current.tertiaryFixed,
        contentColor = LocalFixedColorRoles.current.onTertiaryFixedVariant,
        imageVector = Icons.Outlined.RestartAlt,
        text = stringResource(R.string.resume),
        onClick = onClick,
    )
}

@Composable
/** ErrorReportButton의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
private fun ErrorReportButton(modifier: Modifier = Modifier, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    ActionSheetPrimaryButton(
        // 0.5초 이상 누르면 복사 대신 미리보기를 열도록 초기 패스에서 길게 누름을 감지하고 뒤따르는 클릭을 소비합니다.
        modifier = modifier.longPressFirst(onLongClick),
        containerColor = ErrorTonalPalettes.accent1(80.0),
        contentColor = ErrorTonalPalettes.accent1(10.0),
        imageVector = Icons.Outlined.ErrorOutline,
        text = stringResource(R.string.copy_error_report),
        onClick = onClick,
    )
}

@Composable
/** DeleteButton의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
private fun DeleteButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionSheetPrimaryButton(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        imageVector = Icons.Outlined.Delete,
        outlineColor = MaterialTheme.colorScheme.outlineVariant,
        text = stringResource(R.string.delete),
        onClick = onClick,
    )
}

@Composable
/** CancelButton의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
private fun CancelButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionSheetPrimaryButton(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        imageVector = Icons.Outlined.Cancel,
        text = stringResource(R.string.cancel),
        onClick = onClick,
    )
}

@Composable
/** CopyURLButton의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
private fun CopyURLButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionSheetPrimaryButton(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        outlineColor = MaterialTheme.colorScheme.outlineVariant,
        imageVector = Icons.Outlined.ContentCopy,
        text = stringResource(R.string.copy_link),
        onClick = onClick,
    )
}

@Composable
/** OpenVideoURLButton의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
private fun OpenVideoURLButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionSheetPrimaryButton(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        outlineColor = MaterialTheme.colorScheme.outlineVariant,
        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
        text = stringResource(R.string.open_url),
        onClick = onClick,
    )
}

@Composable
/** OpenThumbnailURLButton의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
private fun OpenThumbnailURLButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    ActionSheetPrimaryButton(
        modifier = modifier,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        outlineColor = MaterialTheme.colorScheme.outlineVariant,
        imageVector = Icons.Outlined.Image,
        text = stringResource(R.string.thumbnail),
        onClick = onClick,
    )
}

@Composable
/** Title의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
fun Title(imageModel: Any?, title: String, author: String, downloadState: DownloadState) {

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /*        AsyncImageImpl(
            model = imageModel,
            modifier =
                Modifier.height(64.dp).aspectRatio(16f / 9f, matchHeightConstraintsFirst = true),
            contentDescription = null,
            contentScale = ContentScale.Crop,
        )*/
        //        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.height(IntrinsicSize.Min)) {
            Column(Modifier) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Spacer(Modifier.height(8.dp))

            ListItemStateText(downloadState = downloadState)
        }
    }
}

@Composable
/** SheetContent의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
fun SheetContent(
    task: Task,
    viewState: ViewState,
    downloadState: DownloadState,
    onDismissRequest: () -> Unit,
    onActionPost: (Task, UiAction) -> Unit,
) {

    LazyColumn {
        item {
            Title(
                imageModel = viewState.thumbnailUrl,
                title = viewState.title,
                author = viewState.uploader,
                downloadState = downloadState,
            )
        }

        item {
            LazyRow(
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                ActionButtons(
                    task = task,
                    downloadState = downloadState,
                    viewState = viewState,
                    onDismissRequest = onDismissRequest,
                    onActionPost = onActionPost,
                )
            }
        }

        item { ActionSheetInfo(task = task, viewState = viewState) }
    }
}

/** ActionButtons의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
fun LazyListScope.ActionButtons(
    task: Task,
    downloadState: DownloadState,
    viewState: ViewState,
    onDismissRequest: () -> Unit,
    onActionPost: (Task, UiAction) -> Unit,
) {
    when (downloadState) {
        is Canceled -> {
            item(key = "ResumeButton") {
                ResumeButton(modifier = Modifier.animateItem()) {
                    onActionPost(task, UiAction.Resume)
                    onDismissRequest()
                }
            }
        }
        is Completed -> {
            if (!downloadState.missing) {
            item(key = "PlayButton") {
                PlayButton(modifier = Modifier.animateItem()) {
                    onActionPost(task, UiAction.OpenFile(downloadState.filePath))
                    onDismissRequest()
                }
            }
            item(key = "ShareButton") {
                ShareButton(modifier = Modifier.animateItem()) {
                    onActionPost(task, UiAction.ShareFile(downloadState.filePath))
                }
            }

            }
        }
        is Error -> {
            item(key = "ResumeButton") {
                ResumeButton(modifier = Modifier.animateItem()) {
                    onActionPost(task, UiAction.Resume)
                    onDismissRequest()
                }
            }
            item(key = "ErrorReportButton") {
                var preview by remember { mutableStateOf(false) }
                ErrorReportButton(modifier = Modifier.animateItem(), onLongClick = { preview = true }) {
                    onActionPost(task, UiAction.CopyErrorReport(downloadState.throwable))
                }
                if (preview) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { preview = false },
                    title = { Text(stringResource(R.string.loc_report_preview_title)) },
                    text = {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(com.junkfood.seal.util.getErrorReport(downloadState.throwable, task.url),
                                modifier = Modifier.heightIn(max = 360.dp)
                                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { preview = false }) { Text(stringResource(R.string.confirm)) }
                    })
            }
        }
        is FetchingInfo,
        ReadyWithInfo,
        Idle,
        is Running -> {
            item(key = "CancelButton") {
                CancelButton(modifier = Modifier.animateItem()) {
                    onActionPost(task, UiAction.Cancel)
                    onDismissRequest()
                }
            }
        }
    }
    if (downloadState is DownloadState.Restartable || downloadState is Completed) {
        item(key = "DeleteButton") {
            DeleteButton(modifier = Modifier.animateItem()) {
                onActionPost(task, UiAction.Delete)
                onDismissRequest()
            }
        }
    }
    item(key = "CopyURLButton") {
        CopyURLButton(modifier = Modifier.animateItem()) {
            onActionPost(task, UiAction.CopyVideoURL)
        }
    }
    item(key = "OpenVideoURLButton") {
        OpenVideoURLButton(modifier = Modifier.animateItem()) {
            onActionPost(task, UiAction.OpenVideoURL(viewState.url))
        }
    }
    if (!viewState.thumbnailUrl.isNullOrEmpty()) {
        item(key = "OpenThumbnailURLButton") {
            OpenThumbnailURLButton(modifier = Modifier.animateItem()) {
                onActionPost(task, UiAction.OpenThumbnailURL(viewState.thumbnailUrl))
            }
        }
    }
    item(key = "WallpaperButton") {
        ActionSheetPrimaryButton(
            modifier = Modifier.animateItem(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            outlineColor = MaterialTheme.colorScheme.outlineVariant,
            imageVector = Icons.Outlined.Wallpaper,
            text = stringResource(R.string.loc_wallpaper_setting),
            onClick = {
                val filePath = (downloadState as? Completed)?.filePath
                onActionPost(task, UiAction.OpenWallpaper(url = viewState.thumbnailUrl.orEmpty(), filePath = filePath))
                onDismissRequest()
            }
        )
    }
    item(key = "OpenInstagramButton") {
        ActionSheetPrimaryButton(
            modifier = Modifier.animateItem(),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            outlineColor = MaterialTheme.colorScheme.outlineVariant,
            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
            text = stringResource(R.string.loc_open_instagram),
            onClick = {
                onActionPost(task, UiAction.OpenInstagram(viewState.url))
                onDismissRequest()
            }
        )
    }
}


@Composable
/** ActionSheetInfo의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
fun ActionSheetInfo(modifier: Modifier = Modifier, task: Task, viewState: ViewState) {
    with(viewState) {
        Column(modifier = modifier) {
            HorizontalDivider()
            Text(
                stringResource(R.string.media_info),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
            )
            ActionSheetItem(
                text = {
                    Text(
                        task.timeCreated.toLocalizedString(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "${duration.toDurationText()} · ${fileSizeApprox.toFileSizeText()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.FileDownload, contentDescription = null)
                },
            )

            videoFormats?.forEachIndexed { _index, fmt ->
                val index = _index + 1
                val fileSizeText = (fmt.fileSize ?: fmt.fileSizeApprox).toFileSizeText()
                val bitRateText = fmt.vbr.toBitrateText()
                val codecText = fmt.vcodec?.substringBefore(delimiter = ".") ?: ""

                val title = "${stringResource(R.string.video)} #$index: ${fmt.formatNote}"
                val details =
                    listOf(codecText, fmt.resolution, bitRateText, fileSizeText)
                        .filterNot { it.isNullOrBlank() }
                        .joinToString(separator = " · ")

                ActionSheetItem(
                    text = {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(details, style = MaterialTheme.typography.bodySmall)
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.VideoFile, contentDescription = null)
                    },
                )
            }

            val audioFormats: List<Format> = buildList {
                videoFormats?.filter { it.containsAudio() }?.let { addAll(it) }
                audioOnlyFormats?.let { addAll(it) }
            }

            audioFormats.forEachIndexed { _index, fmt ->
                val index = _index + 1
                val fileSizeText = (fmt.fileSize ?: fmt.fileSizeApprox).toFileSizeText()
                val bitRateText = fmt.abr.toBitrateText()
                val codecText = fmt.acodec?.substringBefore(delimiter = ".") ?: ""

                val title = "${stringResource(R.string.audio)} #$index: ${fmt.formatNote}"
                val details =
                    listOf(codecText, bitRateText, fileSizeText)
                        .filterNot { it.isBlank() }
                        .joinToString(separator = " · ")

                ActionSheetItem(
                    text = {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(details, style = MaterialTheme.typography.bodySmall)
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Outlined.AudioFile, contentDescription = null)
                    },
                )
            }

            ActionSheetItem(
                text = {
                    Text(text = extractorKey, style = MaterialTheme.typography.titleSmall)
                    Text(text = url, style = MaterialTheme.typography.bodySmall)
                },
                leadingIcon = { Icon(imageVector = Icons.Outlined.Link, contentDescription = null) },
            )
        }
    }
}

/** 자식 클릭보다 먼저 0.5초 길게 누름을 감지하고, 성립하면 손을 뗄 때의 클릭을 소비해 중복 동작을 막습니다. */
private fun Modifier.longPressFirst(onLongPress: () -> Unit): Modifier = pointerInput(onLongPress) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var timedOut = true
        withTimeoutOrNull(500L) { waitForUpOrCancellation(PointerEventPass.Initial); timedOut = false }
        if (timedOut) {
            onLongPress()
            waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
        }
    }
}