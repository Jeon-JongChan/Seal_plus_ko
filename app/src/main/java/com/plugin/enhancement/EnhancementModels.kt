package com.plugin.enhancement

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App
import com.junkfood.seal.util.PreferenceUtil.getString
import com.plugin.compatibility.createPluginPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 모델은 APK에 포함하지 않고 공개 폴더 또는 사용자가 가져온 파일에서 읽습니다. */
object EnhancementModels {
    const val SELECTED = "image_ai_model"
    const val ACCELERATED = "image_ai_accelerated"
    const val GOOGLE_FIRST = "image_ai_google_first"

    /** 추천 모델의 고정 버전·해시와 안내 정보를 보관합니다. */
    data class Recommendation(val name: String, val url: String, val sha256: String, val description: String)
    /** 일반 파일과 SAF 모델을 같은 목록에서 선택합니다. */
    data class Model(val name: String, val source: String, val bytes: Long)

    val recommendations = listOf(
        Recommendation("realesr-general-x4v3.onnx",
            "https://huggingface.co/skillsafe-ai/realesr-general-x4v3/resolve/042a40bc4c918349ad3e2e607a68ae509a4c27b5/model.onnx",
            "a946f7a9397021b9b6b7e71df3d2821b04cc09ff244423b7ca79cb191ce4a00e",
            "일반 사진용 · 4배 모델 · 약 4.9 MB · BSD-3-Clause"),
        Recommendation("real_esrgan_x2.onnx",
            "https://huggingface.co/SceneWorks/real-esrgan-onnx/resolve/09f741bac80a246b407da3ee902bf5f3291b602f/real_esrgan_x2.onnx",
            "7115ba92e8a1bfa63d68558ef006ef3d91273a068d321b1439f8bb1c9179002c",
            "사진 복원용 · 2배 모델 · 약 67 MB · BSD-3-Clause"),
    )
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** 앱 내부 저장소는 폴더 권한 없이 가져온 모델도 사용할 수 있게 합니다. */
    fun internalRoot(context: Context): File = File(context.filesDir, "image-ai-models").apply { mkdirs() }

    /** 일반 다운로드 디렉토리의 models 위치를 안내합니다. */
    fun publicRoot(): File = File(App.videoDownloadDir, "models")

