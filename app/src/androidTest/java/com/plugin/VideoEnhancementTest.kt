package com.plugin

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.plugin.compatibility.enhancement.*
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 실제 QuickSRNet·Vulkan 모델과 영상 코덱·오디오의 연결을 작은 합성 영상으로 검증합니다. */
class VideoEnhancementTest {
    /** 실제 서비스·전경 알림·파일 복제·변경된 원본 거부를 다운로드 없는 전용 앱에서 확인합니다. */
    @Test fun videoServiceAndSafeSave(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val root = File(context.cacheDir, "video-service-check-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val key = EnhancementModels.ACCELERATED; val existed = kv.containsKey(key); val originalSetting = kv.decodeBool(key, true)
        var preview: EnhancementClient.Preview? = null
        try {
            kv.encode(key, false)
            val source = fixture(context, root); val hash = EnhancementModels.hash(source)
            androidx.test.core.app.ActivityScenario.launch(com.plugin.ui.PluginMainActivity::class.java).use {
                val result = EnhancementClient.enhanceVideo(context, source.path, 2, EnhancementModels.SMALL_MODEL, 0) {}
                preview = result
                assertNotEquals(android.os.Process.myPid(), result.workerPid); assertEquals(128, result.width)
                assertEquals("mp4", result.format); assertTrue(EnhancementFiles.canReplace(result))
                val output = EnhancementFiles.save(context, result, false)
                assertTrue(output.endsWith(".mp4")); assertEquals(hash, EnhancementModels.hash(source))
                assertEquals(1, VideoEnhancer.inspect(File(output)).audioTracks)
                assertEquals("video/avc", com.plugin.compatibility.PluginMediaMetadataStore.get(output)?.quality?.codec)
                source.appendBytes(byteArrayOf(0)); val changed = EnhancementModels.hash(source)
                try { EnhancementFiles.save(context, result, true); fail("수정된 원본을 교체했습니다.") }
                catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("원본이 변경")) }
                assertEquals(changed, EnhancementModels.hash(source))
                com.plugin.compatibility.PluginMediaMetadataStore.delete(output)
            }
        } finally {
            preview?.let(EnhancementClient::discard)
            if (existed) kv.encode(key, originalSetting) else kv.removeValueForKey(key)
            root.deleteRecursively()
        }
    }

    /** 합성 SDR 영상·오디오를 만들어 사용자 파일을 테스트 입력으로 사용하지 않습니다. */
    private fun fixture(context: android.content.Context, root: File, vfr: Boolean = false): File {
        FFmpeg.getInstance().init(context)
        val source = File(root, "fixture.mp4")
        val command = listOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").path, "-y", "-nostdin", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=3:duration=1", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-bf", "0", "-c:a", "aac", "-shortest") +
            (if (vfr) listOf("-vf", "setpts=N/6/TB", "-fps_mode", "vfr", "-enc_time_base", "1/1000000") else emptyList()) + source.path
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python", "aria2c").joinToString(":") {
                File(context.noBackupFilesDir, "youtubedl-android/packages/$it/usr/lib").path
            }
        }.start()
        val error = process.inputStream.bufferedReader().readText(); assertEquals(error, 0, process.waitFor())
        return source
    }
    /** 에뮬레이터의 CPU Vulkan을 사용자 앱의 GPU 경로로 통과시키지 않습니다. */
    @Test fun productionRejectsCpuVulkan() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE == "ranchu")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val supplied = InstrumentationRegistry.getArguments().getString("rife_models") ?: error("보간 검사 모델 경로가 필요합니다.")
        try { RifeSession(context, supplied).close(); fail("CPU Vulkan을 GPU로 사용했습니다.") }
        catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("Vulkan GPU가 없습니다")) }
    }
    /** 두 공식 경량 모델의 정적 RGB 계약과 실제 CPU 추론을 확인합니다. */
    @Test fun quickSrSmallAndMedium(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "quicksr-check-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            for (model in listOf(EnhancementModels.SMALL_MODEL, EnhancementModels.MEDIUM_MODEL)) {
                val file = File(root, "model.tflite"); EnhancementModels.stage(context, model, file)
                LiteRtSession(context, file.path, false, 1, false).use { session ->
                    assertEquals(128, session.inputWidth()); assertEquals(512, session.outputWidth())
                    val result = session.infer(FloatArray(128 * 128 * 3) { 0.5f })
                    assertEquals(512 * 512 * 3, result.size)
                    assertTrue(result.all { it.isFinite() && it in -0.1f..1.1f })
                    assertEquals(0.5, result.average(), 0.02)
                }
            }
        } finally { root.deleteRecursively() }
    }

    /** 공식 가중치·JNI·보간 결과의 CPU 기준 검사를 GPU 실행 성공과 구분합니다. */
    @Test fun rifeModelReferenceContract() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val supplied = InstrumentationRegistry.getArguments().getString("rife_models") ?: error("보간 검사 모델 경로가 필요합니다.")
        val first = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(70, 100, 150)) }
        val second = first.copy(Bitmap.Config.ARGB_8888, true).apply { setPixel(32, 24, Color.WHITE) }
        try {
            RifeSession(context, supplied, true).use { session ->
                val result = session.interpolate(first, second, 0.5f)
                try {
                    assertEquals(first.width, result.width); assertEquals(first.height, result.height)
                    assertFalse(first.isRecycled); assertFalse(second.isRecycled)
                    assertTrue("보간 결과가 원본 복제입니다.", !result.sameAs(first) && !result.sameAs(second))
                } finally { result.recycle() }
            }
        } finally { first.recycle(); second.recycle() }
    }

    /** 프레임 유지와 실제 보간 모두 해상도·시각·오디오·원본 바이트를 보존합니다. */
    @Test fun videoFramesAudioAndOriginal(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val root = File(context.cacheDir, "video-check-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = fixture(context, root, vfr = true)
            val hash = EnhancementModels.hash(source); val original = VideoEnhancer.inspect(source)
            for (fps in listOf(0, 24)) {
                val work = File(root, "fps-$fps").apply { mkdirs() }; source.copyTo(File(work, "source"))
                EnhancementModels.stage(context, EnhancementModels.SMALL_MODEL, File(work, "model.tflite"))
                if (fps > 0) {
                    val supplied = InstrumentationRegistry.getArguments().getString("rife_models") ?: error("보간 검사 모델 경로가 필요합니다.")
                    val destination = File(work, "rife").apply { mkdirs() }
                    for (name in listOf("flownet.param", "flownet.bin")) File(supplied, name).copyTo(File(destination, name))
                }
                val data = Bundle().apply {
                    putInt("option", 2); putInt("fps", fps); putBoolean("accelerated", false); putInt("cpu_threads", 1)
                    putInt("sampling", EnhancementSampling.BILINEAR); putBoolean("rife_reference_test", true)
                }
                VideoEnhancer.process(context, work, data) {}
                if (fps > 0) assertTrue(data.getString("engine").orEmpty().contains("RIFE CPU 기준 검사"))
                val output = VideoEnhancer.inspect(File(work, "preview.mp4"))
                assertEquals(128, output.width); assertEquals(96, output.height); assertEquals(1, output.audioTracks)
                assertEquals(if (fps == 0) original.fps else fps.toDouble(), output.fps, 0.1)
                assertEquals(original.duration.toDouble(), output.duration.toDouble(), 150_000.0)
                assertEquals(hash, EnhancementModels.hash(source))
            }
        } finally { VideoEnhancer.stop(); root.deleteRecursively() }
    }
}
