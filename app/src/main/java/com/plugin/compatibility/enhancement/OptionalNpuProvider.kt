package com.plugin.compatibility.enhancement

import android.content.Context
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.NpuAcceleratorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** 공식 NPU 제공자 계약을 사용하며 큰 SDK 라이브러리는 명시적 선택 후에만 설치합니다. */
class OptionalNpuProvider(private val context: Context) : NpuAcceleratorProvider {
    private val builtin = BuiltinNpuAcceleratorProvider(context)
    private val root = File(context.filesDir, "ai-runtimes/qualcomm-2.50.0.260828")

    /** 기존 공개 호환 검사와 패키징한 Android ABI 범위를 따릅니다. */
    override fun isDeviceSupported(): Boolean = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && builtin.isDeviceSupported()

    /** 설치 완료 표시는 모든 라이브러리의 고정 SHA256 검사 후에만 생성합니다. */
    override fun isLibraryReady(): Boolean = File(root, "ready").isFile

    /** 준비하지 않은 NPU는 호출자가 기존 GPU·CPU 경로로 대체할 수 있게 거부합니다. */
    override fun getLibraryDir(): String {
        check(isLibraryReady()) { "NPU 런타임을 플러그인 설정에서 먼저 다운로드하세요." }
        return root.absolutePath
    }

    /** 공식 인터페이스의 설치 요청도 같은 검증 경로를 재사용합니다. */
    override suspend fun downloadLibrary() { install {} }

    /** 2.6GB SDK 전체 대신 검증된 압축 엔트리의 범위만 읽어 설치합니다. */
    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (isLibraryReady()) return@withContext
        check(root.isDirectory || root.mkdirs()) { "NPU 런타임 폴더를 만들 수 없습니다." }
        val manifest = context.assets.open("image-ai/qualcomm-runtime.json").use { JSONObject(it.bufferedReader().readText()) }
        val entries = manifest.getJSONArray("files")
        check(root.usableSpace > 140L * 1024 * 1024) { "NPU 런타임을 설치할 저장 공간이 부족합니다." }
        val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
        for (index in 0 until entries.length()) {
            currentCoroutineContext().ensureActive()
            val entry = entries.getJSONObject(index)
            val name = entry.getString("name")
            require(name.matches(Regex("libQnn[A-Za-z0-9]+\\.so")))
            val start = entry.getLong("start"); val size = entry.getLong("compressed"); val end = start + size - 1
            require(size in 1..150L * 1024 * 1024 && entry.getLong("size") in 1..150L * 1024 * 1024)
            val target = File(root, name); val partial = File(root, "$name.part")
            val request = Request.Builder().url(manifest.getString("url") + "?range=$start-$end")
                .header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity").build()
            progress("NPU 런타임 준비 · ${index + 1}/${entries.length()}")
            try {
                client.newCall(request).execute().use { response ->
                    check(response.code == 206 && response.header("Content-Range") == "bytes $start-$end/${manifest.getLong("total")}") {
                        "공식 SDK 서버가 범위 다운로드를 지원하지 않습니다. GPU를 사용하거나 나중에 다시 시도하세요."
                    }
                    val body = checkNotNull(response.body)
                    check(body.contentLength() == size) { "NPU 런타임 응답 크기가 다릅니다." }
                    body.byteStream().use { compressed ->
                        val inflater = Inflater(true)
                        try {
                            val input = when (entry.getInt("method")) { 8 -> InflaterInputStream(compressed, inflater); 0 -> compressed; else -> error("지원하지 않는 SDK 압축 방식입니다.") }
                            val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer); if (count < 0) break
                                    total += count; check(total <= entry.getLong("size")) { "NPU 파일의 크기 제한을 초과했습니다." }
                                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                                }
                                check(total == entry.getLong("size") && digest.digest().joinToString("") { "%02x".format(it) } == entry.getString("sha256")) { "NPU 런타임 무결성 검증에 실패했습니다." }
                                output.fd.sync()
                            }
                        } finally { inflater.end() }
                    }
                }
                check(partial.renameTo(target)) { "NPU 라이브러리 설치를 완료할 수 없습니다." }
            } finally { partial.delete() }
        }
        // LiteRT 컴파일·실행 플러그인도 SDK와 같은 탐색 디렉터리에 놓습니다.
        for (name in listOf("libLiteRtCompilerPlugin_Qualcomm.so", "libLiteRtDispatch_Qualcomm.so")) {
            val source = File(context.applicationInfo.nativeLibraryDir, name)
            check(source.isFile) { "이 APK는 Qualcomm NPU를 지원하지 않습니다." }
            source.copyTo(File(root, name), overwrite = true)
        }
        File(root, "ready").writeText("2.50.0.260828")
        progress("NPU 런타임 준비 완료")
    }
}
