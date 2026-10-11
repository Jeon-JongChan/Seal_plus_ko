package com.plugin.runtime

import com.plugin.compatibility.copyPreservingPluginOptions
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetValue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.PlaylistResult
import com.junkfood.seal.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart

private const val TAG = "DownloadDialogViewModel"

/** 원본 상태 계약을 유지하면서 확장 UI와 요청 처리기를 연결합니다. */
class PluginDownloadDialogViewModel(private val downloader: DownloaderV2) : ViewModel() {

    private val mSelectionStateFlow: MutableStateFlow<SelectionState> =
        MutableStateFlow(SelectionState.Idle)
    private val mSheetStateFlow: MutableStateFlow<Any> =
        MutableStateFlow(SheetState.InputUrl)
    private val mSheetValueFlow: MutableStateFlow<SheetValue> = MutableStateFlow(SheetValue.Hidden)

    val selectionStateFlow = mSelectionStateFlow.asStateFlow()
    val sheetStateFlow = mSheetStateFlow.asStateFlow()
    val sheetValueFlow = mSheetValueFlow.asStateFlow()

    /** 현재 표시 중인 기본 또는 확장 상태입니다. */
    private val sheetState
        get() = sheetStateFlow.value

    private val controller = PluginDialogController(viewModelScope, downloader,
        mSheetStateFlow, mSelectionStateFlow, ::hideDialog)

    /** 기능별 요청을 먼저 처리하고 기본 요청은 보존한 처리 흐름으로 전달합니다. */
    fun postAction(request: Any) {
        val action = controller.filter(request)
        if (controller.handle(action)) return
        when (action) {
            is Action.ProceedWithURLs -> proceedWithUrls(action)
            is Action.FetchFormats -> fetchFormat(action)
            is Action.FetchPlaylist -> fetchPlaylist(action)
            is Action.DownloadWithPreset -> downloadWithPreset(action.urlList, action.preferences)
            is Action.RunCommand -> runCommand(action.url, action.template, action.preferences)
            Action.HideSheet -> hideDialog()
            is Action.ShowSheet -> showDialog(action)
            Action.Cancel -> cancel()
            Action.Reset -> resetSelectionState()
        }
    }

    /** 입력한 링크를 설정 단계로 전달합니다. */
    private fun proceedWithUrls(action: Action.ProceedWithURLs) {
        mSheetStateFlow.update { SheetState.Configure(action.urlList) }
    }

    /** 기본 재생목록 조회 결과를 선택 화면으로 전달합니다. */
    private fun fetchPlaylist(action: Action.FetchPlaylist) {
        val (url, preferences) = action

        val job =
            viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                DownloadUtil.getPlaylistOrVideoInfo(
                        playlistURL = url,
                        downloadPreferences = preferences,
                    )
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            when (info) {
                                is PlaylistResult -> {
                                    mSelectionStateFlow.update {
                                        SelectionState.PlaylistSelection(result = info)
                                    }
                                }

                                is VideoInfo -> {
                                    mSelectionStateFlow.update {
                                        SelectionState.FormatSelection(info = info)
                                    }
                                }
                            }
                            mSheetStateFlow.value = SheetState.InputUrl
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

    /** 기본 형식 조회 결과를 선택 화면으로 전달합니다. */
    private fun fetchFormat(action: Action.FetchFormats) {
        val (url, audioOnly, preferences) = action

        val job =
            viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        preferences = preferences.copyPreservingPluginOptions { copy(extractAudio = audioOnly) },
                        taskKey = "FetchFormat_$url",
                    )
                    .onSuccess { info ->
                        withContext(Dispatchers.Main) {
                            mSelectionStateFlow.update {
                                SelectionState.FormatSelection(info = info)
                            }
                            mSheetStateFlow.value = SheetState.InputUrl
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

    /** 기본 링크를 대기열에 등록하고 창을 닫습니다. */
    private fun downloadWithPreset(
        urlList: List<String>,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        urlList.forEach { downloader.enqueue(Task(url = it, preferences = preferences)) }
        hideDialog()
    }

    /** 사용자 명령 작업을 기본 엔진 계약으로 등록합니다. */
    private fun runCommand(
        url: String,
        template: CommandTemplate,
        preferences: DownloadUtil.DownloadPreferences,
    ) {
        val task =
            Task(
                url = url,
                type = Task.TypeInfo.CustomCommand(template = template),
                preferences = preferences,
            )
        downloader.enqueue(task)
    }

    /** 창을 숨기고 진행 중인 조회를 취소합니다. */
    private fun hideDialog() {
        mSheetValueFlow.update { SheetValue.Hidden }
        when (sheetState) {
            is SheetState.Loading -> {
                cancel()
            }

            else -> {}
        }
    }

    /** 공유 링크 유무에 맞춰 입력 또는 설정 화면을 엽니다. */
    private fun showDialog(action: Action.ShowSheet) {
        // 다음 공유 요청이 이전 조회 결과나 선택 화면과 섞이지 않도록 정리합니다.
        cancel()
        resetSelectionState()
        val urlList = action.urlList
        if (!urlList.isNullOrEmpty()) {
            mSheetStateFlow.update { SheetState.Configure(urlList) }
        } else {
            mSheetStateFlow.update { SheetState.InputUrl }
        }
        mSheetValueFlow.update { SheetValue.Expanded }
    }

    /** 외부 프로세스와 플러그인 조회 작업을 함께 취소합니다. */
    private fun cancel(): Boolean {
        return when (val state = sheetState) {
            is SheetState.Loading -> {
                YoutubeDL.destroyProcessById(id = state.taskKey)
                controller.cancel(state.taskKey)
                state.job.cancel()
                mSheetStateFlow.value = SheetState.InputUrl
                return true
            }
            else -> false
        }
    }

    /** 닫은 선택 화면의 상태를 초기화합니다. */
    private fun resetSelectionState() {
        mSelectionStateFlow.update { SelectionState.Idle }
    }

    /** 호스트가 사라지면 남아 있는 HTTP 조회와 외부 프로세스를 정리합니다. */
    override fun onCleared() {
        cancel()
        super.onCleared()
    }
}
