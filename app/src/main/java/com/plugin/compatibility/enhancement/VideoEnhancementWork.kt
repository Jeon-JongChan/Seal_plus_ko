package com.plugin.compatibility.enhancement

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.plugin.ui.PluginMainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import org.json.JSONObject

/** 화면 수명과 분리하여 영상 보정을 실행하고 미확정 결과를 알림으로 다시 엽니다. */
class VideoEnhancementWork : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** 내부 실행 요청만 받으며 바인딩은 기존 격리 작업 서비스에 맡깁니다. */
    override fun onBind(intent: Intent): IBinder? = null

    /** 화면을 떠나도 원본 준비부터 결과 저장까지 전경 서비스 안에서 이어갑니다. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true || intent == null) return START_NOT_STICKY
        val source = intent.getStringExtra("source") ?: run { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "영상 보정 진행", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(RESULT_CHANNEL, "영상 보정 완료", NotificationManager.IMPORTANCE_DEFAULT))
        try {
            val notification = notification(CHANNEL, "원본과 모델 준비 중", ongoing = true)
            if (Build.VERSION.SDK_INT >= 35) startForeground(ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            else if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, notification)
        } catch (failure: Exception) {
            state.value = State(source = source, error = failure.message.orEmpty())
            stopSelf(); return START_NOT_STICKY
        }
        state.value = State(source = source, running = true)
        job = scope.launch {
            try {
                val preview = EnhancementClient.enhanceVideo(applicationContext, source,
                    intent.getIntExtra("option", 2), intent.getStringExtra("model") ?: error("모델이 없습니다."),
                    intent.getIntExtra("fps", 0), { state.value = state.value.copy(engine = it) }) { percent ->
                    if (percent != state.value.progress) {
                        state.value = state.value.copy(progress = percent)
                        manager.notify(ID, notification(CHANNEL, "영상 처리 중 · $percent%", ongoing = true))
                    }
                }
                // 원본은 수정하지 않고 완료 결과의 경로·검증값만 앱 전용 저장소에 남깁니다.
                try { withContext(Dispatchers.IO) { persist(applicationContext, preview) } }
                catch (failure: Exception) { EnhancementClient.discard(preview); throw failure }
                state.value = state.value.copy(running = false, preview = preview, progress = 100)
                runCatching { manager.notify(RESULT_ID, notification(RESULT_CHANNEL, "눌러서 원본과 보정 결과를 비교하세요.", false)) }
            } catch (cancel: CancellationException) {
                state.value = State(source = source); throw cancel
            } catch (failure: Exception) {
                state.value = State(source = source, error = failure.message ?: "영상 보정에 실패했습니다.")
                runCatching { manager.notify(RESULT_ID, notification(RESULT_CHANNEL, state.value.error, false)) }
            } finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }

    /** 시스템 제한 시간에 도달하면 원본 보존 상태로 작업을 취소합니다. */
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopSelf() }

    /** 명시적 취소와 서비스 종료만 격리 작업의 취소로 이어집니다. */
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** 완료 알림은 파일 경로를 외부 인텐트에 노출하지 않고 내부 결과 화면만 요청합니다. */
    private fun notification(channel: String, text: String, ongoing: Boolean): Notification {
        val pending = PendingIntent.getActivity(this, RESULT_ID,
            Intent(this, PluginMainActivity::class.java).setAction(RESULT_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("AI 동영상 화질개선").setContentText(text).setContentIntent(pending)
            .setProgress(if (ongoing) 100 else 0, if (ongoing) state.value.progress else 0, ongoing && state.value.progress == 0)
            .setOngoing(ongoing).setAutoCancel(!ongoing).setOnlyAlertOnce(ongoing).build()
    }

    companion object {
        const val RESULT_ACTION = "com.plugin.compatibility.enhancement.RESULT"
        private const val CHANNEL = "video_ai_work"
        private const val RESULT_CHANNEL = "video_ai_result"
        private const val ID = 7422
        private const val RESULT_ID = 7423
        val state = MutableStateFlow(State())
        val showResult = MutableStateFlow(false)
        val saved = MutableStateFlow<Saved?>(null)

        /** 단일 실행의 진행·오류·미확정 결과를 화면과 공유합니다. */
        data class State(val source: String = "", val running: Boolean = false, val progress: Int = 0,
            val engine: String = "", val error: String = "", val preview: EnhancementClient.Preview? = null)

        /** 열려 있는 원본 뷰어에도 승인한 저장 결과를 전달합니다. */
        data class Saved(val source: String, val output: String, val replace: Boolean)

        /** 진행 중이거나 확인할 결과가 있으면 새 작업으로 덮어쓰지 않습니다. */
        fun start(context: Context, source: String, option: Int, model: String, fps: Int) {
            check(Build.VERSION.SDK_INT >= 28) { "AI 동영상 화질개선은 Android 9 이상에서 지원합니다." }
            restore(context)
            check(!state.value.running && state.value.preview == null && !EnhancementClient.busy) { "진행 중인 작업 또는 보정 결과를 먼저 확인하세요." }
            state.value = State(source = source, running = true)
            try {
                context.startForegroundService(Intent(context, VideoEnhancementWork::class.java)
                    .putExtra("source", source).putExtra("option", option).putExtra("model", model).putExtra("fps", fps))
            } catch (failure: Exception) { state.value = State(source = source, error = failure.message.orEmpty()); throw failure }
        }

        /** 취소 버튼만 실행 중인 작업을 중단합니다. */
        fun cancel(context: Context) { context.stopService(Intent(context, VideoEnhancementWork::class.java)) }

        /** 비교 종료·저장 승인 후에는 해당 미확정 결과만 정리합니다. */
        fun dismiss(context: Context) {
            state.value.preview?.let(EnhancementClient::discard)
            context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE).edit().remove("preview").commit()
            state.value = State(); showResult.value = false
            context.getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
        }

        /** 완료 후 앱 프로세스가 다시 만들어져도 비교할 작업 사본을 복구합니다. */
        fun restore(context: Context) {
            if (state.value.running || state.value.preview != null) return
            val encoded = context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE).getString("preview", null) ?: return
            runCatching {
                val data = JSONObject(encoded)
                val directory = File(data.getString("directory")).canonicalFile
                require(directory.path.startsWith(File(context.cacheDir, "image-ai").canonicalPath + File.separator))
                require(File(directory, "preview.mp4").isFile && File(directory, "source").isFile)
                val preview = EnhancementClient.Preview(data.getString("source"), directory, data.getString("hash"),
                    data.getInt("width"), data.getInt("height"), data.getString("format"), data.getString("engine"), 0,
                    true, data.getDouble("fps"), data.getDouble("duration"), data.getString("codec"))
                state.value = State(source = preview.source, preview = preview, progress = 100)
            }.onFailure { context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE).edit().remove("preview").apply() }
        }

        /** 복구에 필요한 작은 결과 명세만 동기적으로 기록합니다. */
        private fun persist(context: Context, preview: EnhancementClient.Preview) {
            val data = JSONObject().put("source", preview.source).put("directory", preview.directory.path)
                .put("hash", preview.hash).put("width", preview.width).put("height", preview.height)
                .put("format", preview.format).put("engine", preview.engine).put("fps", preview.frameRate)
                .put("duration", preview.duration).put("codec", preview.codec)
            check(context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE).edit().putString("preview", data.toString()).commit()) {
                "완료 결과를 기록할 수 없습니다."
            }
        }
    }
}
