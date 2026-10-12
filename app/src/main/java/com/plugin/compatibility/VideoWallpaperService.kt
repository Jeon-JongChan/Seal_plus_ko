package com.plugin.compatibility

import android.app.WallpaperManager
import android.content.*
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.util.AtomicFile
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 시스템 확인 전에는 후보만 만들고 기존 영상 배경화면 사본은 변경하지 않습니다. */
object VideoWallpaperSource {
    const val SOURCE_CHANGED = "com.plugin.video_wallpaper.SOURCE_CHANGED"

    /** 사본·선택 포인터는 앱 전용 폴더에만 보관합니다. */
    private fun root(context: Context) = File(context.filesDir, "video-wallpaper").apply { check(isDirectory || mkdirs()) }

    /** 원본을 삭제하지 않고 파일·문서 URI에서 읽습니다. */
    fun uri(source: String): Uri = if (source.startsWith("content://") || source.startsWith("file://")) Uri.parse(source) else Uri.fromFile(File(source))

    /** 생성한 UUID 이름만 허용해 다른 앱 파일을 참조하지 못하게 합니다. */
    private fun owned(context: Context, name: String): File {
        require(name.matches(Regex("[a-f0-9-]{36}\\.mp4"))) { "잘못된 영상 사본입니다." }
        val directory = root(context).canonicalFile
        return File(directory, name).canonicalFile.also { require(it.parentFile == directory) }
    }

    /** 포인터 변경은 Android의 원자적 파일 쓰기를 사용합니다. */
    private fun write(context: Context, key: String, name: String) {
        val file = AtomicFile(File(root(context), key)); val stream = file.startWrite()
        try { stream.write(name.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }

    /** 잘못된 포인터·사라진 사본은 재생하지 않습니다. */
    private fun read(context: Context, key: String): File? = runCatching {
        AtomicFile(File(root(context), key)).openRead().use { owned(context, it.readBytes().toString(Charsets.UTF_8).trim()) }
    }.getOrNull()?.takeIf { it.isFile && it.length() > 0 }

    /** 미리보기 엔진은 후보를, 실제 홈 화면 엔진은 확정된 사본만 읽습니다. */
    fun selected(context: Context, preview: Boolean): File? = if (preview) read(context, "pending") ?: read(context, "active") else read(context, "active")

    /** 권한이 없어지거나 원본이 정리되어도 재생할 수 있도록 검증한 전용 사본을 준비합니다. */
    suspend fun prepare(context: Context, source: String): String = withContext(Dispatchers.IO) {
        require(!source.startsWith("http")) { "다운로드된 영상으로 배경화면을 설정하세요." }
        val name = java.util.UUID.randomUUID().toString() + ".mp4"
        val file = owned(context, name)
        try {
            context.contentResolver.openInputStream(uri(source))?.use { input -> file.outputStream().use { output -> input.copyTo(output); output.fd.sync() } }
                ?: error("영상을 읽을 수 없습니다.")
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                check((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0) { "재생 가능한 영상이 아닙니다." }
            } finally { retriever.release() }
            val previous = read(context, "pending")
            write(context, "pending", name)
            if (previous != read(context, "active")) previous?.delete()
            name
        } catch (failure: Throwable) { file.delete(); throw failure }
    }

    /** 시스템에서 적용을 확인한 뒤에만 실제 재생 소스를 교체합니다. */
    suspend fun commit(context: Context, name: String) = withContext(Dispatchers.IO) {
        val next = owned(context, name)
        check(read(context, "pending") == next && next.isFile) { "영상 선택이 변경되었습니다. 다시 설정하세요." }
        val old = read(context, "active")
        write(context, "active", name)
        AtomicFile(File(root(context), "pending")).delete()
        context.sendBroadcast(Intent(SOURCE_CHANGED).setPackage(context.packageName))
        if (old != next) old?.delete()
    }

    /** 취소 시 후보 사본만 정리하며 원본과 적용 중인 사본은 보존합니다. */
    suspend fun discard(context: Context, name: String) = withContext(Dispatchers.IO) {
        val candidate = owned(context, name)
        if (read(context, "pending") == candidate) AtomicFile(File(root(context), "pending")).delete()
        if (read(context, "active") != candidate) candidate.delete()
        Unit
    }

    /** 시스템 미리보기와 최종 확인을 특정 서비스로 연결합니다. */
    fun intent(context: Context) = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, VideoWallpaperService::class.java))
}

/** 영상은 보일 때만 소리 없이 재생하고 숨겨지면 디코더까지 해제합니다. */
class VideoWallpaperService : WallpaperService() {
    private val engines = mutableSetOf<VideoEngine>()
    /** 앱에서 확정한 소스만 같은 UID의 엔진에 반영합니다. */
    private val changed = object : BroadcastReceiver() {
        /** 확정된 소스 변경 후 보이는 엔진만 다시 준비합니다. */
        override fun onReceive(context: Context, intent: Intent) { engines.toList().forEach { it.reload() } }
    }

    /** 외부 앱에 노출되지 않는 변경 수신기를 등록합니다. */
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, changed, IntentFilter(VideoWallpaperSource.SOURCE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** 시스템의 미리보기·실제 배경화면마다 독립 재생 엔진을 만듭니다. */
    override fun onCreateEngine(): Engine = VideoEngine().also { engines += it }

    /** 서비스 종료 시 디코더와 수신기를 함께 해제합니다. */
    override fun onDestroy() { engines.toList().forEach { it.stop() }; unregisterReceiver(changed); super.onDestroy() }

    /** MediaPlayer의 표준 하드웨어 디코딩과 중앙 채우기를 사용합니다. */
    private inner class VideoEngine : Engine() {
        private var player: MediaPlayer? = null
        private var visible = false
        /** 포인터 변경·화면 복귀 때만 파일을 열고 준비합니다. */
        fun reload() {
            stop()
            if (!visible || !surfaceHolder.surface.isValid) return
            val source = VideoWallpaperSource.selected(this@VideoWallpaperService, isPreview) ?: return
            val next = MediaPlayer()
            player = next
            try {
                next.setDataSource(source.path); next.setDisplay(surfaceHolder)
                next.isLooping = true; next.setVolume(0f, 0f)
                next.setOnPreparedListener { if (player === it && visible) { runCatching { it.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING); it.start() }.onFailure { stop() } } }
                next.setOnErrorListener { _, _, _ -> stop(); true }
                next.prepareAsync()
            } catch (_: Exception) { stop() }
        }
        /** 숨겨진 배경화면이 메모리·CPU·오디오를 점유하지 않도록 해제합니다. */
        fun stop() { player?.let { runCatching { it.release() } }; player = null }
        /** 화면이 나타나는 경우만 디코딩을 시작합니다. */
        override fun onVisibilityChanged(visible: Boolean) { this.visible = visible; if (visible) reload() else stop() }
        /** 시스템이 새 출력 표면을 만든 뒤 재생을 준비합니다. */
        override fun onSurfaceCreated(holder: SurfaceHolder) { super.onSurfaceCreated(holder); reload() }
        /** 표면이 사라지면 유효하지 않은 표면에 쓰지 않습니다. */
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { stop(); super.onSurfaceDestroyed(holder) }
        /** 엔진 참조와 디코더를 모두 정리합니다. */
        override fun onDestroy() { stop(); engines -= this; super.onDestroy() }
    }
}
