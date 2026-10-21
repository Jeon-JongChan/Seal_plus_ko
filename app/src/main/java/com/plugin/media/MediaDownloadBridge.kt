package com.plugin.media

import com.plugin.compatibility.MediaLibrary
import com.plugin.compatibility.MediaRules

import com.plugin.compatibility.*
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.VideoQuality
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.VideoInfo
import java.io.File

/** 저장 파일 재사용은 플러그인에서 처리하고 새 다운로드·이력 저장은 원본에 위임합니다. */
fun reuseOrDownload(
    videoInfo: VideoInfo,
    downloadPreferences: DownloadPreferences,
    taskId: String,
    progressCallback: ((Float, Long, String) -> Unit)?,
    download: () -> Result<List<String>>,
    recordHistory: (VideoInfo, List<String>, Map<String, VideoQuality>) -> List<String>,
): Result<List<String>> {
        return MediaLibrary.withDownloadLock(
            MediaRules.identity(videoInfo.webpageUrl ?: videoInfo.originalUrl.orEmpty())
        ) {
            runCatching { MediaLibrary.findExisting(videoInfo, downloadPreferences) }
                .fold(
                    onSuccess = { existing ->
                        if (existing.isNotEmpty()) {
                            runCatching {
                                // 재시도 시 이미 받은 원본에는 후처리만 적용하고 다운로드를 반복하지 않습니다.
                                val sourceQualities = mutableMapOf<String, VideoQuality>()
                                val history = kotlinx.coroutines.runBlocking { com.plugin.compatibility.PluginDatabaseUtil.getDownloadHistory() }.associateBy { it.videoPath }
                                val paths = existing.flatMap { path ->
                                    val finished = if (path.startsWith("content://")) listOf(path)
                                        else MediaLibrary.finish(listOf(path),
                                            if (downloadPreferences.keepAv1Original &&
                                                existing.any { File(it).nameWithoutExtension == File(path).nameWithoutExtension + "-av1" })
                                                downloadPreferences.copyPluginOptions(transcodeAv1 = false) else downloadPreferences,
                                            taskId, progressCallback,
                                            classificationQuality = history[path]?.sourceQuality,
                                            onOutput = { output, quality ->
                                                if (quality != null) sourceQualities[File(output).name] = quality
                                            })
                                    // 원본 보존 시 원본 이력은 유지하고 대체·이동된 경로만 갱신합니다.
                                    if (path !in finished) {
                                        kotlinx.coroutines.runBlocking {
                                            com.plugin.compatibility.PluginDatabaseUtil.updateMediaPath(path, finished.last())
                                        }
                                    }
                                    finished
                                }.distinct()
                                progressCallback?.invoke(100f, 0, com.junkfood.seal.App.context.getString(R.string.loc_reuse_saved_file))
                                if (downloadPreferences.privateMode) emptyList()
                                else recordHistory(videoInfo, paths, sourceQualities)
                            }
                        } else
                            download()
                    },
                    onFailure = { Result.failure(it) },
                )
        }
}
