package com.plugin.queue

import com.plugin.queue.PluginTask as Task
import com.plugin.queue.PluginTaskDownloader as DownloaderV2

import com.plugin.queue.QueueMedia

import com.junkfood.seal.util.DownloadUtil
import com.plugin.compatibility.InstagramMedia
import kotlinx.coroutines.Job
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** 게시물 묶음·혼합 종류 분리·완료 백업 호환성을 확인합니다. */
class QueueMediaTest {
    /** 원본과 AV1 복제 파일을 실제 완료 파일 수에 포함합니다. */
    @Test
    fun av1CopiesCountAsCompletedFiles() {
        val task = Task("https://www.instagram.com/p/BA/", preferences = DownloadUtil.DownloadPreferences.EMPTY)
        val state = Task.State(Task.DownloadState.Completed("/a.mp4", listOf("/a.mp4", "/a-av1.mp4")), null,
            Task.ViewState(codec = "video/av01", originalCodec = "video/avc", av1Mode = 2))
        val grouped = QueueMedia.grouped(mapOf(task to state)).values.single().viewState
        assertEquals(2, grouped.mediaCount)
        assertEquals(2, grouped.completedCount)
        assertEquals(2, grouped.videoCount)
        assertEquals("video/av01", grouped.codec)
    }
    /** 혼합 게시물도 하나의 열람·알림 식별자를 공유하고 페이지 순서와 삭제 상태를 보존합니다. */
    @Test
    fun mixedPostKeepsPageOrderAndSkipsMissingFiles() {
        val preferences = DownloadUtil.DownloadPreferences.EMPTY
        val photo = InstagramMedia("photo", "https://www.instagram.com/p/BA/", "", "", true, "post", "author")
        val image = Task(photo.postUrl, Task.TypeInfo.SocialMedia(photo), preferences)
        val video = Task(photo.postUrl, Task.TypeInfo.SocialMedia(photo.copy(id = "video", image = false, pageIndex = 1)), preferences)
        val other = Task("https://www.instagram.com/p/BB/", preferences = preferences)
        val states = linkedMapOf(video to Task.State(Task.DownloadState.Completed("/video.mp4"), null, Task.ViewState()),
            image to Task.State(Task.DownloadState.Completed("/photo.jpg"), null, Task.ViewState(isImage = true)),
            other to Task.State(Task.DownloadState.Completed("/other.mp4"), null, Task.ViewState()))
        assertEquals(QueueMedia.postKey(image), QueueMedia.postKey(video))
        assertNotEquals(QueueMedia.postKey(image), QueueMedia.postKey(other))
        assertEquals(listOf("/photo.jpg", "/video.mp4"), QueueMedia.completedPages(image, states))
        val grouped = QueueMedia.grouped(states).entries.single { it.key != other }.value
        assertEquals(2, grouped.viewState.mediaCount)
        assertEquals(1, grouped.viewState.imageCount)
        assertEquals(1, grouped.viewState.videoCount)
        assertEquals(2, grouped.viewState.completedCount)
        assertFalse(grouped.viewState.isImage)
        states[image] = states.getValue(image).copy(downloadState = Task.DownloadState.Completed("/photo.jpg", missing = true))
        assertEquals(listOf("/video.mp4"), QueueMedia.completedPages(video, states))
        assertEquals(1, QueueMedia.grouped(states).entries.single { it.key != other }.value.viewState.completedCount)
    }
    /** 스무 이미지의 작업을 보존하면서 한 카드로 표시하고 영상을 독립 처리합니다. */
    @Test
    fun twentyImagesKeepIndependentTasksAndOneCard() {
        val preferences = DownloadUtil.DownloadPreferences.EMPTY
        val states = (1..20).associate { index ->
            val media = InstagramMedia(index.toString(), "https://www.instagram.com/p/BA/",
                "https://s.cdninstagram.com/$index.jpg", "", true, "post", "test")
            Task(media.postUrl, Task.TypeInfo.SocialMedia(media), preferences) to
                Task.State(Task.DownloadState.Completed("/$index.jpg"), null,
                    Task.ViewState(isImage = true, fileSizeApprox = 1000.0))
        }
        val grouped = QueueMedia.grouped(states)
        assertEquals(1, grouped.size)
        val (task, state) = grouped.entries.single().toPair()
        assertEquals(20, state.viewState.mediaCount)
        assertEquals(20_000.0, state.viewState.fileSizeApprox, 0.0)
        assertEquals(20, (state.downloadState as Task.DownloadState.Completed).filePaths.size)
        assertEquals(20, QueueMedia.members(task, states).size)
        val running = states.toMutableMap().apply {
            this[task] = state.copy(downloadState = Task.DownloadState.Running(Job(), task.id, 0.5f))
        }
        assertEquals(0.975f, (QueueMedia.grouped(running).values.single().downloadState as Task.DownloadState.Running).progress, 0.0001f)
        val video = Task(task.url, preferences = preferences)
        assertEquals(1, QueueMedia.grouped(states + (video to Task.State(Task.DownloadState.Completed("/video.mp4"), null, Task.ViewState()))).size)
        val deleted = states.mapValues { (_, old) -> old.copy(downloadState = (old.downloadState as Task.DownloadState.Completed).copy(missing = true)) }
        assertTrue((QueueMedia.grouped(deleted).values.single().downloadState as Task.DownloadState.Completed).missing)
    }

    /** AV1 원본 보존을 포함한 여러 최종 경로와 과거 단일 경로 백업을 읽습니다. */
    @Test
    fun completedBackupKeepsEveryOutput() {
        val completed = Task.DownloadState.Completed("/av1.mp4", listOf("/source.mp4", "/av1.mp4"))
        assertEquals(completed, Json.decodeFromString<Task.DownloadState.Completed>(Json.encodeToString(completed)))
        val legacy = Json.decodeFromString<Task.DownloadState.Completed>("""{"filePath":"/old.mp4"}""")
        assertEquals(listOf("/old.mp4"), legacy.filePaths)
        assertFalse(legacy.missing)
    }
}
