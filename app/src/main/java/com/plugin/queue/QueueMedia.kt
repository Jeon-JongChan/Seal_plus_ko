package com.plugin.queue

import com.plugin.queue.PluginTask as Task
import com.plugin.compatibility.*
import com.plugin.compatibility.MediaRules

/** 다운로드는 파일별로 유지하고 이미지 게시물의 표시와 조작만 묶습니다. */
object QueueMedia {
    /** 게시물 단위 알림과 열람에 사용할 식별자입니다. */
    fun postKey(task: Task): String {
        val url = (task.type as? Task.TypeInfo.SocialMedia)?.media?.postUrl
            ?: task.url.takeIf { !task.preferences.extractAudio && MediaRules.usesSocialMediaApi(it, false) &&
                MediaRules.instagramAccount(it) == null } ?: return task.id
        // 이전 버전에서 URL 작업으로 복구한 영상도 같은 게시물에 표시합니다.
        return "post:${MediaRules.identity(url.substringBefore('#'))}"
    }

    /** 게시물 페이지 순서대로 완료된 파일을 모읍니다. */
    fun completedPages(task: Task, states: Map<Task, Task.State>): List<String> = states.entries
        .filter { postKey(it.key) == postKey(task) }
        .sortedWith(compareBy({ (it.key.type as? Task.TypeInfo.SocialMedia)?.media?.pageIndex ?: 0 }, { it.key.timeCreated }))
        .flatMap { (it.value.downloadState as? Task.DownloadState.Completed)?.takeUnless { state -> state.missing }?.filePaths.orEmpty() }
        .distinct()
    /** 이미지·영상의 종류와 무관하게 같은 게시물과 설정을 한 카드로 묶습니다. */
    fun key(task: Task): String = postKey(task)

    /** 묶음의 실제 작업들을 찾아 취소·재개·정리를 모두 적용합니다. */
    fun members(task: Task, states: Map<Task, Task.State>): List<Task> = states.keys.filter { key(it) == key(task) }

    /** 같은 페이지의 재시도는 마지막 상태만 사용합니다. */
    private fun latest(entries: List<Map.Entry<Task, Task.State>>) = entries
        .groupBy { (it.key.type as? Task.TypeInfo.SocialMedia)?.media?.id ?: it.key.id }
        .values.map { page -> page.maxBy { it.key.timeCreated } }

    /** 다운로드 전·실패·선택 제외 페이지는 삭제 수에 포함하지 않습니다. */
    fun missingPages(task: Task, states: Map<Task, Task.State>): Int = latest(
        states.entries.filter { key(it.key) == key(task) }
    ).sumOf { entry ->
        val completed = entry.value.downloadState as? Task.DownloadState.Completed
        if (completed == null) 0 else {
            val count = if (entry.key.type is Task.TypeInfo.SocialMedia) 1
                else maxOf(entry.value.viewState.mediaCount, completed.filePaths.distinct().size, 1)
            if (completed.missing) count else (count - completed.filePaths.distinct().size).coerceAtLeast(0)
        }
    }

    /** 같은 공유 링크의 이미지 수와 전체 진행 상태를 하나의 카드로 계산합니다. */
    fun grouped(states: Map<Task, Task.State>): Map<Task, Task.State> =
        states.entries.groupBy { key(it.key) }.values.associate { attempts ->
            // 같은 페이지를 다른 설정으로 다시 받은 경우 마지막 시도를 표시하되 원본 작업은 보존합니다.
            val entries = latest(attempts)
            val first = entries.minBy { it.key.timeCreated }
            run {
                val ordered = entries.map { it.value.downloadState }.sorted()
                val state = when (val active = ordered.first()) {
                    is Task.DownloadState.Completed -> {
                        val completed = ordered.filterIsInstance<Task.DownloadState.Completed>()
                        val paths = completed.filterNot { it.missing }.flatMap { it.filePaths }.distinct()
                        Task.DownloadState.Completed(completed.firstOrNull { !it.missing }?.filePath ?: paths.firstOrNull(),
                            paths, completed.all { it.missing })
                    }
                    is Task.DownloadState.Running -> active.copy(progress = ordered.sumOf {
                        when (it) {
                            is Task.DownloadState.Completed -> 1.0
                            is Task.DownloadState.Running -> it.progress.coerceAtLeast(0f).toDouble()
                            else -> 0.0
                        }
                    }.toFloat() / entries.size)
                    else -> active
                }
                val views = entries.map { it.value.viewState }
                val sizes = views.filter { it.width > 0 && it.height > 0 }.sortedBy { it.width.toLong() * it.height }
                // 게시물 페이지의 원본·AV1 복제는 같은 페이지이며 일반 URL의 출력 파일 수와 구분합니다.
                val counts = entries.map { if (it.key.type is Task.TypeInfo.SocialMedia) 1 else maxOf(1, it.value.viewState.mediaCount,
                    (it.value.downloadState as? Task.DownloadState.Completed)?.filePaths?.distinct()?.size ?: 0) }
                // 게시물 메타데이터의 전체 수를 사용해 선택 제외나 파일 삭제로 분모가 줄지 않게 합니다.
                val total = maxOf(counts.sum(), attempts.maxOf { entry -> maxOf(
                    (entry.key.type as? Task.TypeInfo.SocialMedia)?.media?.pageCount ?: 0,
                    entry.value.videoInfo?.postPageCount ?: 0) })
                val images = entries.mapIndexed { index, entry ->
                    if ((entry.key.type as? Task.TypeInfo.SocialMedia)?.media?.image ?: entry.value.viewState.isImage) counts[index] else 0
                }.sum()
                first.key to first.value.copy(downloadState = state,
                    viewState = first.value.viewState.copy(mediaCount = total,
                        isImage = images == counts.sum(), imageCount = images, videoCount = counts.sum() - images,
                        completedCount = entries.sumOf { entry ->
                            val paths = (entry.value.downloadState as? Task.DownloadState.Completed)?.takeUnless { it.missing }
                                ?.filePaths?.distinct().orEmpty()
                            if (entry.key.type is Task.TypeInfo.SocialMedia) if (paths.isEmpty()) 0 else 1 else paths.size },
                        width = sizes.lastOrNull()?.width ?: 0, height = sizes.lastOrNull()?.height ?: 0,
                        minWidth = sizes.firstOrNull()?.width ?: 0, minHeight = sizes.firstOrNull()?.height ?: 0,
                        duration = views.filterNot { it.isImage }.maxOfOrNull { it.duration } ?: 0,
                        averageMbps = views.mapNotNull { it.averageMbps }.takeIf { it.isNotEmpty() }?.average(),
                        originalCodec = views.filterNot { it.isImage }.map { it.originalCodec }.filter { it.isNotBlank() }.distinct().joinToString(" / "),
                        codec = views.map { it.codec }.filter { it.isNotBlank() }.distinct().joinToString(" / "),
                        av1Mode = views.maxOf { it.av1Mode },
                        fileSizeApprox = entries.sumOf { it.value.viewState.fileSizeApprox }))
            }
        }
}
