package com.plugin.runtime

import com.plugin.compatibility.copyPreservingPluginOptions
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.download.DownloaderV2
import com.plugin.queue.PluginTask as Task
import com.plugin.queue.PluginTaskFactory
import com.plugin.compatibility.enqueueBatch
import com.junkfood.seal.util.*
import com.junkfood.seal.ui.page.downloadv2.configure.*
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.plugin.PluginState
import com.plugin.PluginId
import com.plugin.compatibility.*
import com.plugin.compatibility.MediaRules
import com.plugin.instagram.InstagramUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** 원본 ViewModel의 상태를 공유하고 확장 요청만 처리합니다. */
class PluginDialogController(
    private val scope: CoroutineScope,
    private val downloader: DownloaderV2,
    private val mSheetStateFlow: MutableStateFlow<Any>,
    private val mSelectionStateFlow: MutableStateFlow<SelectionState>,
    private val hideDialog: () -> Unit,
    private val onSelectionPreferences: (DownloadUtil.DownloadPreferences) -> Unit,
) {
    private val socialController by lazy {
        com.plugin.instagram.SocialDownloadController(scope, downloader,
            { mSheetStateFlow.value = it }, hideDialog, ::downloadWithPreset)
    }

    private val listController by lazy {
        com.plugin.listdownload.ListDownloadController(scope, downloader,
            { mSheetStateFlow.value = it }, hideDialog, ::downloadWithPreset)
    }

    /** 새 요청 사본에 현재 활성 기능만 적용합니다. */
    fun filter(action: Any): Any {
        return when (action) {
            is Action.FetchPlaylist -> action.copy(preferences = com.plugin.PluginState.preferences(action.preferences))
            is Action.FetchFormats -> action.copy(preferences = com.plugin.PluginState.preferences(action.preferences))
            is Action.DownloadWithPreset -> action.copy(preferences = com.plugin.PluginState.preferences(action.preferences))
            is Action.RunCommand -> action.copy(preferences = com.plugin.PluginState.preferences(action.preferences))
            is InspectInstagramAction -> action.copy(preferences = com.plugin.PluginState.preferences(action.preferences))
            is DownloadInstagramAction -> action.copy(preferences = com.plugin.PluginState.preferences(action.preferences))
            else -> action
        }
    }

    /** 처리할 확장 요청이면 true를 반환하고 나머지는 원본 함수에 맡깁니다. */
    fun handle(action: Any): Boolean {
        val social = PluginState.isEnabled(PluginId.SOCIAL)
        when (action) {
            is InspectInstagramAction -> if (action.preferences.listProcessing) listController.inspect(action) else socialController.inspect(action)
            is DownloadInstagramAction -> if (action.preferences.listProcessing) listController.download(action) else socialController.download(action)
            is Action.FetchPlaylist -> {
                if (action.preferences.listProcessing && (MediaRules.instagramAccount(action.url) != null ||
                    MediaRules.usesSocialMediaApi(action.url, action.preferences.extractAudio))) {
                    listController.inspect(InspectInstagramAction(action.url, action.preferences))
                    return true
                }
                if (!action.preferences.downloadAllMedia && action.preferences.mediaSelection != 2 &&
                    !(social && (MediaRules.instagramAccount(action.url) != null ||
                        MediaRules.usesSocialMediaApi(action.url, action.preferences.extractAudio)))) return false
                fetchPlaylist(action)
            }
            is Action.FetchFormats -> {
                if (!social || !MediaRules.usesSocialMediaApi(action.url, action.audioOnly)) return false
                fetchFormat(action)
            }
            is Action.DownloadWithPreset -> {
                if (!social || !action.urlList.any { MediaRules.instagramAccount(it) != null ||
                    MediaRules.usesSocialMediaApi(it, action.preferences.extractAudio) } && action.preferences.mediaSelection != 2) return false
                downloadWithPreset(action.urlList, action.preferences)
            }
            else -> return false
        }
        return true
    }

    /** 소셜 HTTP 조회도 일반 엔진 취소와 함께 중단합니다. */
    fun cancel(taskKey: String) {
        InstagramUtil.cancel(taskKey)
        com.plugin.listdownload.ListMediaUtil.cancel(taskKey)
    }

    /** 일반 재생목록은 기존 엔진, 이미지·계정 목록은 전용 API로 조회합니다. */
    private fun fetchPlaylist(action: Action.FetchPlaylist) {
        val (url, preferences) = action
        if (com.plugin.PluginState.isEnabled(com.plugin.PluginId.SOCIAL) && (MediaRules.instagramAccount(url) != null || MediaRules.usesSocialMediaApi(url, preferences.extractAudio))) {
            socialController.inspect(InspectInstagramAction(url, preferences))
            return
        }
        if (preferences.mediaSelection == 2) {
            mSheetStateFlow.value = SheetState.Error(action,
                IllegalArgumentException(App.context.getString(R.string.loc_image_supported_sites)))
            return
        }

        val job =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                (if (preferences.cookies) PluginDownloadUtil.getPlaylistOrVideoInfo(playlistURL = url, downloadPreferences = preferences) else DownloadUtil.getPlaylistOrVideoInfo(
                        playlistURL = url,
                        downloadPreferences = preferences,
                    ))
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            onSelectionPreferences(preferences)
                            when (info) {
                                is PlaylistResult -> {
                                    if (preferences.downloadAllMedia) {
                                        PluginTaskFactory.createWithPlaylistResult(url,
                                            (1..info.entries.orEmpty().size).toList(), info, preferences)
                                            .let { downloader.enqueueBatch(it) }
                                    } else mSelectionStateFlow.update {
                                        SelectionState.PlaylistSelection(result = info)
                                    }
                                }

                                is VideoInfo -> {
                                    mSelectionStateFlow.update {
                                        SelectionState.FormatSelection(info = info)
                                    }
                                }
                            }
                            // 조회 성공 후 닫기는 사용자 취소가 아니므로 조회 상태를 먼저 끝냅니다.
                            mSheetStateFlow.update { SheetState.InputUrl }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        if (th is CancellationException) throw th
                        mSheetStateFlow.update { SheetState.Error(action = action, throwable = th) }
                    }
            }
        mSheetStateFlow.update { SheetState.Loading(taskKey = "FetchPlaylist_$url", job = job) }
        job.start()
    }

    /** 영상 형식은 처음부터 기존 엔진으로 조회합니다. */
    private fun fetchFormat(action: Action.FetchFormats) {
        val (url, audioOnly, preferences) = action
        if (com.plugin.PluginState.isEnabled(com.plugin.PluginId.SOCIAL) && MediaRules.usesSocialMediaApi(url, audioOnly)) {
            socialController.inspect(InspectInstagramAction(url, preferences))
            return
        }

        val job =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                PluginDownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        preferences = preferences.copyPreservingPluginOptions { copy(extractAudio = audioOnly) },
                        taskKey = "FetchFormat_$url",
                    )
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            onSelectionPreferences(preferences)
                            mSelectionStateFlow.update {
                                SelectionState.FormatSelection(info = info)
                            }
                            // 조회 성공 후 닫기는 사용자 취소가 아니므로 조회 상태를 먼저 끝냅니다.
                            mSheetStateFlow.update { SheetState.InputUrl }
                            hideDialog()
                        }
                    }
                    .onFailure { th ->
                        if (th is CancellationException) throw th
                        withContext(Dispatchers.Main) {
                            mSheetStateFlow.update { SheetState.Error(action, throwable = th) }
                        }
                    }
            }

        mSheetStateFlow.update { SheetState.Loading(taskKey = "FetchFormat_$url", job = job) }
        job.start()
    }

    /** 일반 URL은 기존 작업으로 등록하고 소셜 게시물은 먼저 이미지·영상 항목을 분석합니다. */
    private fun downloadWithPreset(
        urlList: List<String>,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        if (com.plugin.PluginState.isEnabled(com.plugin.PluginId.SOCIAL) && urlList.any { MediaRules.instagramAccount(it) != null }) {
            mSheetStateFlow.value = SheetState.Error(Action.DownloadWithPreset(urlList, preferences),
                IllegalArgumentException(App.context.getString(R.string.loc_account_separate_menu)))
            return
        }
        val instagram = if (com.plugin.PluginState.isEnabled(com.plugin.PluginId.SOCIAL))
            urlList.filter { MediaRules.usesSocialMediaApi(it, preferences.extractAudio) } else emptyList()
        if (preferences.mediaSelection == 2 && urlList.any { it !in instagram }) {
            mSheetStateFlow.value = SheetState.Error(Action.DownloadWithPreset(urlList, preferences),
                IllegalArgumentException(App.context.getString(R.string.loc_image_supported_sites)))
            return
        }
        urlList
            .filterNot { it in instagram }
            .forEach { downloader.pluginQueue().enqueue(Task(url = it, preferences = preferences)) }
        if (instagram.isNotEmpty())
            socialController.inspect(InspectInstagramAction(instagram.first(), preferences, instagram))
        else hideDialog()
    }





}
