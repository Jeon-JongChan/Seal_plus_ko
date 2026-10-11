package com.plugin.queue

import com.junkfood.seal.download.Task

/** 원본 `Task` API와 플러그인 전용 큐 모델 사이에서 변환합니다. */
object PluginTaskAdapter {
    /** 원본 API 요청을 플러그인 엔진에서 사용할 모델로 복사합니다. */
    fun fromSeal(task: Task): PluginTask = PluginTask(
        url = task.url,
        type = when (val type = task.type) {
            is Task.TypeInfo.Playlist -> PluginTask.TypeInfo.Playlist(type.index)
            is Task.TypeInfo.CustomCommand -> PluginTask.TypeInfo.CustomCommand(type.template)
            Task.TypeInfo.URL -> PluginTask.TypeInfo.URL
        },
        preferences = task.preferences,
        id = task.id,
        timeCreated = task.timeCreated,
    )

    /** 원본 API 상태를 플러그인 대기열 상태로 복사합니다. */
    fun fromSeal(state: Task.State): PluginTask.State = PluginTask.State(
        downloadState = when (val value = state.downloadState) {
            Task.DownloadState.Idle -> PluginTask.DownloadState.Idle
            is Task.DownloadState.FetchingInfo -> PluginTask.DownloadState.FetchingInfo(value.job, value.taskId)
            Task.DownloadState.ReadyWithInfo -> PluginTask.DownloadState.ReadyWithInfo
            is Task.DownloadState.Running -> PluginTask.DownloadState.Running(value.job, value.taskId, value.progress, value.progressText)
            is Task.DownloadState.Canceled -> PluginTask.DownloadState.Canceled(value.action.toPlugin(), value.progress)
            is Task.DownloadState.Error -> PluginTask.DownloadState.Error(value.throwable, value.action.toPlugin())
            is Task.DownloadState.Completed -> PluginTask.DownloadState.Completed(value.filePath)
        },
        videoInfo = state.videoInfo,
        viewState = PluginTask.ViewState(
            url = state.viewState.url,
            title = state.viewState.title,
            uploader = state.viewState.uploader,
            extractorKey = state.viewState.extractorKey,
            duration = state.viewState.duration,
            fileSizeApprox = state.viewState.fileSizeApprox,
            thumbnailUrl = state.viewState.thumbnailUrl,
            videoFormats = state.viewState.videoFormats,
            audioOnlyFormats = state.viewState.audioOnlyFormats,
        ),
    )

    /** 플러그인 요청을 기존 Seal 진입점이 이해하는 기본 요청으로 변환합니다. */
    fun toSeal(task: PluginTask): Task = Task(
        url = task.url,
        type = when (val type = task.type) {
            is PluginTask.TypeInfo.Playlist -> Task.TypeInfo.Playlist(type.index)
            is PluginTask.TypeInfo.CustomCommand -> Task.TypeInfo.CustomCommand(type.template)
            is PluginTask.TypeInfo.SocialMedia, PluginTask.TypeInfo.URL -> Task.TypeInfo.URL
        },
        preferences = task.preferences,
        id = task.id,
    )

    /** 플러그인 큐 상태를 기존 Seal 진입점의 표시 가능한 기본 상태로 변환합니다. */
    fun toSeal(state: PluginTask.State): Task.State = Task.State(
        downloadState = when (val value = state.downloadState) {
            PluginTask.DownloadState.Idle -> Task.DownloadState.Idle
            is PluginTask.DownloadState.FetchingInfo -> Task.DownloadState.FetchingInfo(value.job, value.taskId)
            PluginTask.DownloadState.ReadyWithInfo -> Task.DownloadState.ReadyWithInfo
            is PluginTask.DownloadState.Running -> Task.DownloadState.Running(value.job, value.taskId, value.progress, value.progressText)
            is PluginTask.DownloadState.Canceled -> Task.DownloadState.Canceled(value.action.toSeal(), value.progress)
            is PluginTask.DownloadState.Error -> Task.DownloadState.Error(value.throwable, value.action.toSeal())
            is PluginTask.DownloadState.Completed -> Task.DownloadState.Completed(value.filePath)
        },
        videoInfo = state.videoInfo,
        viewState = Task.ViewState(
            url = state.viewState.url,
            title = state.viewState.title,
            uploader = state.viewState.uploader,
            extractorKey = state.viewState.extractorKey,
            duration = state.viewState.duration,
            fileSizeApprox = state.viewState.fileSizeApprox,
            thumbnailUrl = state.viewState.thumbnailUrl,
            videoFormats = state.viewState.videoFormats,
            audioOnlyFormats = state.viewState.audioOnlyFormats,
        ),
    )

    /** 재시작 동작을 플러그인 상태 모델로 복사합니다. */
    private fun Task.RestartableAction.toPlugin(): PluginTask.RestartableAction = when (this) {
        Task.RestartableAction.FetchInfo -> PluginTask.RestartableAction.FetchInfo
        Task.RestartableAction.Download -> PluginTask.RestartableAction.Download
    }

    /** 재시작 동작을 원본 상태 모델로 복사합니다. */
    private fun PluginTask.RestartableAction.toSeal(): Task.RestartableAction = when (this) {
        PluginTask.RestartableAction.FetchInfo -> Task.RestartableAction.FetchInfo
        PluginTask.RestartableAction.Download -> Task.RestartableAction.Download
    }
}
