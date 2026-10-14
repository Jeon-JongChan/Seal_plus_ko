package com.plugin.faces

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 화면을 닫아도 모델 하나·이미지 한 장씩 처리하는 사용자 요청 스캔입니다. */
class FaceScanService : Service() {
    // GPU의 EGL 컨텍스트가 코루틴 재개 때 다른 스레드로 넘어가지 않도록 고정합니다.
    private val detectorDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + detectorDispatcher)
    private var started = false
    private var scanJob: Job? = null

    /** 알림은 작업 시작 즉시 표시하고 상세 진행은 독립 메뉴에 전달합니다. */
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("face-sort", "얼굴 없는 사진 분류", NotificationManager.IMPORTANCE_LOW))
        startForeground(4110, NotificationCompat.Builder(this, "face-sort")
            .setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("얼굴 없는 사진 분류 중")
            .setContentText("이미지를 한 장씩 확인합니다.").setOngoing(true).build())
    }

    /** 검사 시작 당시 폴더·모델 옵션을 고정하고 판독 오류를 얼굴 없음과 구별합니다. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            // 네이티브 실행이 끝나기 전에 서비스와 실행 잠금을 해제하지 않습니다.
            scanJob?.cancel() ?: stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val request = pending ?: run { stopSelf(); return START_NOT_STICKY }
        pending = null
        started = true
        scanJob = scope.launch {
            try {
                FaceDetector(this@FaceScanService, request.options.model, request.options.threshold,
                    request.options.resolution, request.options.backend).use { detector ->
                    progress.value = progress.value.copy(message = detector.engine)
                    // ponytail: 전체 파일 목록을 저장하지 않는 순회입니다. 정밀 전체 진행률은 별도 사전 집계가 필요합니다.
                    for (candidate in FaceFolders.candidates(this@FaceScanService, request.preferences, request.options.perFolder)) {
                        ensureActive()
                        progress.value = progress.value.copy(file = candidate.file.name)
                        try {
                            val bitmap = FaceImages.decode(this@FaceScanService, candidate.file.path, request.options.resolution)
                            val hasFace = try { detector.hasFace(bitmap) } finally { bitmap.recycle() }
                            progress.value = progress.value.copy(message = detector.engine)
                            ensureActive()
                            if (hasFace) progress.value = progress.value.copy(kept = progress.value.kept + 1)
                            else {
                                // 이동·이력 갱신 한 건은 함께 완료한 뒤 다음 중단 요청을 처리합니다.
                                withContext(NonCancellable) { FaceFolders.move(this@FaceScanService, candidate) }
                                progress.value = progress.value.copy(moved = progress.value.moved + 1)
                            }
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            android.util.Log.e("FaceScanService", "얼굴 분류 실패: ${candidate.file.path}", failure)
                            progress.value = progress.value.copy(failures = progress.value.failures + 1,
                                error = "${candidate.file.name}: ${failure.message.orEmpty()}")
                        }
                        progress.value = progress.value.copy(scanned = progress.value.scanned + 1)
                    }
                }
                progress.value = progress.value.copy(message = "스캔 완료")
            } catch (_: CancellationException) {
                progress.value = progress.value.copy(message = "스캔 중단 · 이미 이동한 파일은 보존됩니다.")
            } catch (failure: Throwable) {
                // 메모리 부족·네이티브 모듈 로딩 실패도 얼굴 없음으로 간주하지 않습니다.
                progress.value = progress.value.copy(failures = progress.value.failures + 1,
                    error = failure.message ?: failure.javaClass.simpleName, message = "스캔을 완료하지 못했습니다.")
            }
        }
        // 코루틴 본문 시작 전에 취소돼도 서비스가 남지 않도록 완료 처리기를 사용합니다.
        scanJob!!.invokeOnCompletion { stopSelf() }
        return START_NOT_STICKY
    }

    /** 실행 요청을 취소하고 다음 진입에 마지막 통계만 남깁니다. */
    override fun onDestroy() {
        scope.cancel()
        detectorDispatcher.close()
        progress.value = progress.value.copy(running = false)
        super.onDestroy()
    }

    /** 진행 상태는 앱 내 상태 흐름으로 전달하므로 바인딩하지 않습니다. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** 실행 당시 옵션이며 사용자 설정 변경이 진행 중 스캔에 섞이지 않습니다. */
    data class Options(val model: String = "yunet", val resolution: Int = 640,
        val perFolder: Boolean = false, val threshold: Float = 0.5f, val backend: String = "gpu")

    /** 파일별 비트맵을 보관하지 않는 누적 통계입니다. */
    data class Progress(val running: Boolean = false, val scanned: Int = 0, val moved: Int = 0,
        val kept: Int = 0, val failures: Int = 0, val file: String = "", val message: String = "", val error: String = "")

    /** 실제 서비스 시작 직전 전달하는 폴더 설정 사본입니다. */
    private data class Request(val preferences: DownloadPreferences, val options: Options)

    companion object {
        private var pending: Request? = null
        private val progress = MutableStateFlow(Progress())
        val state = progress.asStateFlow()

        /** 중복 시작을 차단하고 사용자 스캔 버튼으로만 파일 이동을 시작합니다. */
        @Synchronized
        fun start(context: Context, preferences: DownloadPreferences, options: Options) {
            check(!com.plugin.compatibility.TemporarySession.changing) { "저장소 전환이 진행 중입니다." }
            if (progress.value.running) return
            pending = Request(preferences, options)
            progress.value = Progress(running = true, message = "${if (options.model == "yunet") "YuNet" else "MediaPipe BlazeFace"} · 초기화 중")
            try {
                check(!com.plugin.compatibility.TemporarySession.changing) { "저장소 전환이 진행 중입니다." }
                ContextCompat.startForegroundService(context, Intent(context, FaceScanService::class.java))
            }
            catch (failure: Exception) {
                pending = null
                progress.value = Progress(failures = 1, error = failure.message.orEmpty())
            }
        }

        /** 취소 요청은 다음 이미지 이동 전에 검사하며 확정된 이동은 되돌리지 않습니다. */
        fun stop(context: Context) {
            if (progress.value.running) context.startService(Intent(context, FaceScanService::class.java).setAction("stop"))
        }
    }
}
