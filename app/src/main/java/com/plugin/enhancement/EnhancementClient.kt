package com.plugin.enhancement

import android.content.*
import android.net.Uri
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 바인딩 수명과 원본 사본을 관리하고 네이티브 추론은 다른 프로세스에 맡깁니다. */
object EnhancementClient {
    private val running = Mutex()

    /** 사용자 승인 전까지만 보관하는 결과와 원본 검증값입니다. */
    data class Preview(val source: String, val directory: File, val hash: String,
        val width: Int, val height: Int, val format: String, val engine: String, val workerPid: Int) {
        val image: File get() = File(directory, "preview.png")
    }

    /** 같은 이미지의 수정 중첩을 막고 서비스 종료는 복구 가능한 오류로 전달합니다. */
    suspend fun enhance(context: Context, source: String, option: Int, progress: (Int) -> Unit): Preview {
        check(Build.VERSION.SDK_INT >= 28) { "이미지 AI 보정은 Android 9 이상에서 지원합니다." }
        check(running.tryLock()) { "다른 이미지의 보정이 진행 중입니다." }
        val directory = File(context.cacheDir, "image-ai/${java.util.UUID.randomUUID()}")
        try {
            check(directory.mkdirs()) { "보정 임시 폴더를 만들 수 없습니다." }
            val google = com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.GOOGLE_FIRST.getBoolean() }
            val model = com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.SELECTED.getString() }
            check(google || model.isNotBlank()) { "플러그인 설정에서 모델을 먼저 선택하세요." }
            val snapshot = File(directory, "source")
            EnhancementModels.stage(context, source, snapshot, 64 * 1024 * 1024)
            if (model.isNotBlank()) EnhancementModels.stage(context, model, File(directory, "model.onnx"))
            val name = if (source.startsWith("content://")) androidx.documentfile.provider.DocumentFile.fromSingleUri(context, Uri.parse(source))?.name.orEmpty()
                else File(source).name
            val format = name.substringAfterLast('.', "").lowercase()
            val result = withContext(Dispatchers.Main) { withTimeout(30 * 60_000L) {
                execute(context, Bundle().apply {
                    putString("directory", directory.absolutePath); putInt("option", option)
                    putBoolean("accelerated", com.junkfood.seal.util.PreferenceUtil.run { EnhancementModels.ACCELERATED.getBoolean(true) });
                    putBoolean("google", google); putString("format", format)
                }, progress)
            } }
            return Preview(source, directory, withContext(Dispatchers.IO) { EnhancementModels.hash(snapshot) },
                result.getInt("width"), result.getInt("height"), format, result.getString("engine").orEmpty(), result.getInt("worker_pid"))
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
        finally { running.unlock() }
    }

    /** 화면의 코루틴 취소·바인더 단절·프로세스 강제 종료를 모두 연결 해제로 마무리합니다. */
    private suspend fun execute(context: Context, request: Bundle, progress: (Int) -> Unit): Bundle {
        var worker: Messenger? = null
        var bound = false
        lateinit var connection: ServiceConnection
        try {
            return suspendCancellableCoroutine { continuation ->
                val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
                    if (continuation.isActive) when (message.what) {
                        EnhancementService.PROGRESS -> progress(message.data.getInt("progress"))
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
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("보정 프로세스가 종료되었습니다. 낮은 해상도로 다시 시도하세요."))
                    }
                    /** 프로세스 연결이 무효화된 경우 같은 복구 경로를 사용합니다. */
                    override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                    /** 서비스 바인더를 받지 못하면 무한 대기를 막습니다. */
                    override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                }
                continuation.invokeOnCancellation { runCatching { worker?.send(Message.obtain(null, EnhancementService.CANCEL)) } }
                bound = context.bindService(Intent(context, EnhancementService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound && continuation.isActive) continuation.resumeWithException(IllegalStateException("보정 서비스를 시작할 수 없습니다."))
            }
        } finally { if (bound) runCatching { context.unbindService(connection) } }
    }

    /** 승인 전 결과 취소는 해당 요청의 캐시만 제거합니다. */
    fun discard(preview: Preview) { preview.directory.deleteRecursively() }
}
