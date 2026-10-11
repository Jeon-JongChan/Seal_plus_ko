package com.plugin.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import com.junkfood.seal.R
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.page.downloadv2.DownloadPageImplV2
import com.junkfood.seal.ui.page.downloadv2.UiAction
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.getErrorReport
import com.junkfood.seal.util.makeToast
import com.plugin.runtime.PluginDownloadDialogViewModel
import org.koin.compose.koinInject

/** 기본 대기열 UI를 재사용하고 다운로드 버튼은 공통 플러그인 호스트로 연결합니다. */
@Composable
fun BaselineDownloadPage(
    modifier: Modifier = Modifier,
    onMenuOpen: (() -> Unit) = {},
    dialogViewModel: PluginDownloadDialogViewModel,
    downloader: DownloaderV2 = koinInject(),
) {
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    DownloadPageImplV2(
        modifier = modifier,
        taskDownloadStateMap = downloader.getTaskStateMap(),
        downloadCallback = {
            view.slightHapticFeedback()
            dialogViewModel.postAction(Action.ShowSheet())
        },
        onMenuOpen = onMenuOpen,
    ) { task, action ->
        view.slightHapticFeedback()
        when (action) {
            UiAction.Cancel -> downloader.cancel(task)
            UiAction.Delete -> downloader.remove(task)
            UiAction.Resume -> downloader.restart(task)
            is UiAction.CopyErrorReport -> {
                clipboardManager.setText(
                    AnnotatedString(getErrorReport(action.throwable, task.url))
                )
                context.makeToast(R.string.error_copied)
            }
            UiAction.CopyVideoURL -> {
                clipboardManager.setText(AnnotatedString(task.url))
                context.makeToast(R.string.link_copied)
            }
            is UiAction.OpenFile -> {
                action.filePath?.let {
                    FileUtil.openFile(path = it) { context.makeToast(R.string.file_unavailable) }
                }
            }
            is UiAction.OpenThumbnailURL -> {
                uriHandler.openUri(action.url)
            }
            is UiAction.OpenVideoURL -> {
                uriHandler.openUri(action.url)
            }
            is UiAction.ShareFile -> {
                val shareTitle = context.getString(R.string.share)
                FileUtil.createIntentForSharingFile(action.filePath)?.let {
                    context.startActivity(Intent.createChooser(it, shareTitle))
                }
            }
        }
    }

}
