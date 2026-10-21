package com.plugin.runtime

import com.plugin.queue.PluginTask as Task
import com.plugin.queue.PluginTaskDownloader
import com.plugin.queue.PluginTaskBackup
import com.plugin.compatibility.requireTaskState
import com.plugin.compatibility.updateTaskState
import com.plugin.compatibility.removeTaskState
import com.plugin.compatibility.putBatch
import com.plugin.compatibility.isDuplicateOf
import com.plugin.compatibility.refreshCompletedFiles
import com.plugin.compatibility.quality
import com.plugin.compatibility.sourceQuality
import com.plugin.compatibility.withStoredPluginOptions
import com.plugin.compatibility.TemporarySession
import androidx.compose.runtime.snapshots.Snapshot

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
import com.junkfood.seal.util.FileUtil
import com.plugin.instagram.InstagramUtil
import com.plugin.compatibility.MediaLibrary
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.plugin.compatibility.InstagramMedia
import com.plugin.compatibility.QueueMedia
import com.plugin.compatibility.keepAv1Original
import com.junkfood.seal.util.ToastUtil
import com.junkfood.seal.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent

private const val TAG = "DownloaderV2"

private const val MAX_CONCURRENCY = 3

/** 전용 작업 상태·백업·단일 스케줄러를 소유하고 요청 설정에 따라 작업자를 선택합니다. */
@OptIn(FlowPreview::class)
class PluginTaskEngine(private val appContext: Context) : PluginTaskDownloader, KoinComponent {
    /** 복구 완료 전 저장소를 바꿔 일반 백업이 새 세션으로 유입되는 것을 막습니다. */
    @Volatile var restored = false
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskStateMap = mutableStateMapOf<Task, Task.State>()
    private val snapshotFlow = snapshotFlow { taskStateMap.toMap() }
    // 객체를 바꾸지 않고 저장소를 공유하므로 실행 중 스위치를 변경해도 작업과 취소가 이어집니다.
    private val original = BaselineTaskWorker(appContext, scope, taskStateMap)

    private val completedNotifications = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch(Dispatchers.Default) {
            snapshotFlow
                .onEach { doYourWork() }
                .map { it.countRunning() }
                .distinctUntilChanged()
                .collect { if (it > 0) App.startService() else App.stopService() }
        }

