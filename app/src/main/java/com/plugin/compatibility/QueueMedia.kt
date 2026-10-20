package com.plugin.compatibility

import com.plugin.queue.PluginTask as Task

/** 다운로드 작업은 유지하고 게시물 단위 화면 상태만 계산합니다. */
object QueueMedia {
    /** 한 번의 계산으로 카드 상태·삭제 수·재시도 표시를 함께 전달하는 불변 UI 자료입니다. */
    data class Snapshot(
        val states: Map<Task, Task.State> = emptyMap(),
        val missingPages: Map<Task, Int> = emptyMap(),
        val retryable: Set<Task> = emptySet(),
    )

    /** 게시물 단위 알림과 열람에 사용할 식별자입니다. */
    fun postKey(task: Task): String {
        val url = (task.type as? Task.TypeInfo.SocialMedia)?.media?.postUrl
            ?: task.url.takeIf { !task.preferences.extractAudio && MediaRules.usesSocialMediaApi(it, false) &&
                MediaRules.instagramAccount(it) == null } ?: return task.id
        return "post:${MediaRules.identity(url.substringBefore('#'))}"
    }

    /** 게시물 페이지 순서대로 완료된 파일을 모읍니다. */
    fun completedPages(task: Task, states: Map<Task, Task.State>): List<String> {
        val post = key(task)
        return states.entries.filter { key(it.key) == post }
            .sortedWith(compareBy({ (it.key.type as? Task.TypeInfo.SocialMedia)?.media?.pageIndex ?: 0 }, { it.key.timeCreated }))
            .flatMap { (it.value.downloadState as? Task.DownloadState.Completed)?.takeUnless { state -> state.missing }?.filePaths.orEmpty() }
            .distinct()
    }

    /** 이미지·영상의 종류와 무관하게 같은 게시물과 설정을 한 카드로 묶습니다. */
    fun key(task: Task): String = postKey(task)

    /** 묶음의 실제 작업들을 찾아 취소·재개·정리를 모두 적용합니다. */
    fun members(task: Task, states: Map<Task, Task.State>): List<Task> {
        val post = key(task)
        return states.keys.filter { key(it) == post }
    }

    /** 페이지별 마지막 시도를 한 번의 순회로 고르며 동일 시각이면 기존 순서를 유지합니다. */
    private fun latest(entries: List<Map.Entry<Task, Task.State>>): List<Map.Entry<Task, Task.State>> {
        val pages = LinkedHashMap<String, Map.Entry<Task, Task.State>>()
        for (entry in entries) {
            val id = (entry.key.type as? Task.TypeInfo.SocialMedia)?.media?.id ?: entry.key.id
            val previous = pages[id]
            if (previous == null || previous.key.timeCreated < entry.key.timeCreated) pages[id] = entry
        }
        return pages.values.toList()
    }

