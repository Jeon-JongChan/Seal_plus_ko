package com.plugin.compatibility

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import com.junkfood.seal.util.DownloadType

/**
 * 플러그인 전용 다운로드 유형 확장입니다.
 * 원본 PreferenceUtil.DownloadType(Video, Audio, Playlist, Command)을 훼손하지 않고
 * Auto 및 Image 유형을 지원합니다.
 */
enum class PluginDownloadType {
    Video,
    Audio,
    Playlist,
    Command,
    Auto,
    Image;

    /** 원본 DownloadType으로 변환합니다 (Auto/Image는 Video로 매핑). */
    fun toNative(): DownloadType = when (this) {
        Video, Auto, Image -> DownloadType.Video
        Audio -> DownloadType.Audio
        Playlist -> DownloadType.Playlist
        Command -> DownloadType.Command
    }

    companion object {
        /** 원본 DownloadType에서 PluginDownloadType으로 변환합니다. */
        fun fromNative(native: DownloadType): PluginDownloadType = when (native) {
            DownloadType.Video -> Video
            DownloadType.Audio -> Audio
            DownloadType.Playlist -> Playlist
            DownloadType.Command -> Command
        }

        /** 표시 문자열 리소스를 반환합니다. */
        @Composable
        fun PluginDownloadType.label(): String = stringResource(
            when (this) {
                Audio -> R.string.audio
                Video -> R.string.video
                Command -> R.string.commands
                Auto -> R.string.loc_download_auto
                Image -> R.string.loc_download_image
                Playlist -> R.string.playlist
            }
        )
    }
}

