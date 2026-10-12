package com.plugin.runtime

import com.plugin.compatibility.copyPreservingPluginOptions
import com.plugin.compatibility.useWebsiteFolder
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.plugin.queue.PluginTask.DownloadState
import com.plugin.queue.PluginTask.DownloadState.Canceled
import com.plugin.queue.PluginTask.DownloadState.Completed
import com.plugin.queue.PluginTask.DownloadState.Error
import com.plugin.queue.PluginTask.DownloadState.FetchingInfo
import com.plugin.queue.PluginTask.DownloadState.Idle
import com.plugin.queue.PluginTask.DownloadState.ReadyWithInfo
import com.plugin.queue.PluginTask.DownloadState.Running
import com.plugin.queue.PluginTask.RestartableAction.Download
import com.plugin.queue.PluginTask.RestartableAction.FetchInfo
import com.plugin.queue.PluginTask.TypeInfo
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import com.plugin.queue.PluginTask as Task
import com.plugin.compatibility.requireTaskState
import com.plugin.compatibility.updateTaskState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val TAG = "DownloaderV2"

private const val MAX_CONCURRENCY = 3

/** 기준 커밋의 처리 흐름을 전용 작업 모델에서 실행하는 기본 작업자입니다. */
internal class BaselineTaskWorker(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val taskStateMap: SnapshotStateMap<Task, Task.State>,
) {
    /** 기본 작업을 기준 커밋의 취소 흐름으로 중단합니다. */
    fun cancel(task: Task): Boolean = task.cancelImpl()

    /** 알림의 기본 작업 식별자를 실제 작업으로 변환합니다. */
    fun cancel(taskId: String): Boolean =
        taskStateMap.keys.find { it.id == taskId }?.let { cancel(it) } ?: false

    /** 기본 작업을 기준 커밋의 재시작 흐름으로 되돌립니다. */
    fun restart(task: Task) = task.restartImpl()

    /** 실행 중 작업 수를 전체 전용 대기열에서 계산합니다. */
    private fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
        state.downloadState is Running || state.downloadState is FetchingInfo
    }

    private val Task.state: Task.State
        get() = taskStateMap.requireTaskState(this)

    private var Task.downloadState: DownloadState
        get() = state.downloadState
        set(value) {
            taskStateMap.updateTaskState(this) { it.copy(downloadState = value) }
        }

    private var Task.info: VideoInfo?
        get() = state.videoInfo
        set(value) {
            taskStateMap.updateTaskState(this) { it.copy(videoInfo = value) }
        }

    private var Task.viewState: Task.ViewState
        get() = state.viewState
        set(value) {
            taskStateMap.updateTaskState(this) { it.copy(viewState = value) }
        }

    private val Task.notificationId: Int
        get() = id.hashCode()

    /** 전체 동시 실행 한도 안에서 다운로드 대기 작업을 우선 처리합니다. */
    internal fun doYourWork(task: Task) {
        if (taskStateMap.countRunning() >= MAX_CONCURRENCY) return
        // 스케줄러가 선택한 기본 작업만 처리해 상태 갱신 중 다른 플러그인 작업을 가져오지 않습니다.
        when (taskStateMap[task]?.downloadState) {
            Idle -> task.prepare()
            ReadyWithInfo -> task.download()
            else -> Unit
        }
    }

    /** prepare의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
    private fun Task.prepare() {
        check(downloadState == Idle)
        if (type is TypeInfo.CustomCommand) {
            execute()
        } else {
            fetchInfo()
        }
    }

    /** fetchInfo의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
    private fun Task.fetchInfo() {
        check(downloadState == Idle)
        val task = this
        val taskInfo = task.type
        val playlistIndex = if (taskInfo is TypeInfo.Playlist) taskInfo.index else null
        scope
            .launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        playlistIndex = playlistIndex,
                        preferences = preferences,
                        taskKey = id,
                    )
                    .onSuccess {
                        currentCoroutineContext().ensureActive()
                        com.plugin.compatibility.SocialPostMetadataStore.collectVideoInfo(it, url)
                        info = it
                        downloadState = ReadyWithInfo
                        viewState = Task.ViewState.fromVideoInfo(it)
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException || throwable is kotlinx.coroutines.CancellationException) {
                            return@onFailure
                        }
                        task.downloadState = Error(throwable = throwable, action = FetchInfo)
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.download_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
            }
            .also { job -> downloadState = FetchingInfo(job = job, taskId = id); job.start() }
    }

    /** download의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
    private fun Task.download() {
        check(downloadState == ReadyWithInfo && info != null)
        if (type is TypeInfo.CustomCommand) {
            execute()
            return
        }
        scope
            .launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                val taskContext = currentCoroutineContext()
                DownloadUtil.downloadVideo(
                        videoInfo = info,
                        taskId = id,
                        downloadPreferences = preferences.copyPreservingPluginOptions {
                            copy(subdirectoryExtractor = preferences.useWebsiteFolder(image = false))
                        },
                        progressCallback = { progressPercentage, _, text ->
                            taskContext.ensureActive()
                            val progress = progressPercentage / 100f
                            when (val preState = downloadState) {
                                is Running -> {
                                    downloadState =
                                        preState.copy(progress = progress, progressText = text)
                                    NotificationUtil.notifyProgress(
                                        notificationId = notificationId,
                                        progress = progressPercentage.toInt(),
                                        text = text,
                                        title = viewState.title,
                                        taskId = id,
                                    )
                                }
                                else -> {}
                            }
                        },
                    )
                    .onSuccess { pathList ->
                        currentCoroutineContext().ensureActive()
                        downloadState = Completed(pathList.firstOrNull())

                        val text =
                            appContext.getString(
                                if (pathList.isEmpty()) R.string.status_completed
                                else R.string.download_finish_notification
                            )
                        FileUtil.createIntentForOpeningFile(pathList.firstOrNull()).run {
                            NotificationUtil.finishNotification(
                                notificationId,
                                title = viewState.title,
                                text = text,
                                intent =
                                    if (this != null)
                                        PendingIntent.getActivity(
                                            appContext,
                                            0,
                                            this,
                                            PendingIntent.FLAG_IMMUTABLE,
                                        )
                                    else null,
                            )
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException || throwable is kotlinx.coroutines.CancellationException) {
                            return@onFailure
                        }
                        downloadState = Error(throwable = throwable, action = Download)
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.fetch_info_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
            }
            .also { job -> downloadState = Running(job = job, taskId = id); job.start() }
    }

    /** cancelImpl의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
    private fun Task.cancelImpl(): Boolean {
        when (val preState = taskStateMap[this]?.downloadState ?: return false) {
            is DownloadState.Cancelable -> {
                // 외부 프로세스 시작 전이어도 조회·다운로드 코루틴은 반드시 취소합니다.
                preState.job.cancel()
                YoutubeDL.destroyProcessById(preState.taskId)
                val progress = if (preState is Running) preState.progress else null
                NotificationUtil.cancelNotification(notificationId)
                downloadState = DownloadState.Canceled(action = preState.action, progress = progress)
                return true
            }
            Idle -> {
                downloadState = DownloadState.Canceled(action = FetchInfo)
            }
            ReadyWithInfo -> {
                downloadState = DownloadState.Canceled(action = Download)
            }

            else -> {
                return false
            }
        }
        return true
    }

    /** restartImpl의 전용 대기열 처리 또는 화면 동작을 제공합니다. */
    private fun Task.restartImpl() {
        when (val preState = taskStateMap[this]?.downloadState ?: return) {
            is DownloadState.Restartable -> {
                downloadState =
                    when (preState.action) {
                        Download -> ReadyWithInfo
                        FetchInfo -> Idle
                    }
            }
            else -> {
                throw IllegalStateException()
            }
        }
    }

    /** 기준 커밋의 사용자 명령 실행 흐름을 제공합니다. */
    private fun Task.execute() {
        check(downloadState == Idle)
        check(type is TypeInfo.CustomCommand)
        val template = type.template
        scope
            .launch(start = CoroutineStart.LAZY) {
                val taskContext = currentCoroutineContext()
                DownloadUtil.executeCustomCommandTask(url, id, template, preferences) {
                        progressPercentage,
                        _,
                        text ->
                        taskContext.ensureActive()
                        val progress = progressPercentage / 100f
                        when (val preState = downloadState) {
                            is Running -> {
                                downloadState =
                                    preState.copy(progress = progress, progressText = text)
                                NotificationUtil.makeNotificationForCustomCommand(
                                    notificationId = notificationId,
                                    taskId = id,
                                    progress = progressPercentage.toInt(),
                                    templateName = template.name,
                                    taskUrl = url,
                                    text = text,
                                )
                            }
                            else -> {}
                        }
                    }
                    .onFailure { throwable ->
                        if (throwable is YoutubeDL.CanceledException || throwable is kotlinx.coroutines.CancellationException) {
                            return@onFailure
                        }
                        downloadState = Error(throwable = throwable, action = Download)
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.fetch_info_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
                    .onSuccess {
                        currentCoroutineContext().ensureActive()
                        downloadState = Completed(null)

                        val text = appContext.getString(R.string.status_completed)

                        NotificationUtil.finishNotification(
                            notificationId = notificationId,
                            title = viewState.title,
                            text = text,
                            intent = null,
                        )
                    }
            }
            .also { downloadState = Running(job = it, taskId = id); it.start() }
    }
}