    /** 게시물별 집계를 한 번만 수행하며 취소된 이전 계산은 UI에 전달하지 않습니다. */
    fun snapshot(states: Map<Task, Task.State>, checkCancelled: () -> Unit = {}): Snapshot {
        val posts = states.entries.groupBy { checkCancelled(); key(it.key) }
        val cards = LinkedHashMap<Task, Task.State>()
        val missingPages = LinkedHashMap<Task, Int>()
        val retryable = LinkedHashSet<Task>()
        for (attempts in posts.values) {
            checkCancelled()
            val entries = latest(attempts)
            val first = entries.minBy { it.key.timeCreated }
            val downloads = entries.map { it.value.downloadState }
            // 같은 파일의 메타데이터는 집계 한 번에 한 번만 조회합니다.
            val duplicatePaths = downloads.filterIsInstance<Task.DownloadState.Completed>()
                .flatMap { it.filePaths }.distinct().filter { PluginMediaMetadataStore.isAiDuplicate(it) }.toSet()
            val paths = downloads.map { (it as? Task.DownloadState.Completed)?.takeUnless { it.missing }?.filePaths.orEmpty().distinct() }
            val duplicates = paths.flatten().toSet().count { it in duplicatePaths }
            val counts = entries.mapIndexed { index, entry ->
                if (entry.key.type is Task.TypeInfo.SocialMedia) 1
                else maxOf(1, entry.value.viewState.mediaCount, paths[index].count { it !in duplicatePaths })
            }
            val originalCount = counts.sum()
            val active = downloads.minOrNull()!!
            val state = when (active) {
                is Task.DownloadState.Completed -> {
                    val completed = downloads.filterIsInstance<Task.DownloadState.Completed>()
                    val files = paths.flatten().distinct()
                    Task.DownloadState.Completed(completed.firstOrNull { !it.missing }?.filePath ?: files.firstOrNull(),
                        files, completed.all { it.missing })
                }
                is Task.DownloadState.Running -> active.copy(progress = downloads.sumOf {
                    when (it) {
                        is Task.DownloadState.Completed -> 1.0
                        is Task.DownloadState.Running -> (it.progress.takeIf { progress -> progress.isFinite() } ?: 0f).coerceIn(0f, 1f).toDouble()
                        else -> 0.0
                    }
                }.toFloat() / entries.size)
                else -> active
            }
            val views = entries.map { it.value.viewState }
            val sizes = views.filter { it.width > 0 && it.height > 0 }
            val smallest = sizes.minByOrNull { it.width.toLong() * it.height }
            val largest = sizes.asReversed().maxByOrNull { it.width.toLong() * it.height }
            val total = maxOf(originalCount, attempts.maxOf { entry -> maxOf(
                (entry.key.type as? Task.TypeInfo.SocialMedia)?.media?.pageCount ?: 0,
                entry.value.videoInfo?.postPageCount ?: 0) }) + duplicates
            val images = entries.indices.sumOf { index ->
                val entry = entries[index]
                if ((entry.key.type as? Task.TypeInfo.SocialMedia)?.media?.image ?: entry.value.viewState.isImage)
                    counts[index] + paths[index].count { it in duplicatePaths } else 0
            }
            cards[first.key] = first.value.copy(downloadState = state,
                viewState = first.value.viewState.copy(mediaCount = total,
                    isImage = images == originalCount + duplicates, imageCount = images, videoCount = originalCount + duplicates - images,
                    completedCount = entries.indices.sumOf { index ->
                        if (entries[index].key.type is Task.TypeInfo.SocialMedia)
                            (if (paths[index].any { it !in duplicatePaths }) 1 else 0) + paths[index].count { it in duplicatePaths }
                        else paths[index].size
                    },
                    width = largest?.width ?: 0, height = largest?.height ?: 0,
                    minWidth = smallest?.width ?: 0, minHeight = smallest?.height ?: 0,
                    duration = views.filterNot { it.isImage }.maxOfOrNull { it.duration } ?: 0,
                    averageMbps = views.mapNotNull { it.averageMbps }.takeIf { it.isNotEmpty() }?.average(),
                    originalCodec = views.filterNot { it.isImage }.map { it.originalCodec }.filter { it.isNotBlank() }.distinct().joinToString(" / "),
                    codec = views.map { it.codec }.filter { it.isNotBlank() }.distinct().joinToString(" / "),
                    av1Mode = views.maxOf { it.av1Mode },
                    fileSizeApprox = views.sumOf { it.fileSizeApprox }))
            missingPages[first.key] = entries.indices.sumOf { index ->
                val completed = downloads[index] as? Task.DownloadState.Completed
                if (completed == null) 0 else {
                    // AI 복제만 남았더라도 삭제한 원본 페이지는 삭제 수에 포함합니다.
                    val originalPaths = completed.filePaths.distinct().count { it !in duplicatePaths }
                    val expected = if (entries[index].key.type is Task.TypeInfo.SocialMedia) 1
                        else maxOf(views[index].mediaCount, originalPaths, 1)
                    if (completed.missing) expected else (expected - originalPaths).coerceAtLeast(0)
                }
            }
            if (attempts.any { it.value.downloadState is Task.DownloadState.Restartable }) retryable.add(first.key)
        }
        return Snapshot(cards, missingPages, retryable)
    }
}
