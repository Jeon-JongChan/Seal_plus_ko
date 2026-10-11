package com.plugin.listdownload

import com.plugin.compatibility.enqueueBatch

import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.download.DownloaderV2
import com.plugin.queue.PluginTask as Task
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.ToastUtil
import com.plugin.compatibility.MediaRules
import com.plugin.compatibility.mediaSelection
import com.plugin.compatibility.downloadAllMedia
import com.junkfood.seal.ui.page.downloadv2.configure.InspectInstagramAction
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadInstagramAction
import com.junkfood.seal.ui.page.downloadv2.configure.InstagramSelectionState
import kotlinx.coroutines.*

/** 소셜 게시물 분석과 작업 생성을 담당하며 호스트의 상태·엔진은 콜백으로 사용합니다. */
class ListDownloadController(
    private val scope: CoroutineScope,
    private val downloader: DownloaderV2,
    private val onState: (Any) -> Unit,
    private val onHide: () -> Unit,
    private val onOriginalDownload: (List<String>, DownloadUtil.DownloadPreferences) -> Unit,
) {
    /** 동종 게시물은 바로 다운로드하고 혼합 게시물·계정 목록만 파일 선택 화면을 엽니다. */
    fun inspect(action: InspectInstagramAction) {
        // 소셜 확장이 꺼지면 새 분석 요청을 기존 영상 다운로드로 보냅니다.
        if (!com.plugin.PluginState.isEnabled(com.plugin.PluginId.LIST)) {
            onOriginalDownload(action.urls, com.plugin.PluginState.preferences(action.preferences))
            return
        }
        val key = "Instagram_${action.url.hashCode()}"
        val accountList = action.urls.any { MediaRules.instagramAccount(it) != null }
        // 쿠키 실패 시 임의로 활성화하거나 비활성화하지 않고 사용자가 선택한 설정을 유지합니다.
        val preferences = action.preferences
        if (accountList) ToastUtil.makeToastSuspend(App.context.getString(R.string.fetching_playlist_info))
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            runCatching {
                action.urls.flatMap { ListMediaUtil.fetch(it, preferences, key) }.distinctBy { it.id }.also { items ->
                    // 선택 화면을 생략할 때 다운로드 종류 필터에 맞는 항목이 없으면 이유를 안내합니다.
                    check(items.any { MediaRules.accepts(preferences.mediaSelection, it.image) &&
                        (!preferences.extractAudio || !it.image) }) {
                        App.context.getString(R.string.loc_instagram_no_media)
                    }
                }
            }.onSuccess { items ->
                withContext(Dispatchers.Main) {
                    if (MediaRules.requiresMediaSelection(action.urls,
                        items, preferences.downloadAllMedia)) {
                        onState(InstagramSelectionState(items, preferences, accountList))
                    } else {
                        // 조회 상태를 먼저 끝내야 닫기 과정에서 현재 조회 작업이 스스로 취소되지 않습니다.
                        onState(SheetState.Configure(action.urls))
                        download(DownloadInstagramAction(items, preferences))
                    }
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                ToastUtil.makeToastSuspend(App.context.getString(R.string.fetch_info_error_msg))
                onState(SheetState.Error(Action.FetchPlaylist(action.url, action.preferences), error))
            }
        }
        onState(SheetState.Loading(key, job))
        job.start()
    }

    /** 캐러셀 파일별로 독립 작업과 메타데이터를 만들어 다운로드합니다. */
    fun download(action: DownloadInstagramAction) {
        if (!com.plugin.PluginState.isEnabled(com.plugin.PluginId.LIST)) {
            onState(SheetState.Error(Action.Reset, IllegalStateException(App.context.getString(R.string.plugin_list_disabled))))
            return
        }
        action.items
            .filter {
                com.plugin.compatibility.MediaRules.accepts(
                    action.preferences.mediaSelection,
                    it.image,
                ) && (!action.preferences.extractAudio || !it.image)
            }
            .distinctBy { it.id }
            .map { media -> media.copy(pageCount = maxOf(media.pageCount,
                action.items.count { com.plugin.compatibility.MediaRules.identity(it.postUrl) == com.plugin.compatibility.MediaRules.identity(media.postUrl) })) }
            .associate { media ->
                val info = media.videoInfo()
                val task = Task(media.postUrl, Task.TypeInfo.SocialMedia(media), action.preferences)
                task to Task.State(
                        Task.DownloadState.ReadyWithInfo,
                        info,
                        Task.ViewState.fromVideoInfo(info),
                    )
            }
            .let { downloader.enqueueBatch(it) }
        onHide()
    }
}
