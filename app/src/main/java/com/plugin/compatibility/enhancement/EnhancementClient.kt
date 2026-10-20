package com.plugin.compatibility.enhancement

import android.content.*
import android.net.Uri
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import com.plugin.compatibility.AiMediaFilter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 바인딩 수명과 원본 사본을 관리하고 네이티브 추론은 다른 프로세스에 맡깁니다. */
object EnhancementClient {
    private val running = Mutex()
    /** 저장 루트 변경 중 실행 중인 보정을 보호합니다. */
    val busy: Boolean get() = running.isLocked

    /** 지금 처리 중인 단계(전처리·AI 업스케일 등)입니다. [running] 잠금으로 보정은 한 번에 하나만 실행되어 하나의 값을 공유합니다. */
    val stage = kotlinx.coroutines.flow.MutableStateFlow("")
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 영상도 이미지와 같은 작업 잠금·격리 프로세스·검증 후 저장 절차를 사용합니다. */
    suspend fun enhanceVideo(context: Context, source: String, option: Int, model: String,
        fps: Int, onEngine: (String) -> Unit = {}, progress: (Int) -> Unit): Preview {
        check(Build.VERSION.SDK_INT >= 28) { "AI 동영상 화질개선은 Android 9 이상에서 지원합니다." }
        require(fps in VideoRules.frameRates)
        check(!com.plugin.compatibility.PluginMediaMetadataStore.isProcessingExcluded(source)) { "NOFACE 폴더는 보정 대상에서 제외됩니다." }
        check(running.tryLock()) { "다른 미디어 보정이 진행 중입니다." }
        val directory = File(context.cacheDir, "image-ai/${java.util.UUID.randomUUID()}")
        AiMediaFilter.begin(listOf(source))
        try {
            check(!com.plugin.compatibility.TemporarySession.changing) { "저장소 전환이 진행 중입니다." }
            check(directory.mkdirs()) { "영상 작업 폴더를 만들 수 없습니다." }
            onEngine("원본과 모델 준비 중"); stage.value = "준비 · 원본 사본과 모델 복사"
            val snapshot = File(directory, "source")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            EnhancementModels.stage(context, source, snapshot, VideoEnhancer.MAX_SOURCE_BYTES, digest)
            val sourceHash = digest.digest().joinToString("") { "%02x".format(it) }
            val info = withContext(Dispatchers.IO) {
                val jobContext = currentCoroutineContext()
                VideoEnhancer.inspect(snapshot) { jobContext.ensureActive() }
            }
            check(directory.usableSpace > maxOf(snapshot.length(), 128L * 1024 * 1024)) { "영상 결과를 저장할 공간이 부족합니다." }
            val lite = EnhancementModels.isLiteRt(context, model)
            val backend = if (lite) "litert" else if (model == EnhancementModels.DEFAULT_MODEL)
                com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.BACKEND.getString("litert") } else "onnx"
            EnhancementModels.stage(context, model, File(directory, if (lite) "model.tflite" else "model.onnx"))
            if (model == EnhancementModels.DEFAULT_MODEL && backend == "litert") EnhancementModels.stage(context, EnhancementModels.LITERT_MODEL, File(directory, "model.tflite"))
            if (VideoRules.increases(fps, info.fps, info.frames)) { stage.value = "준비 · 프레임 보간 모델(RIFE)"; RifeModels.prepare(context, File(directory, "rife"), onEngine) }
            val request = com.junkfood.seal.util.PreferenceUtil.run { Bundle().apply {
                putString("directory", directory.path); putBoolean("video", true); putInt("option", option); putInt("fps", fps)
                putInt("sampling", EnhancementSampling.normalized(EnhancementSampling.KEY.getInt(EnhancementSampling.BILINEAR)))
                putBoolean("original_codec", EnhancementModels.VIDEO_ORIGINAL_CODEC.getBoolean(false))
                putString("video_codec", EnhancementModels.VIDEO_CODEC.getString("auto"))
                putString("denoise", PreDenoise.mode()); putFloat("denoise_sigma", PreDenoise.sigma()); putBoolean("denoise_distance", PreDenoise.distanceWeight()); putFloat("sharpen", PostSharpen.amount())
                putString("shrink", EnhancementModels.VIDEO_SHRINK.getString(PreDenoise.OFF)); putBoolean("interp_low", EnhancementModels.VIDEO_INTERP_LOW.getBoolean(false)); putString("denoise_engine", PreDenoise.engine())
                putBoolean("accelerated", EnhancementModels.ACCELERATED.getBoolean(true))
                putInt("cpu_threads", EnhancementModels.CPU_THREADS.getInt(2)); putString("backend", backend)
            } }
            val result = withContext(Dispatchers.Main) { withTimeout(6 * 60 * 60_000L) {
                var recovered: Bundle? = null; var stopped: WorkerStoppedException? = null
                for (route in EnhancementRules.recoveryRoutes(request.getString("backend").orEmpty(), request.getBoolean("accelerated"),
                    lite || model == EnhancementModels.DEFAULT_MODEL, com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.NPU_FIRST.getBoolean(false) })) {
                    ensureActive()
                    val attempt = Bundle(request).apply { putBoolean("allow_npu", route == "automatic"); if (route == "cpu") putBoolean("accelerated", false) }
                    val service = when (route) { "cpu" -> EnhancementCpuService::class.java; "gpu" -> EnhancementGpuService::class.java; else -> EnhancementService::class.java }
                    try { recovered = execute(context, attempt, service, onEngine, progress); break }
                    catch (failure: WorkerStoppedException) { stopped = failure; onEngine("보정 프로세스 종료 · $route 다음 경로 재시도") }
                }
                recovered ?: throw (stopped ?: IllegalStateException("영상 결과를 받지 못했습니다."))
            } }
            return Preview(source, directory, sourceHash, result.getInt("width"), result.getInt("height"),
                (if (source.startsWith("content://")) androidx.documentfile.provider.DocumentFile.fromSingleUri(context, Uri.parse(source))?.name.orEmpty()
                    else File(source).name).substringAfterLast('.', "").lowercase(),
                result.getString("engine").orEmpty(), result.getInt("worker_pid"), true, result.getDouble("frame_rate"), result.getDouble("duration"), result.getString("codec").orEmpty())
        } catch (failure: Throwable) { AiMediaFilter.end(listOf(source)); directory.deleteRecursively(); throw failure }
        finally { stage.value = ""; running.unlock() }
    }

    /** 전체 게시글도 한 장씩 처리하며 실패·취소 시 이미 만든 미확정 결과를 정리합니다. */
    suspend fun enhancePost(context: Context, paths: List<String>, option: Int,
        onEngine: (String) -> Unit = {}, progress: (Int, Int, Int) -> Unit): List<Preview> {
        val images = EnhancementRules.postImages(paths)
        require(images.isNotEmpty()) { "보정할 이미지가 없습니다." }
        val previews = mutableListOf<Preview>()
        AiMediaFilter.begin(images)
        try {
            for ((index, image) in images.withIndex()) {
                currentCoroutineContext().ensureActive()
                progress(index + 1, images.size, 0)
                previews += enhance(context, image, option, onEngine) { progress(index + 1, images.size, it) }
            }
            return previews
        } catch (failure: Throwable) { AiMediaFilter.end(images); previews.forEach(::discard); throw failure }
    }

    /** 응답으로 전달된 일반 오류와 프로세스 자체의 종료를 구별합니다. */
    private class WorkerStoppedException : IllegalStateException("보정 프로세스가 종료되었습니다. 네이티브 오류 또는 시스템 메모리 회수 여부를 확인해야 합니다.")

    /** 사용자 승인 전까지만 보관하는 결과와 원본 검증값입니다. */
    data class Preview(val source: String, val directory: File, val hash: String,
        val width: Int, val height: Int, val format: String, val engine: String, val workerPid: Int,
        val video: Boolean = false, val frameRate: Double = 0.0, val duration: Double = 0.0, val codec: String = "") {
        val image: File get() = File(directory, if (video) "preview.mp4" else "preview.png")

        private var saves = 0
        private var discarded = false

        /** 저장 중에는 화면이 사라져도 작업 사본을 보존합니다. */
        @Synchronized internal fun retain() {
            check(!discarded) { "이미 닫힌 보정 결과입니다." }
            saves++
        }

        /** 마지막 저장이 끝난 뒤 미뤄둔 정리를 수행합니다. */
        internal fun release() {
            val remove = synchronized(this) { check(saves > 0); --saves == 0 && discarded }
            if (remove) { AiMediaFilter.end(listOf(source)); directory.deleteRecursively() }
        }

        /** 화면 종료는 저장 중인 결과의 삭제를 마지막 저장 완료까지 미룹니다. */
        internal fun discard() {
            val remove = synchronized(this) {
                if (discarded) false else { discarded = true; saves == 0 }
            }
            // UI 종료에서 큰 작업 사본을 지우느라 메인 스레드를 막지 않습니다.
            if (remove) { AiMediaFilter.end(listOf(source)); cleanup.launch { directory.deleteRecursively() } }
        }
    }

    /** 같은 이미지의 수정 중첩을 막고 서비스 종료는 복구 가능한 오류로 전달합니다. */
    suspend fun enhance(context: Context, source: String, option: Int, onEngine: (String) -> Unit = {}, progress: (Int) -> Unit): Preview {
        check(!com.plugin.compatibility.PluginMediaMetadataStore.isProcessingExcluded(source)) { "NOFACE 폴더는 이미지 보정 대상에서 제외됩니다." }
        check(Build.VERSION.SDK_INT >= 28) { "이미지 AI 보정은 Android 9 이상에서 지원합니다." }
        check(running.tryLock()) { "다른 이미지의 보정이 진행 중입니다." }
        val sampling = com.junkfood.seal.util.PreferenceUtil.run {
            EnhancementSampling.normalized(EnhancementSampling.KEY.getInt(EnhancementSampling.BILINEAR))
        }
        val directory = File(context.cacheDir, "image-ai/${java.util.UUID.randomUUID()}")
        AiMediaFilter.begin(listOf(source))
        try {
            check(!com.plugin.compatibility.TemporarySession.changing) { "저장소 전환이 진행 중입니다." }
            check(directory.mkdirs()) { "보정 임시 폴더를 만들 수 없습니다." }
            stage.value = "준비 · 원본 사본과 모델 복사"
            val google = com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.GOOGLE_FIRST.getBoolean() }
            val model = EnhancementModels.selectedSource(context)
            check(google || model.isNotBlank()) { "플러그인 설정에서 모델을 먼저 선택하세요." }
            val snapshot = File(directory, "source")
            EnhancementModels.stage(context, source, snapshot, 64 * 1024 * 1024)
            val customLiteRt = EnhancementModels.isLiteRt(context, model)
            if (model.isNotBlank()) EnhancementModels.stage(context, model, File(directory, if (customLiteRt) "model.tflite" else "model.onnx"))
            val backend = if (customLiteRt) "litert" else com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.BACKEND.getString("litert") }
            if (backend == "litert" && model == EnhancementModels.DEFAULT_MODEL)
                EnhancementModels.stage(context, EnhancementModels.LITERT_MODEL, File(directory, "model.tflite"))
            val name = if (source.startsWith("content://")) androidx.documentfile.provider.DocumentFile.fromSingleUri(context, Uri.parse(source))?.name.orEmpty()
                else File(source).name
            val format = name.substringAfterLast('.', "").lowercase()
            val result = withContext(Dispatchers.Main) { withTimeout(30 * 60_000L) {
                val request = Bundle().apply {
                    putString("directory", directory.absolutePath); putInt("option", option)
                    putInt("sampling", sampling)
                    putBoolean("accelerated", com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.ACCELERATED.getBoolean(true) });
                    putInt("cpu_threads", com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.CPU_THREADS.getInt(2) })
                    putString("backend", backend); putString("denoise", PreDenoise.mode(image = true)); putFloat("denoise_sigma", PreDenoise.sigma()); putBoolean("denoise_distance", PreDenoise.distanceWeight()); putFloat("sharpen", PostSharpen.amount())
                    putBoolean("google", google); putString("format", format)
                }
                var recovered: Bundle? = null
                var failure: WorkerStoppedException? = null
                for (route in EnhancementRules.recoveryRoutes(backend, request.getBoolean("accelerated"), customLiteRt || model == EnhancementModels.DEFAULT_MODEL,
                    com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.NPU_FIRST.getBoolean(false) })) {
                    ensureActive()
                    val attempt = Bundle(request).apply {
                        putBoolean("allow_npu", route == "automatic")
                        if (failure != null) putBoolean("google", false)
                        if (route == "cpu") {
                            putBoolean("accelerated", false)
                            if (failure != null) { if (!customLiteRt) putString("backend", "onnx"); putInt("cpu_threads", 1) }
                        }
                    }
                    val service = when (route) {
                        "gpu" -> EnhancementGpuService::class.java
                        "cpu" -> EnhancementCpuService::class.java
                        else -> EnhancementService::class.java
                    }
                    if (failure != null) {
                        progress(0)
                        onEngine(if (route == "gpu") "가속 프로세스 종료 · NPU를 제외한 GPU 경로로 재시도합니다."
                            else "가속 프로세스 종료 · CPU 1스레드로 재시도합니다.")
                    }
                    try {
                        recovered = execute(context, attempt, service, onEngine, progress)
                        if (failure != null) recovered.putString("engine", "프로세스 종료 후 재시도 · ${recovered.getString("engine")}")
                        break
                    } catch (stopped: WorkerStoppedException) {
                        failure = stopped
                        android.util.Log.w("ImageAi", "보정 작업 프로세스 종료: $route", stopped)
                    }
                }
                recovered ?: throw (failure ?: IllegalStateException("보정 결과를 받지 못했습니다."))
            } }
            return Preview(source, directory, withContext(Dispatchers.IO) { EnhancementModels.hash(snapshot) },
                result.getInt("width"), result.getInt("height"), format, result.getString("engine").orEmpty(), result.getInt("worker_pid"))
        } catch (failure: Throwable) { AiMediaFilter.end(listOf(source)); directory.deleteRecursively(); throw failure }
        finally { stage.value = ""; running.unlock() }
    }

    /** 화면의 코루틴 취소·바인더 단절·프로세스 강제 종료를 모두 연결 해제로 마무리합니다. */
    private suspend fun execute(context: Context, request: Bundle, serviceClass: Class<out EnhancementService>, onEngine: (String) -> Unit, progress: (Int) -> Unit): Bundle {
        var worker: Messenger? = null
        var bound = false
        lateinit var connection: ServiceConnection
        try {
            return suspendCancellableCoroutine { continuation ->
                val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
                    if (continuation.isActive) when (message.what) {
                        EnhancementService.PROGRESS -> {
                            // 진행률 콜백(알림 등)이 새 단계를 읽을 수 있게 단계를 먼저 갱신합니다.
                            message.data.getString("stage")?.let { stage.value = it }
                            progress(message.data.getInt("progress"))
                            message.data.getString("engine")?.let(onEngine)
                        }
                        EnhancementService.DONE -> continuation.resume(Bundle(message.data))
                        EnhancementService.FAILED -> continuation.resumeWithException(IllegalStateException(message.data.getString("error")))
                    }
                    true
                })
                connection = object : ServiceConnection {
                    /** 파일 경로만 전달하며 비트맵을 바인더에 싣지 않습니다. */
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        if (!continuation.isActive) return
                        worker = Messenger(service)
                        runCatching { worker!!.send(Message.obtain(null, EnhancementService.RUN).apply { data = request; replyTo = replies }) }
                            .onFailure { continuation.resumeWithException(it) }
                    }
                    /** 보정 프로세스 오류를 앱 전체 종료 대신 화면 오류로 전달합니다. */
                    override fun onServiceDisconnected(name: ComponentName) {
                        if (continuation.isActive) continuation.resumeWithException(WorkerStoppedException())
                    }
                    /** 프로세스 연결이 무효화된 경우 같은 복구 경로를 사용합니다. */
                    override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                    /** 서비스 바인더를 받지 못하면 무한 대기를 막습니다. */
                    override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                }
                continuation.invokeOnCancellation { runCatching { worker?.send(Message.obtain(null, EnhancementService.CANCEL)) } }
                bound = context.bindService(Intent(context, serviceClass), connection, Context.BIND_AUTO_CREATE)
                if (!bound && continuation.isActive) continuation.resumeWithException(IllegalStateException("보정 서비스를 시작할 수 없습니다."))
            }
        } finally { if (bound) runCatching { context.unbindService(connection) } }
    }

    /** 승인 전 결과 취소는 해당 요청의 캐시만 제거합니다. */
    fun discard(preview: Preview) { preview.discard() }
}