    /** 공개 폴더·SD 카드·앱 내부 모델을 읽되 접근 오류는 사용자에게 전달합니다. */
    suspend fun scan(context: Context): List<Model> = withContext(Dispatchers.IO) {
        val internal = internalRoot(context).listFiles().orEmpty().filter { it.extension.equals("onnx", true) }
            .map { Model(it.name, it.absolutePath, it.length()) }
        val preferences = createPluginPreferences()
        val external = if (preferences.sdcard) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(preferences.sdcardUri))
            check(root?.canRead() == true) { "모델 폴더 권한이 필요합니다. 파일 가져오기를 사용할 수도 있습니다." }
            root!!.findFile("models")?.listFiles().orEmpty().filter { it.isFile && it.name.orEmpty().endsWith(".onnx", true) }
                .map { Model(it.name!!, it.uri.toString(), it.length()) }
        } else {
            val root = publicRoot()
            if (root.exists()) check(root.canRead()) { "models 폴더를 읽을 권한이 필요합니다." }
            root.listFiles().orEmpty().filter { it.isFile && it.extension.equals("onnx", true) }
                .map { Model(it.name, it.absolutePath, it.length()) }
        }
        (external + internal).distinctBy { it.source }.sortedBy { it.name }
    }

    /** SAF와 일반 경로를 같은 읽기 경로로 처리합니다. */
    fun open(context: Context, source: String): InputStream =
        if (source.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(source))
            ?: error("파일을 열 수 없습니다.")
        else File(if (source.startsWith("file://")) Uri.parse(source).path!! else source).inputStream()

    /** 선택한 단일 파일 모델을 작업 전용 사본으로 만들고 파일 크기를 검증합니다. */
    suspend fun stage(context: Context, source: String, destination: File, limit: Long = EnhancementRules.MAX_MODEL_BYTES) =
        withContext(Dispatchers.IO) {
            open(context, source).use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= limit) { "파일이 너무 큽니다. 더 작은 모델이나 이미지를 사용하세요." }
                        output.write(buffer, 0, count)
                    }
                    check(total > 0) { "빈 파일은 사용할 수 없습니다." }
                }
            }
        }

    /** 사용자 선택 파일은 이름을 검증한 뒤 내부 저장소에 보존합니다. */
    suspend fun import(context: Context, uri: Uri): Model = withContext(Dispatchers.IO) {
        val name = DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
        require(name.endsWith(".onnx", true)) { "단일 파일 ONNX 모델(.onnx)만 지원합니다. .pth·NCNN 모델은 변환이 필요합니다." }
        val output = File(internalRoot(context), java.util.UUID.randomUUID().toString() + "-" + safeName(name))
        val temp = File(output.parentFile, output.name + ".part")
        try {
            stage(context, uri.toString(), temp)
            // 가져오기가 끝난 모델만 목록에 노출하여 불완전한 모델 선택을 막습니다.
            check(temp.renameTo(output)) { "모델 가져오기를 완료할 수 없습니다." }
            Model(name, output.absolutePath, output.length())
        } finally { temp.delete() }
    }

    /** 추천 해시 또는 사용자 파일 링크를 확인하고 불완전한 파일은 목록에 노출하지 않습니다. */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun download(context: Context, url: String, recommendation: Recommendation? = null,
        progress: (String) -> Unit): Model = withContext(Dispatchers.IO) {
        val resolved = EnhancementRules.modelUrl(url)
        val name = safeName(recommendation?.name ?: Uri.parse(resolved).lastPathSegment.orEmpty())
        val temp = File.createTempFile("model-", ".part", internalRoot(context))
        try {
            val call = client.newCall(Request.Builder().url(resolved).build())
            val task = currentCoroutineContext()[kotlinx.coroutines.Job]!!
            val cancel = task.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { if (task.isCancelled) call.cancel() }
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { "모델 다운로드 실패 (${response.code}). 로그인 필요 모델은 파일 가져오기를 사용하세요." }
                    val body = response.body ?: error("모델 응답이 비어 있습니다.")
                    check(body.contentLength() <= EnhancementRules.MAX_MODEL_BYTES) { "모델 파일이 너무 큽니다." }
                    body.byteStream().use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer); if (read < 0) break
                            total += read; check(total <= EnhancementRules.MAX_MODEL_BYTES) { "모델 파일이 너무 큽니다." }
                            output.write(buffer, 0, read); progress("모델 다운로드: ${total / 1024} KB")
                        }
                        check(total > 0 && (body.contentLength() < 0 || total == body.contentLength())) { "모델 다운로드가 완료되지 않았습니다." }
                    } }
                }
            } finally { cancel.dispose() }
            if (recommendation != null) check(hash(temp) == recommendation.sha256) { "모델 검증 해시가 일치하지 않습니다." }
            val preferences = createPluginPreferences()
            if (preferences.sdcard) {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(preferences.sdcardUri))
                if (root?.canWrite() == true) {
                    val folder = root.findFile("models") ?: root.createDirectory("models") ?: error("models 폴더를 만들 수 없습니다.")
                    check(folder.isDirectory && folder.findFile(name) == null) { "같은 모델이 있거나 모델 폴더가 올바르지 않습니다." }
                    val document = folder.createFile("application/octet-stream", ".model-${java.util.UUID.randomUUID()}.part") ?: error("모델 파일을 만들 수 없습니다.")
                    try {
                        context.contentResolver.openFileDescriptor(document.uri, "w")!!.use { descriptor ->
                            java.io.FileOutputStream(descriptor.fileDescriptor).use { output ->
                                temp.inputStream().use { check(it.copyTo(output) == temp.length()) }; output.fd.sync()
                            }
                        }
                        check(document.length() == temp.length() && document.renameTo(name)) { "모델 파일 저장을 확인할 수 없습니다." }
                        return@withContext Model(name, document.uri.toString(), temp.length())
                    } catch (failure: Throwable) { document.delete(); throw failure }
                }
            }
            // 공개 폴더를 쓸 수 없는 기기에서도 내부 저장소와 SAF 가져오기를 제공합니다.
            val public = publicRoot()
            val destinationRoot = if (!createPluginPreferences().sdcard &&
                (public.isDirectory || public.mkdirs()) && public.canWrite()) public else internalRoot(context)
            val output = File(destinationRoot, name)
            check(!output.exists()) { "같은 모델 파일이 있습니다. 새로고침 후 해당 모델을 선택하세요." }
            android.util.AtomicFile(output).let { atomic ->
                val stream = atomic.startWrite()
                try { temp.inputStream().use { it.copyTo(stream) }; atomic.finishWrite(stream) }
                catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
            }
            Model(name, output.absolutePath, output.length())
        } finally { temp.delete() }
    }

    /** 입력 이름의 경로 구분자를 제거하고 파일 경계를 유지합니다. */
    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(160).also {
        require(it.endsWith(".onnx", true) && !it.startsWith('.')) { "잘못된 모델 파일명입니다." }
    }

    /** 교체 직전 원본 변경과 추천 모델의 무결성을 확인합니다. */
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
