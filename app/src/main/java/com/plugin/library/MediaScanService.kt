package com.plugin.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.junkfood.seal.R
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.MediaLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 파일 관리 화면을 닫아도 지정 폴더의 스캔·분류를 IO 스레드에서 계속합니다. */
class MediaScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    /** 시스템 알림으로 장시간 파일 작업이 진행 중임을 표시합니다. */
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("media-scan", getString(R.string.revision5_scanning), NotificationManager.IMPORTANCE_LOW))
        startForeground(4105, NotificationCompat.Builder(this, "media-scan")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.revision5_scanning)).setOngoing(true).build())
    }

    /** 시작 시점의 폴더 설정 사본을 사용하고 중복 스캔 요청은 겹쳐 실행하지 않습니다. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (started) return START_NOT_STICKY
        val preferences = pending ?: run { stopSelf(); return START_NOT_STICKY }
        pending = null
        started = true
        scope.launch {
            try {
                val files = MediaLibrary.listVideos(preferences, includeClassified = intent?.getBooleanExtra("all", false) == true)
                progress.value = ScanProgress(running = true, total = files.size)
                val processedPaths = mutableSetOf<String>()
                for (file in files) {
                    ensureActive()
                    // 앞선 이동·대체로 내용이 바뀌거나 사라진 스냅샷 항목을 옛 화질로 다시 분류하지 않습니다.
                    if (file.path in processedPaths || file.bytes > 0 && MediaLibrary.fileBytes(file.path) == 0L) {
                        progress.value = progress.value.copy(done = progress.value.done + 1)
                        continue
                    }
                    val result = runCatching {
                        check(file.quality != null) { "미디어 정보 확인 실패: ${file.name}" }
                        MediaLibrary.classify(file, preferences, intent?.getBooleanExtra("replaceDuplicates", false) == true)
                    }
                    result.getOrNull()?.let { processedPaths.add(it) }
                    result.exceptionOrNull()?.let {
                        if (it is CancellationException) throw it
                        android.util.Log.e("MediaScanService", "파일 분류 실패: ${file.path}", it)
                    }
                    progress.value = progress.value.copy(done = progress.value.done + 1,
                        failures = progress.value.failures + if (result.isFailure) 1 else 0,
                        error = result.exceptionOrNull()?.message ?: progress.value.error)
                }
                // 분류에서 제외한 화질 폴더의 외부 파일도 다운로드 메뉴에 등록합니다.
                MediaLibrary.registerExternalFiles(MediaLibrary.listVideos(preferences))
                if (intent?.getBooleanExtra("cleanup", false) == true)
                    progress.value = progress.value.copy(cleaned = MediaLibrary.cleanEmptyFolders(preferences))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                progress.value = progress.value.copy(failures = progress.value.failures + 1, error = error.message.orEmpty())
            } finally {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** 서비스 종료 시 진행 중인 IO 작업을 중단합니다. */
    override fun onDestroy() {
        scope.cancel()
        progress.value = progress.value.copy(running = false)
        super.onDestroy()
    }

    /** 화면은 상태 흐름으로 결과를 읽으므로 바인딩은 사용하지 않습니다. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** 스캔 결과와 마지막 파일 오류를 표시하는 최소 상태입니다. */
    data class ScanProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0,
        val failures: Int = 0, val error: String = "", val cleaned: Int = 0)

    companion object {
        private var pending: DownloadPreferences? = null
        private val progress = MutableStateFlow(ScanProgress())
        val state = progress.asStateFlow()

        /** 사용자 버튼 요청으로만 스캔을 시작하며 원본 설정·폴더는 삭제하지 않습니다. */
        @Synchronized
        fun start(context: Context, preferences: DownloadPreferences, all: Boolean, cleanupEmpty: Boolean = false) {
            if (progress.value.running) return
            pending = preferences
            progress.value = ScanProgress(running = true)
            try {
                ContextCompat.startForegroundService(context, Intent(context, MediaScanService::class.java)
                    .putExtra("all", all).putExtra("cleanup", cleanupEmpty && !preferences.sdcard)
                    .putExtra("replaceDuplicates", com.plugin.compatibility.PluginPreferences.mediaReplaceDuplicates))
            } catch (error: Exception) {
                pending = null
                progress.value = ScanProgress(failures = 1, error = error.message.orEmpty())
            }
        }
    }
}
