package com.plugin.compatibility.enhancement

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.os.*
import kotlinx.coroutines.*
import java.io.File

/** 외부에 노출하지 않은 별도 프로세스에서 이미지 하나만 처리합니다. */
open class EnhancementService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == CANCEL) {
            VideoEnhancer.stop()
            // 네이티브 추론도 즉시 끝내되 다운로드 프로세스에는 영향을 주지 않습니다.
            Process.killProcess(Process.myPid())
        } else if (message.what == RUN && job?.isActive != true) {
            val data = Bundle(message.data); val reply = message.replyTo
            job = scope.launch {
                try {
                    if (data.getBoolean("video")) foregroundVideo()
                    var lastProgress = -1; var lastEngine: String? = null
                    process(data) { percent ->
                        val engine = data.getString("engine")
                        if (percent != lastProgress || engine != lastEngine) {
                            lastProgress = percent; lastEngine = engine
                            send(reply, PROGRESS, Bundle().apply { putInt("progress", percent); putString("engine", engine) })
                        }
                    }
                    send(reply, DONE, data)
                } catch (failure: Throwable) {
                    send(reply, FAILED, Bundle().apply { putString("error", if (failure is OutOfMemoryError)
                        "보정 메모리가 부족합니다. 더 낮은 해상도를 선택하세요." else failure.message ?: "이미지 보정에 실패했습니다.") })
                } finally { if (data.getBoolean("video")) stopForeground(STOP_FOREGROUND_REMOVE) }
            }
        } else if (message.what == RUN) send(message.replyTo, FAILED, Bundle().apply { putString("error", "이미지 보정 작업이 실행 중입니다.") })
        true
    })

    /** 화면에서 바인딩한 앱 내부 요청만 받습니다. */
    override fun onBind(intent: Intent): IBinder = messenger.binder

    /** 화면 취소·종료 시 프로세스를 정리해 네이티브 메모리도 반환합니다. */
    override fun onUnbind(intent: Intent): Boolean {
        VideoEnhancer.stop()
        scope.cancel()
        Process.killProcess(Process.myPid())
        return false
    }

    /** 확정되지 않은 출력은 캐시 아래에서만 생성하고 원본에는 쓰지 않습니다. */
    private suspend fun process(data: Bundle, progress: (Int) -> Unit) {
        val root = File(cacheDir, "image-ai").canonicalFile
        val directory = File(data.getString("directory") ?: error("작업 경로가 없습니다.")).canonicalFile
        require(directory.path.startsWith(root.path + File.separator)) { "잘못된 보정 작업 경로입니다." }
        if (data.getBoolean("video")) { VideoEnhancer.process(this, directory, data, progress); return }
        val sourceFile = File(directory, "source")
        val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(sourceFile)) { decoder, info, _ ->
            check(info.size.width.toLong() * info.size.height <= EnhancementRules.MAX_PIXELS) { "원본 이미지가 너무 큽니다." }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }
        val source = (decoded as? BitmapDrawable)?.bitmap ?: error("움직이는 이미지는 보정하지 않습니다.")
        var output: Bitmap? = null
        try {
            val sampling = EnhancementSampling.normalized(data.getInt("sampling", EnhancementSampling.BILINEAR))
            val target = EnhancementRules.target(source.width, source.height, data.getInt("option"))
            check((source.width.toLong() * source.height + target.first.toLong() * target.second) * 4 + 32 * 1024 * 1024 <=
                Runtime.getRuntime().maxMemory() * 7 / 10) { "이 기기의 메모리로 처리하기에는 큽니다. 더 낮은 배율을 선택하세요." }
            val model = File(directory, "model.onnx")
            // CPU 전용 선택은 Google의 자동 가속 우선 설정보다 우선합니다.
            val googleAvailable = if (data.getBoolean("google") && data.getBoolean("accelerated")) {
                try { GoogleEnhancer.availability(this).let { it.first && it.second } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { false }
            } else false
            if (googleAvailable) {
                try {
                    data.putString("engine", "Google 보정 실행 중"); progress(0)
                    var current = source
                    try {
                        for (pass in 0 until 4) {
                            val upscale = target.first > current.width || target.second > current.height
                            val next = GoogleEnhancer.process(this, current, upscale)
                            if (current !== source && current !== next) current.recycle()
                            current = next
                            check(current.width.toLong() * current.height <= EnhancementRules.MAX_PIXELS) { "Google 결과가 너무 큽니다." }
                            if (!upscale || current.width >= target.first && current.height >= target.second) break
                        }
                        check(current.width >= target.first && current.height >= target.second) { "Google 업스케일 배율을 지원하지 않습니다." }
                        output = if (current.width == target.first && current.height == target.second) current
                            else EnhancementSampling.resize(current, target.first, target.second, sampling).also { if (current !== source) current.recycle() }
                        data.putString("engine", "Google")
                    } catch (failure: Throwable) { if (current !== source) current.recycle(); throw failure }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (!model.isFile) throw failure
                }
            }
            var liteRtFallback = false
            val liteModel = File(directory, "model.tflite")
            if (output == null && data.getString("backend") == "litert" && liteModel.isFile) {
                try {
                    data.putString("engine", if (!data.getBoolean("accelerated")) "LiteRT CPU 초기화 중"
                        else if (data.getBoolean("allow_npu", true)) "LiteRT NPU·GPU 초기화 중" else "LiteRT GPU 초기화 중 · NPU 제외")
                    progress(0)
                    output = LiteRtEnhancer.process(this, source, liteModel.path, target, progress,
                        data.getBoolean("accelerated"), data.getInt("cpu_threads", 2), data.getBoolean("allow_npu", true), sampling) { data.putString("engine", it); progress(0) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { android.util.Log.w("ImageAi", "LiteRT 실행 실패로 ONNX 대체", failure); liteRtFallback = true }
                catch (failure: LinkageError) { android.util.Log.w("ImageAi", "LiteRT ABI·런타임 미지원으로 ONNX 대체", failure); liteRtFallback = true }
            }
            if (output == null && liteRtFallback && !model.isFile && liteModel.isFile) {
                check(data.getBoolean("accelerated")) { "LiteRT CPU에서 이 모델을 실행할 수 없습니다." }
                // 사용자 TFLite에는 ONNX 사본이 없으므로 가속 실패는 동일 모델의 CPU로 복구합니다.
                output = LiteRtEnhancer.process(this, source, liteModel.path, target, progress,
                    false, data.getInt("cpu_threads", 2), false, sampling) { data.putString("engine", "가속 실패 후 $it"); progress(0) }
            }
            if (output == null) {
                check(model.isFile) { "사용할 모델을 먼저 선택하거나 Google 모듈을 설치하세요." }
                val onnxAccelerated = data.getBoolean("accelerated") && data.getBoolean("allow_npu", true)
                data.putString("engine", if (onnxAccelerated) "ONNX 가속 초기화 중" else "ONNX CPU 초기화 중")
                progress(0)
                output = OnnxEnhancer.process(source, model.absolutePath, target, progress, onnxAccelerated,
                    { data.putString("engine", if (liteRtFallback) "LiteRT 대체 · $it" else it); progress(0) }, cpuThreads = data.getInt("cpu_threads", 2), sampling = sampling)
            }
            currentCoroutineContext().ensureActive()
            val image = checkNotNull(output)
            write(image, File(directory, "preview.png"), Bitmap.CompressFormat.PNG)
            val format = when (data.getString("format")) {
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.PNG
            }
            write(image, File(directory, "replacement"), format)
            data.putInt("width", image.width); data.putInt("height", image.height)
            data.putInt("worker_pid", Process.myPid())
        } finally { if (output !== source) output?.recycle(); source.recycle() }
    }

    /** 출력 스트림을 동기화한 뒤에만 성공 결과를 전송합니다. */
    private fun write(bitmap: Bitmap, file: File, format: Bitmap.CompressFormat) {
        file.outputStream().use { output ->
            check(bitmap.compress(format, 98, output)) { "보정 결과를 저장할 수 없습니다." }
            output.fd.sync()
        }
    }

    /** 긴 영상 작업은 OS의 미디어 처리 전경 서비스 시간 제한을 따릅니다. */
    private fun foregroundVideo() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("video_ai", "AI 동영상 화질개선", android.app.NotificationManager.IMPORTANCE_LOW))
        val notification = android.app.Notification.Builder(this, "video_ai").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AI 동영상 화질개선").setContentText("영상 처리 중 · 앱에서 취소할 수 있습니다.").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 35) startForeground(7421, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (Build.VERSION.SDK_INT >= 29) startForeground(7421, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(7421, notification)
    }

    /** 플랫폼 제한 시간에 도달하면 외부 처리와 네이티브 작업을 함께 종료합니다. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        VideoEnhancer.stop(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        Process.killProcess(Process.myPid())
    }

    /** 닫힌 화면의 응답 채널 오류는 보정 프로세스 안에서만 처리합니다. */
    private fun send(reply: Messenger?, what: Int, data: Bundle) {
        runCatching { reply?.send(Message.obtain(null, what).apply { this.data = data }) }
    }

    companion object {
        const val RUN = 1
        const val CANCEL = 2
        const val PROGRESS = 3
        const val DONE = 4
        const val FAILED = 5
    }
}

/** NPU 초기화 중 네이티브 종료가 발생해도 다른 프로세스에서 GPU를 다시 시도합니다. */
class EnhancementGpuService : EnhancementService()

/** 두 가속 경로가 종료돼도 다운로드와 분리된 새 CPU 프로세스에서 재시도합니다. */
class EnhancementCpuService : EnhancementService()
