package com.plugin.queue

import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.PlaylistResult
import kotlin.math.roundToInt

/** 목록 플러그인의 항목 선택 결과를 전용 대기열 요청으로 변환합니다. */
object PluginTaskFactory {
    /** 원본 작업 팩토리를 거치지 않고 선택한 재생목록 항목을 등록 가능한 상태로 만듭니다. */
    fun createWithPlaylistResult(
        playlistUrl: String,
        indexList: List<Int>,
        playlistResult: PlaylistResult,
        preferences: DownloadPreferences,
    ): Map<PluginTask, PluginTask.State> {
        val entries = checkNotNull(playlistResult.entries)
        require(indexList.all { it in 1..entries.size }) { "재생목록 항목 번호가 범위를 벗어났습니다." }
        return indexList.distinct().associate { index ->
            val entry = entries[index - 1]
            val view = PluginTask.ViewState(
                url = entry.url.orEmpty(),
                title = entry.title ?: "${playlistResult.title} - $index",
                duration = entry.duration?.roundToInt() ?: 0,
                uploader = entry.uploader ?: entry.channel ?: playlistResult.channel.orEmpty(),
                thumbnailUrl = entry.thumbnails?.lastOrNull()?.url.orEmpty(),
            )
            val task = PluginTask(playlistUrl, PluginTask.TypeInfo.Playlist(index), preferences)
            task to PluginTask.State(PluginTask.DownloadState.Idle, null, view)
        }
    }
}