        scope.launch(Dispatchers.IO) {
            // 복구에 실패하면 저장 수집기를 시작하지 않아 기존 백업을 덮어쓰지 않습니다.
            if (runCatching { enqueueFromBackup() }.isFailure) {
                Log.e(TAG, "플러그인 대기열 복구 실패: 기존 백업을 보존하고 저장 수집기를 중단합니다.")
                ToastUtil.makeToastSuspend(appContext.getString(R.string.plugin_queue_restore_failed))
                return@launch
            }
            // 완료 작업을 버리던 이전 버전에서 빠진 이력만 최초 한 번 복구합니다.
            if (TemporarySession.root == null && com.plugin.PluginState.isEnabled(com.plugin.PluginId.QUEUE) && !"queue_history_imported".getBoolean()) {
                val preferences = com.plugin.compatibility.createPluginPreferences()
                val paths = taskStateMap.values.flatMap { (it.downloadState as? Completed)?.filePaths.orEmpty() }.toSet()
                com.plugin.compatibility.PluginDatabaseUtil.getDownloadHistory().filterNot { it.videoPath in paths }.forEach { info ->
                    val image = info.videoPath.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif")
                    val type = if (image) TypeInfo.SocialMedia(InstagramMedia(info.id.toString(),
                        info.videoUrl.substringBefore('#'), "", info.thumbnailUrl, true, info.videoTitle, info.videoAuthor)) else TypeInfo.URL
                    val task = Task(info.videoUrl.substringBefore('#'), type, preferences, "history:${info.id}")
                    taskStateMap[task] = Task.State(Completed(info.videoPath), null,
                        Task.ViewState(url = task.url, title = info.videoTitle, uploader = info.videoAuthor,
                            thumbnailUrl = info.thumbnailUrl, isImage = image, fileSizeApprox = (info.quality?.bytes ?: MediaLibrary.fileBytes(info.videoPath)).toDouble(),
                            width = info.quality?.width ?: 0, height = info.quality?.height ?: 0))
                }
                "queue_history_imported".updateBoolean(true)
            }
            refreshCompletedFiles()
            restored = true

            snapshotFlow { TemporarySession.root to taskStateMap.toMap() }
                .distinctUntilChanged()
                .collect { (root, tasks) ->
                    runCatching { PluginTaskBackup.save(tasks, root) }.onFailure {
                        Log.e(TAG, "플러그인 대기열 백업 저장에 실패했습니다.")
                    }
                }
        }
    }

    /** enqueueFromBackup의 원본 처리 흐름을 제공합니다. */
    private fun enqueueFromBackup() {
        val taskList =
            PluginTaskBackup.load()
                .mapValues { (_, state) ->
                    val preState = state.downloadState
                    val downloadState =
                        when (preState) {
                            is FetchingInfo,
                            Idle -> {
                                Canceled(action = FetchInfo)
                            }
                            is Running -> {
                                Canceled(action = Download, progress = preState.progress)
                            }

                            ReadyWithInfo -> {
                                Canceled(action = Download, progress = null)
                            }
                            else -> {
                                preState
                            }
                        }
                    state.copy(downloadState = downloadState)
                }
        // 백업 복구는 새 작업 추가가 아니므로 토스트를 반복 표시하지 않습니다.
        taskStateMap.putAll(taskList)
    }

    /** Map의 원본 처리 흐름을 제공합니다. */
    private fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
        state.downloadState is Running || state.downloadState is FetchingInfo
    }

    /** getTaskStateMap의 독립 플러그인 처리를 제공합니다. */
    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return taskStateMap
    }

    /** 경로와 대기열을 한 스냅샷에서 바꿔 이전 이력이 새 세션에 저장되지 않게 합니다. */
    fun switchSession(session: TemporarySession.Session?, tasks: Map<Task, Task.State>) {
        Snapshot.withMutableSnapshot {
            TemporarySession.publish(session)
            taskStateMap.clear()
            taskStateMap.putAll(tasks)
        }
        completedNotifications.clear()
        TemporarySession.notifyChange()
    }

    /** 정보 미조회 작업도 공통 추가 경로로 보내 기존 작업 추가 토스트를 표시합니다. */
    override fun enqueue(task: Task) {
        enqueue(task, Task.State(Idle, null, Task.ViewState(url = task.url, title = task.url)))
    }

    /** 정보가 있는 인스타그램·일반 작업에 동일한 추가 알림을 제공합니다. */
    @Synchronized override fun enqueue(task: Task, state: Task.State) {
        if (TemporarySession.changing || (TemporarySession.root != null && task.type is TypeInfo.CustomCommand)) {
            ToastUtil.makeToastSuspend("저장소 전환 중이거나 임시 숨기기에서 사용할 수 없는 요청입니다.")
            return
        }
        // 이미 대기·진행·완료된 동일 요청은 추가하지 않고 중복임을 알립니다.
        if (taskStateMap.toMap().isDuplicateOf(task)) {
            ToastUtil.makeToastSuspend(appContext.getString(R.string.loc_task_duplicate))
            return
        }
        completedNotifications.remove(QueueMedia.postKey(task))
        val added = !taskStateMap.containsKey(task)
        val effective = task.copy(preferences = com.plugin.PluginState.preferences(task.preferences.withStoredPluginOptions()))
        taskStateMap += effective to state
        if (added) ToastUtil.makeToastSuspend(appContext.getString(R.string.task_added))
    }

    /** 재등록한 게시물도 이번 실행이 끝날 때 한 번 알립니다. */
    @Synchronized fun enqueueBatch(tasks: Map<Task, Task.State>) {
        if (TemporarySession.changing || (TemporarySession.root != null && tasks.keys.any { it.type is TypeInfo.CustomCommand })) {
            ToastUtil.makeToastSuspend("저장소 전환 중이거나 임시 숨기기에서 사용할 수 없는 요청입니다.")
            return
        }
        // 분석 이후 스위치가 바뀌어도 새 작업에는 현재 켜진 기능만 적용합니다.
        val effective = tasks.mapKeys { (task, _) ->
            task.copy(preferences = com.plugin.PluginState.preferences(task.preferences.withStoredPluginOptions()))
        }
        effective.keys.forEach { completedNotifications.remove(QueueMedia.postKey(it)) }
        putBatch(effective)
    }

    /** 호출자가 취소한 작업을 제거하고 늦은 상태 갱신과의 충돌을 막습니다. */
    override fun remove(task: Task): Boolean {
        if (taskStateMap.removeTaskState(task) != null) {
            if (taskStateMap.keys.none { QueueMedia.postKey(it) == QueueMedia.postKey(task) })
                completedNotifications.remove(QueueMedia.postKey(task))
            return true
        }
        return false
    }

    /** 실제로 취소된 작업에만 기존 취소 토스트를 표시합니다. */
    override fun cancel(task: Task): Boolean =
        (if (owns(task)) task.cancelImpl() else original.cancel(task)).also { canceled ->
        if (canceled) ToastUtil.makeToastSuspend(appContext.getString(R.string.task_canceled))
    }

    /** restart의 독립 플러그인 처리를 제공합니다. */
    @Synchronized override fun restart(task: Task) {
        if (TemporarySession.changing) { ToastUtil.makeToastSuspend("저장소 전환이 진행 중입니다."); return }
        if (owns(task)) task.restartImpl() else original.restart(task)
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
        get() = QueueMedia.postKey(this).hashCode()


    /** 원본이 처리할 수 없는 소셜 작업과 등록 시 켜진 미디어·대기열 작업을 구분합니다. */
    internal fun owns(task: Task): Boolean = PluginExecutionPolicy.usesEnhancedEngine(task)

    /** 취소 알림은 게시물의 모든 페이지에 전달하되 원본 작업은 원본 취소로 넘깁니다. */
    override fun cancel(taskId: String): Boolean {
        val task = taskStateMap.keys.find { it.id == taskId } ?: return false
        if (!owns(task)) return original.cancel(taskId)
        return taskStateMap.keys.toList().filter { QueueMedia.postKey(it) == QueueMedia.postKey(task) }
            .map { cancel(it) }.any { it }
    }

    /** 대기 중인 작업을 기존 동시 실행 한도에서 처리합니다. */
    private fun doYourWork() {
        // 선택과 시작 사이에 전체 삭제가 끼어들어 상태가 사라지지 않게 합니다.
        synchronized(taskStateMap) {
            if (taskStateMap.countRunning() >= MAX_CONCURRENCY) return
            val next = taskStateMap.toMap().entries.sortedBy { it.value.downloadState }
                .firstOrNull { it.value.downloadState == ReadyWithInfo || it.value.downloadState == Idle }
                ?: return
            // 전역 스위치 대신 작업에 저장한 설정으로 선택해 중간에 꺼도 실행 경로를 유지합니다.
            if (!owns(next.key)) {
                original.doYourWork(next.key)
                return
            }
            when (taskStateMap[next.key]?.downloadState) {
                Idle -> next.key.prepare()
                ReadyWithInfo -> next.key.download()
                else -> Unit
            }
        }
    }

    /** Task.prepare의 원본 처리 흐름을 제공합니다. */
    private fun Task.prepare() {
        check(downloadState == Idle)
        if (type is TypeInfo.CustomCommand) {
            execute()
        } else {
            fetchInfo()
        }
    }

    /** Task.fetchInfo의 원본 처리 흐름을 제공합니다. */
    private fun Task.fetchInfo() {
        check(downloadState == Idle)
        val task = this
        val taskInfo = task.type
        val playlistIndex = if (taskInfo is TypeInfo.Playlist) taskInfo.index else null
        scope
            .launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                (if (taskInfo is TypeInfo.SocialMedia)
                        runCatching {
                            InstagramUtil.fetch(taskInfo.media.postUrl, preferences, id)
                                .first { it.id == taskInfo.media.id }
                                .copy(pageIndex = taskInfo.media.pageIndex, pageCount = taskInfo.media.pageCount)
                                .videoInfo()
                        }
                    else
                        PluginDownloadUtil.fetchVideoInfoFromUrl(
                            url = url,
                            playlistIndex = playlistIndex,
                            preferences = preferences,
                            taskKey = id,
                        ))
                    .onSuccess {
                        currentCoroutineContext().ensureActive()
                        com.plugin.compatibility.SocialPostMetadataStore.collectVideoInfo(it, url)
                        info = it
                        downloadState = ReadyWithInfo
                        viewState = Task.ViewState.fromVideoInfo(it)
                    }
                    .onFailure { throwable ->
                        if (
                            throwable is YoutubeDL.CanceledException ||
                                throwable is kotlinx.coroutines.CancellationException ||
                                downloadState is DownloadState.Canceled
                        ) {
                            return@onFailure
                        }
                        task.downloadState = Error(throwable = throwable, action = FetchInfo)
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.fetch_info_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
            }
            .also { job ->
                downloadState = FetchingInfo(job = job, taskId = id)
                job.start()
            }
    }

    /** 시작 상태를 먼저 등록하고 미디어 종류에 맞는 다운로드를 실행합니다. */
    private fun Task.download() {
        check(downloadState == ReadyWithInfo && info != null)
        if (type is TypeInfo.CustomCommand) {
            execute()
            return
        }
        scope
            .launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                val taskContext = currentCoroutineContext()
                val callback: (Float, Long, String) -> Unit = { progressPercentage, _, text ->
                    taskContext.ensureActive()
                    val previous = downloadState
                    if (previous is Running) {
                        downloadState =
                            previous.copy(
                                progress = progressPercentage / 100f,
                                progressText = text,
                            )
                        NotificationUtil.notifyProgress(
                            notificationId = notificationId,
                            progress = progressPercentage.toInt(),
                            text = text,
                            title = viewState.title,
                            taskId = id,
                        )
                    }
                }
                (if (type is TypeInfo.SocialMedia && type.media.image)
                        InstagramUtil.download(type.media, preferences, id, callback)
                    else
                        PluginDownloadUtil.downloadVideo(
                            videoInfo = info,
                            taskId = id,
                            downloadPreferences = preferences,
                            progressCallback = callback,
                        ))
                    .onSuccess { pathList ->
                        taskContext.ensureActive()
                        downloadState = Completed(pathList.firstOrNull(), pathList)
                        val saved = com.plugin.compatibility.PluginDatabaseUtil.getDownloadHistory().filter { it.videoPath in pathList }
                        val converted = saved.firstOrNull { it.sourceQuality != null }
                        val quality = (converted ?: saved.firstOrNull())?.quality ?: pathList.firstOrNull()?.let { MediaLibrary.readMediaQuality(it) }
                        val source = converted?.sourceQuality
                        viewState = viewState.copy(fileSizeApprox = pathList.sumOf { MediaLibrary.fileBytes(it) }.toDouble(),
                            width = quality?.width ?: viewState.width, height = quality?.height ?: viewState.height, duration = quality?.duration?.toInt() ?: viewState.duration,
                            averageMbps = quality?.averageMbps, originalCodec = source?.codec ?: quality?.codec.orEmpty(),
                            codec = saved.mapNotNull { info: com.junkfood.seal.database.objects.DownloadedVideoInfo -> info.quality?.codec }.distinct().joinToString(" / ").ifBlank { quality?.codec.orEmpty() },
                            av1Mode = if (source == null) 0 else if (preferences.keepAv1Original) 2 else 1)

                        // 같은 게시물의 나머지 페이지가 완료되기 전에는 완료 알림을 만들지 않습니다.
                        val postKey = QueueMedia.postKey(this@download)
                        val siblings = taskStateMap.toMap().filterKeys { QueueMedia.postKey(it) == postKey }
                        if (siblings.values.any { it.downloadState !is Completed } || !completedNotifications.add(postKey))
                            return@onSuccess

                        ToastUtil.makeToastSuspend(appContext.getString(R.string.download_success_msg))

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
                        if (
                            throwable is YoutubeDL.CanceledException ||
                                throwable is kotlinx.coroutines.CancellationException ||
                                downloadState is DownloadState.Canceled
                        ) {
                            return@onFailure
                        }
                        downloadState = Error(throwable = throwable, action = Download)
                        ToastUtil.makeToastSuspend(appContext.getString(R.string.download_error_msg))
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.download_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
            }
            .also { job ->
                downloadState = Running(job = job, taskId = id)
                job.start()
            }
    }

    /** 조회 대기·HTTP·엔진·FFmpeg를 모두 중단하고 취소 상태를 보존합니다. */
    private fun Task.cancelImpl(): Boolean {
        when (val preState = taskStateMap[this]?.downloadState ?: return false) {
            is DownloadState.Cancelable -> {
                // 외부 작업 종료보다 먼저 코루틴을 취소해 늦은 콜백을 차단합니다.
                preState.job.cancel()
                InstagramUtil.cancel(preState.taskId)
                MediaLibrary.cancel(preState.taskId)
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

    /** Task.restartImpl의 원본 처리 흐름을 제공합니다. */
    private fun Task.restartImpl() {
        completedNotifications.remove(QueueMedia.postKey(this))
        when (val preState = taskStateMap[this]?.downloadState ?: return) {
            is DownloadState.Restartable -> {
                downloadState =
                    when (preState.action) {
                        Download -> if (type is TypeInfo.SocialMedia) Idle else ReadyWithInfo
                        FetchInfo -> Idle
                    }
            }
            else -> {
                throw IllegalStateException()
            }
        }
    }

    /** 사용자 정의 명령을 확장 설정과 함께 실행합니다. */
    private fun Task.execute() {
        check(downloadState == Idle)
        check(type is TypeInfo.CustomCommand)
        val template = type.template
        scope
            .launch(start = CoroutineStart.LAZY) {
                PluginDownloadUtil.executeCustomCommandTask(url, id, template, preferences) {
                        progressPercentage,
                        _,
                        text ->
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
                        if (throwable is YoutubeDL.CanceledException) {
                            return@onFailure
                        }
                        downloadState = Error(throwable = throwable, action = Download)
                        ToastUtil.makeToastSuspend(appContext.getString(R.string.download_error_msg))
                        NotificationUtil.notifyError(
                            title = viewState.title,
                            textId = R.string.download_error_msg,
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
            .also { job ->
                downloadState = Running(job = job, taskId = id)
                job.start()
            }
    }
}
