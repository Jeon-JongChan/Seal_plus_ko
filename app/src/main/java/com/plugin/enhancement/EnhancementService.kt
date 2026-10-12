package com.plugin.enhancement

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.os.*
import kotlinx.coroutines.*
import java.io.File

/** 외부에 노출하지 않은 별도 프로세스에서 이미지 하나만 처리합니다. */
class EnhancementService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == CANCEL) {
            // 네이티브 추론도 즉시 끝내되 다운로드 프로세스에는 영향을 주지 않습니다.
            Process.killProcess(Process.myPid())
        } else if (message.what == RUN && job?.isActive != true) {
            val data = Bundle(message.data); val reply = message.replyTo
            job = scope.launch {
                try {
                    process(data) { percent -> send(reply, PROGRESS, Bundle().apply { putInt("progress", percent) }) }
                    send(reply, DONE, data)
                } catch (failure: Throwable) {
                    send(reply, FAILED, Bundle().apply { putString("error", if (failure is OutOfMemoryError)
                        "보정 메모리가 부족합니다. 더 낮은 해상도를 선택하세요." else failure.message ?: "이미지 보정에 실패했습니다.") })
                }
            }
        } else if (message.what == RUN) send(message.replyTo, FAILED, Bundle().apply { putString("error", "이미지 보정 작업이 실행 중입니다.") })
        true
    })

    /** 화면에서 바인딩한 앱 내부 요청만 받습니다. */
    override fun onBind(intent: Intent): IBinder = messenger.binder

    /** 화면 취소·종료 시 프로세스를 정리해 네이티브 메모리도 반환합니다. */
    override fun onUnbind(intent: Intent): Boolean {
        scope.cancel()
        Process.killProcess(Process.myPid())
        return false
    }

    /** 확정되지 않은 출력은 캐시 아래에서만 생성하고 원본에는 쓰지 않습니다. */
    private suspend fun process(data: Bundle, progress: (Int) -> Unit) {
        val root = File(cacheDir, "image-ai").canonicalFile
        val directory = File(data.getString("directory") ?: error("작업 경로가 없습니다.")).canonicalFile
        require(directory.path.startsWith(root.path + File.separator)) { "잘못된 보정 작업 경로입니다." }
        val sourceFile = File(directory, "source")
        val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(sourceFile)) { decoder, info, _ ->
            check(info.size.width.toLong() * info.size.height <= EnhancementRules.MAX_PIXELS) { "원본 이미지가 너무 큽니다." }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }
        val source = (decoded as? BitmapDrawable)?.bitmap ?: error("움직이는 이미지는 보정하지 않습니다.")
        var output: Bitmap? = null
        try {
            val target = EnhancementRules.target(source.width, source.height, data.getInt("option"))
            check((source.width.toLong() * source.height + target.first.toLong() * target.second) * 4 + 32 * 1024 * 1024 <=
                Runtime.getRuntime().maxMemory() * 7 / 10) { "이 기기의 메모리로 처리하기에는 큽니다. 더 낮은 배율을 선택하세요." }
            val model = File(directory, "model.onnx")
            val googleAvailable = if (data.getBoolean("google")) {
                try { GoogleEnhancer.availability(this).let { it.first && it.second } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { false }
            } else false
            if (googleAvailable) {
                try {
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
                            else Bitmap.createScaledBitmap(current, target.first, target.second, true).also { if (current !== source) current.recycle() }
                        data.putString("engine", "Google")
                    } catch (failure: Throwable) { if (current !== source) current.recycle(); throw failure }
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (!model.isFile) throw failure
                }
            }
            if (output == null) {
                check(model.isFile) { "사용할 모델을 먼저 선택하거나 Google 모듈을 설치하세요." }
                output = OnnxEnhancer.process(source, model.absolutePath, target, progress, data.getBoolean("accelerated")) { data.putString("engine", it) }
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
