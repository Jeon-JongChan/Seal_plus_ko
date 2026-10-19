package com.plugin.ui

import android.media.MediaPlayer
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import com.plugin.compatibility.VideoWallpaperSource
import com.plugin.compatibility.fitVideoTexture

/** 이미지 프레임 대신 실제 영상을 무음·반복·중앙 채우기로 미리 재생합니다. */
@Composable
fun VideoWallpaperPreview(source: String, modifier: Modifier = Modifier, onReady: (Boolean) -> Unit, onError: () -> Unit,
    muted: Boolean = true, crop: Boolean = true) {
    val ready by rememberUpdatedState(onReady)
    val failed by rememberUpdatedState(onError)
    val context = androidx.compose.ui.platform.LocalContext.current
    key(source, muted, crop) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var rendered by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf(false) }
    val view = remember { TextureView(context).apply {
        // 투명 합성 표면을 사용해 전체 화면 대화상자의 배경이 영상을 가리지 않게 합니다.
        isOpaque = false
        /** 표면의 수명 안에서만 디코더를 소유합니다. */
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            /** 영상 준비가 끝나기 전에는 적용 버튼을 활성화하지 않습니다. */
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                rendered = false
                prepared = false; ready(false)
                val next = MediaPlayer(); player = next
                try {
                    next.setDataSource(context, VideoWallpaperSource.uri(source))
                    Surface(texture).let { surface -> try { next.setSurface(surface) } finally { surface.release() } }
                    next.isLooping = true; next.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f)
                    next.setOnVideoSizeChangedListener { current, width, height ->
                        if (player === current && width > 0 && height > 0) fitVideoTexture(this@apply, current, crop)
                    }
                    next.setOnPreparedListener { prepared ->
                        if (player === prepared && isAvailable) runCatching {
                            prepared.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                            fitVideoTexture(this@apply, prepared, crop); prepared.start()
                        }.onFailure { ready(false); failed() }
                    }
                    next.setOnInfoListener { current, what, _ ->
                        if (player === current && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                            this@apply.invalidate()
                        }
                        false
                    }
                    next.setOnErrorListener { broken, _, _ -> if (player === broken) { ready(false); failed() }; true }
                    next.prepareAsync()
                } catch (_: Exception) { ready(false); failed() }
            }
            /** 중앙 채우기는 MediaPlayer가 출력 크기에 맞춰 계산합니다. */
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                player?.let { fitVideoTexture(this@apply, it, crop) }
            }
            /** 페이지가 바뀌면 이전 디코더와 출력 표면을 해제합니다. */
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                player?.let { runCatching { it.release() } }; player = null; prepared = false; return true
            }
            /** 새 프레임은 TextureView가 대화상자의 합성 레이어에 표시합니다. */
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                // 디코더 준비만으로 재생 성공을 표시하지 않고 실제 첫 영상 프레임을 확인합니다.
                if (!rendered) { rendered = true; prepared = true; ready(true) }
            }
        }
    } }
    DisposableEffect(view) { onDispose { player?.let { runCatching { it.release() } }; player = null } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            runCatching {
                if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) player?.pause()
                else if (event == androidx.lifecycle.Lifecycle.Event.ON_START && prepared) player?.start()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // 스크롤 대화상자에서도 네이티브 텍스처를 Compose 합성 레이어에 포함합니다.
    Box(modifier.graphicsLayer(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { view }, modifier = Modifier.matchParentSize(), onReset = null, onRelease = {
            // 원본·결과 전환 시 이전 AndroidView의 콜백이 새 플레이어 상태에 접근하지 못하게 합니다.
            it.surfaceTextureListener = null
            player?.let { current -> runCatching { current.release() } }; player = null; prepared = false
        })
    }
    }
}
