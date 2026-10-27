package com.plugin.compatibility

import kotlinx.coroutines.ensureActive
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.download.DownloaderV2
import com.plugin.queue.PluginTask as Task
import com.plugin.queue.PluginTask.DownloadState
import com.plugin.queue.PluginTask.DownloadState.Completed
import com.junkfood.seal.util.ToastUtil
import com.plugin.compatibility.MediaRules
import com.plugin.compatibility.MediaLibrary
import com.plugin.runtime.PluginTaskEngine
import com.plugin.runtime.pluginQueue
import com.plugin.queue.PluginTaskDownloader
import com.plugin.compatibility.updateTaskState
import com.plugin.compatibility.QueueMedia

/** 묶음 등록을 실제 엔진 또는 검사 구현에 전달합니다. */
fun DownloaderV2.enqueueBatch(tasks: Map<Task, Task.State>) {
    pluginQueue().enqueueBatch(tasks)
}

/** 전용 대기열의 엔진 또는 미리보기에 묶음 요청을 전달합니다. */
fun PluginTaskDownloader.enqueueBatch(tasks: Map<Task, Task.State>) {
    if (this is PluginTaskEngine) enqueueBatch(tasks) else putBatch(tasks)
}

/**
 * 같은 ID의 요청이 대기·조회·진행·정상 완료 상태이면 중복으로 봅니다.
 * 실패·취소·삭제된 완료 작업은 다시 추가해 교체할 수 있습니다.
 */
internal fun Map<Task, Task.State>.isDuplicateOf(task: Task): Boolean = any { (old, state) ->
    old.id == task.id && when (val s = state.downloadState) {
        is DownloadState.Error, is DownloadState.Canceled -> false
        is Completed -> !s.missing
        else -> true
    }
}

/** 한 게시물의 모든 페이지를 함께 등록하고 추가 토스트는 게시물당 한 번 표시합니다. */
internal fun PluginTaskDownloader.putBatch(tasks: Map<Task, Task.State>) {
        val states = getTaskStateMap()
        val snapshot = states.toMap()
        // 갱신된 CDN 주소가 달라도 같은 작업 ID는 교체하고 이미 실행 중인 작업은 유지합니다.
        val pending = tasks.filterKeys { task -> !snapshot.isDuplicateOf(task) }
        if (pending.isEmpty() && tasks.isNotEmpty()) {
            ToastUtil.makeToastSuspend(App.context.getString(R.string.loc_task_duplicate))
            return
        }
        val ids = pending.keys.map { it.id }.toSet()
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
            states.keys.filter { it.id in ids }.forEach { states.remove(it) }
            states.putAll(pending)
        }
        pending.keys.distinctBy { QueueMedia.postKey(it) }.forEach {
            ToastUtil.makeToastSuspend(App.context.getString(R.string.task_added))
        }
    }

    /** 삭제된 페이지의 완료 작업을 남겨 게시물 전체 수와 삭제 상태를 보존합니다. */
    fun PluginTaskDownloader.removeCompletedFile(path: String) {
        getTaskStateMap().toMap().forEach { (task, state) ->
            val updated = state.withoutCompletedFile(path)
            if (updated != state && getTaskStateMap()[task] == state) {
                // 일반 단일 파일은 기존 제거 동작을 유지하고 게시물만 삭제된 페이지를 보관합니다.
                if ((updated.downloadState as Completed).missing && QueueMedia.postKey(task) == task.id) remove(task)
                else getTaskStateMap().updateTaskState(task) { if (it == state) updated else it }
            }
        }
    }

