package com.plugin.compatibility.enhancement

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** RIFE 가중치는 보간 요청 때만 받으며 APK에는 고정 버전·해시·라이선스만 포함합니다. */
object RifeModels {
    private val files = Mutex()
    /** 보간 모델은 사용자 사진·일반 업스케일 모델과 다른 내부 폴더에 보관합니다. */
    fun root(context: Context): File = File(context.filesDir, "video-ai-models/rife-v4.6")

    /** 원자적으로 설치한 모델의 두 파일이 모두 있어야 사용 가능합니다. */
    fun ready(context: Context): Boolean = root(context).let { File(it, "flownet.param").length() == 16_532L && File(it, "flownet.bin").length() == 10_614_320L }

    /** 사용자 확인 뒤 앱 내부 보간 가중치 두 파일만 삭제하고 다른 모델은 보존합니다. */
    suspend fun delete(context: Context) = withContext(Dispatchers.IO) {
        files.withLock {
        val directory = root(context).canonicalFile
        check(directory.path.startsWith(context.filesDir.canonicalPath + File.separator))
        for (name in listOf("flownet.param", "flownet.bin")) {
            val file = File(directory, name)
            check(file.canonicalFile.parentFile == directory)
            check(!file.exists() || file.delete()) { "보간 모델 파일을 삭제하지 못했습니다." }
        }
        }
    }

    /** 공식 고정 커밋의 두 파일만 검증한 뒤 선택한 동영상의 작업 사본을 만듭니다. */
    suspend fun prepare(context: Context, destination: File, progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        // 삭제와 설치·작업 복사를 직렬화해 .part 충돌과 원본 모델 소실을 방지합니다.
        files.withLock {
        val model = root(context)
        check(model.isDirectory || model.mkdirs()) { "보간 모델 폴더를 만들 수 없습니다." }
        val manifest = context.assets.open("video-ai/rife-v4.6.json").use { JSONObject(it.bufferedReader().readText()) }
        val entries = manifest.getJSONArray("files")
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index); val name = entry.getString("name")
            require(name in listOf("flownet.param", "flownet.bin"))
            val target = File(model, name)
            val valid = target.isFile && target.length() == entry.getLong("size") && EnhancementModels.hash(target) == entry.getString("sha256")
            if (!valid) {
                val temporary = File(model, "$name.part")
                progress("AI 프레임 보간 모델 준비 · ${index + 1}/${entries.length()}")
                try {
                    client.newCall(Request.Builder().url(manifest.getString("url") + name).build()).execute().use { response ->
                        check(response.isSuccessful) { "보간 모델 다운로드에 실패했습니다 (${response.code})." }
                        val body = checkNotNull(response.body); val digest = MessageDigest.getInstance("SHA-256"); var bytes = 0L
                        body.byteStream().use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                bytes += count; check(bytes <= entry.getLong("size")) { "보간 모델 크기가 다릅니다." }
                                digest.update(buffer, 0, count); output.write(buffer, 0, count)
                            }
                            check(bytes == entry.getLong("size") && digest.digest().joinToString("") { "%02x".format(it) } == entry.getString("sha256")) { "보간 모델 검증에 실패했습니다." }
                            output.fd.sync()
                        } }
                    }
                    check(temporary.renameTo(target)) { "보간 모델 설치에 실패했습니다." }
                } finally { temporary.delete() }
            }
        }
        check(destination.isDirectory || destination.mkdirs()) { "보간 작업 폴더를 만들 수 없습니다." }
        for (name in listOf("flownet.param", "flownet.bin")) File(model, name).copyTo(File(destination, name), overwrite = true)
        }
    }
}
