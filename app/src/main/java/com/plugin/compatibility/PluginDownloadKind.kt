package com.plugin.compatibility

import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences

/** 원본 enum을 바꾸지 않고 플러그인 입력창의 다섯 가지 다운로드 유형을 표현합니다. */
enum class PluginDownloadKind {
    Auto, Video, Image, Playlist, Audio;

    /** 기존 형식 편집기를 사용할 때 필요한 원본 유형으로 변환합니다. */
    val formatType: DownloadType get() = when (this) {
        Audio -> DownloadType.Audio
        Playlist -> DownloadType.Playlist
        else -> DownloadType.Video
    }

    /** 현재 요청의 옵션을 보존하면서 유형과 목록 처리 여부를 적용합니다. */
    fun preferences(value: DownloadPreferences, playlistUrl: Boolean = false): DownloadPreferences =
        value.copyPreservingPluginOptions {
            copy(extractAudio = this@PluginDownloadKind == Audio,
                downloadPlaylist = this@PluginDownloadKind == Playlist || (this@PluginDownloadKind == Auto && playlistUrl))
        }.copyPluginOptions(mediaSelection = when (this) {
            Auto, Playlist -> 0
            Image -> 2
            Video, Audio -> 1
        })
}