/** 마지막 파일도 경로와 작업을 남기고, 복제 파일 중 일부만 삭제되면 기존 파일 수를 보존합니다. */
fun Task.State.withoutCompletedFile(path: String): Task.State {
    val completed = downloadState as? Completed ?: return this
    if (completed.filePaths.none { MediaRules.sameFilePath(it, path) }) return this
    val remaining = completed.filePaths.filterNot { MediaRules.sameFilePath(it, path) }
    return copy(
        downloadState = if (remaining.isEmpty()) completed.copy(missing = true)
            else Completed(remaining.first(), remaining),
        // 원본 페이지 삭제는 전체 수를 보존하지만 AI 복제는 원본 페이지 수에 포함하지 않아 삭제하면 개수도 줄어야 합니다.
        viewState = viewState.copy(mediaCount = maxOf(viewState.mediaCount,
            completed.filePaths.distinct().count { !PluginMediaMetadataStore.isAiDuplicate(it) })),
    )
}

    /** 지정한 게시물만 복구하며 명시적 전체 호출 외에는 화면 밖 파일을 열지 않습니다. */
    suspend fun PluginTaskDownloader.refreshCompletedFiles(post: String? = null) {
        val tasks = getTaskStateMap().toMap().filter { (task,state) ->
            state.downloadState is Completed && (post == null || QueueMedia.postKey(task) == post)
        }
        if (tasks.isEmpty()) return
        tasks.forEach { (task, state) ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val completed = state.downloadState as? Completed ?: return@forEach
            if (completed.filePaths.isEmpty()) return@forEach
            val resolved = completed.filePaths.map { old ->
                val current = MediaLibrary.resolveDownloadedPath(old, task.preferences)
                if (current != null && current != old) PluginDatabaseUtil.updateMediaPath(old, current)
                current
            }
            val paths = completed.filePaths.mapIndexed { index, old -> resolved[index] ?: old }
            // 외부에서 이동된 파일도 복구 후의 경로로 저장된 품질·페이지 정보를 찾습니다.
            val history = PluginDatabaseUtil.getDownloadHistory(completed.filePaths+paths).associateBy { it.videoPath }
            val updated = Completed(resolved.firstOrNull { it != null } ?: paths.first(), paths, resolved.all { it == null })
            var viewState = state.viewState
            val records = (completed.filePaths+paths).distinct().mapNotNull { history[it] }
            // 과거 이력에서 URL 작업으로 복구하며 빠뜨린 게시물 전체 수도 기존 데이터로 보완합니다.
            val pageCount = maxOf(viewState.postPageCount,state.videoInfo?.postPageCount ?: 0, records.maxOfOrNull { it.postPageCount } ?: 0)
            val videoInfo = if (pageCount > (state.videoInfo?.postPageCount ?: 0)) {
                (state.videoInfo ?: com.junkfood.seal.util.VideoInfo()).apply { this.postPageCount = pageCount }
            } else state.videoInfo
            // 확장 메타데이터만 바꾸면 동등성이 같아 UI에 알리지 못하므로 전체 수를 불변 상태에도 보관합니다.
            viewState=viewState.copy(postPageCount=pageCount)
            if ((viewState.codec.isBlank() || (!viewState.isImage && viewState.duration <= 0)) && !updated.missing) {
                val saved = records.firstOrNull { it.sourceQuality != null } ?: history[completed.filePath] ?: history[updated.filePath]
                val quality = saved?.quality ?: updated.filePath?.let { MediaLibrary.readMediaQuality(it) }
                if (saved != null && saved.quality == null && quality != null) {
                    val newPath = updated.filePath ?: saved.videoPath
                    PluginMediaMetadataStore.save(newPath, quality = quality)
                    PluginDatabaseUtil.insertInfoImmediately(saved.copy(videoPath = newPath))
                }
                if (quality != null) viewState = viewState.copy(width = quality.width, height = quality.height, duration = quality.duration.toInt(),
                    averageMbps = quality.averageMbps, originalCodec = saved?.sourceQuality?.codec ?: quality.codec,
                    codec = records.mapNotNull { it.quality?.codec }.distinct().joinToString(" / ").ifBlank { quality.codec },
                    av1Mode = if (saved?.sourceQuality == null) 0 else if (task.preferences.keepAv1Original) 2 else 1)
            }
            if (getTaskStateMap()[task]?.downloadState == completed &&
                (updated != completed || viewState != state.viewState || videoInfo != state.videoInfo))
                getTaskStateMap().updateTaskState(task) {
                    if (it.downloadState == completed) it.copy(downloadState = updated, viewState = viewState, videoInfo = videoInfo) else it
                }
        }
    }

/** 기존 파일 삭제 진입점도 표시용 복사본 대신 실제 전용 상태를 갱신합니다. */
fun DownloaderV2.removeCompletedFile(path: String) = pluginQueue().removeCompletedFile(path)

