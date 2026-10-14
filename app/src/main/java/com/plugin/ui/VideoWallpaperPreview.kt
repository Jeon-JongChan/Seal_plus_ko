package com.plugin.ui

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import com.plugin.compatibility.VideoWallpaperSource

/** 이미지 프레임 대신 실제 영상을 무음·반복·중앙 채우기로 미리 재생합니다. */
@Composable
fun VideoWallpaperPreview(source: String, modifier: Modifier = Modifier, onReady: (Boolean) -> Unit, onError: () -> Unit,
    muted: Boolean = true, crop: Boolean = true) {
    val ready by rememberUpdatedState(onReady)
    val failed by rememberUpdatedState(onError)
    val context = androidx.compose.ui.platform.LocalContext.current
    var player by remember(source) { mutableStateOf<MediaPlayer?>(null) }
    var aspect by remember(source) { mutableFloatStateOf(16f / 9f) }
    val view = remember(source, muted, crop) { SurfaceView(context).apply {
        /** 표면의 수명 안에서만 디코더를 소유합니다. */
        holder.addCallback(object : SurfaceHolder.Callback {
            /** 영상 준비가 끝나기 전에는 적용 버튼을 활성화하지 않습니다. */
            override fun surfaceCreated(holder: SurfaceHolder) {
                val next = MediaPlayer(); player = next
                try {
                    next.setDataSource(context, VideoWallpaperSource.uri(source)); next.setDisplay(holder)
                    next.isLooping = true; next.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                    next.setOnVideoSizeChangedListener { current, width, height ->
                        if (player === current && width > 0 && height > 0) aspect = width.toFloat() / height
                    }
                    next.setOnPreparedListener { prepared ->
                        if (player === prepared && holder.surface.isValid) runCatching {
                            prepared.setVideoScalingMode(if (crop) MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING else MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT); prepared.start()
                        }.onSuccess { ready(true) }.onFailure { ready(false); failed() }
                    }
                    next.setOnErrorListener { broken, _, _ -> if (player === broken) { ready(false); failed() }; true }
                    next.prepareAsync()
                } catch (_: Exception) { ready(false); failed() }
            }
            /** 중앙 채우기는 MediaPlayer가 출력 크기에 맞춰 계산합니다. */
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            /** 페이지가 바뀌면 이전 디코더와 출력 표면을 해제합니다. */
            override fun surfaceDestroyed(holder: SurfaceHolder) { player?.let { runCatching { it.release() } }; player = null }
        })
    } }
    DisposableEffect(view) { onDispose { player?.let { runCatching { it.release() } }; player = null } }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { view }, modifier = if (crop) Modifier.matchParentSize()
            else Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = true))
    }
}
