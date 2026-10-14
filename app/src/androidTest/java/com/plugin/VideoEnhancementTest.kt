package com.plugin

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.plugin.compatibility.enhancement.*
import com.plugin.compatibility.copyPluginOptions
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 실제 QuickSRNet·Vulkan 모델과 영상 코덱·오디오의 연결을 작은 합성 영상으로 검증합니다. */
class VideoEnhancementTest {
    /** 프로세스 생성 전 준비 구간의 취소도 원본과 임시 파일 정리를 보장합니다. */
    @Test fun generalAv1CancellationKeepsSource(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val root = File(context.cacheDir, "cancel-av1-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = fixture(context, root, size = "640x360", rate = 30, seconds = 3)
            val hash = EnhancementModels.hash(source)
            val preferences = com.plugin.compatibility.createPluginPreferences().copyPluginOptions(mediaProcessing = true,
                transcodeAv1 = true, keepAv1Original = false, classifyMedia = false, av1Preset = 0)
            val task = "cancel-av1-${java.util.UUID.randomUUID()}"
            val result = async(kotlinx.coroutines.Dispatchers.IO) {
                com.plugin.compatibility.MediaLibrary.finish(listOf(source.path), preferences, task)
            }
            var canceled = false
            for (attempt in 0 until 100) {
                if (com.plugin.compatibility.MediaLibrary.cancel(task)) { canceled = true; break }
                kotlinx.coroutines.delay(5)
            }
            assertTrue("작업 취소 등록 실패", canceled)
            try { result.await(); fail("취소한 작업을 확정했습니다.") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(hash, EnhancementModels.hash(source))
            assertFalse(root.listFiles().orEmpty().any { it.name.endsWith("-av1.mp4") || it.name.startsWith(".") })
            assertFalse(com.plugin.compatibility.MediaLibrary.cancel(task))
        } finally { root.deleteRecursively() }
    }
    /** 일반 AV1 후처리도 하드웨어 디코딩 시도와 소프트웨어 대체 후 오디오·원본을 보존합니다. */
    @Test fun generalAv1ConversionPreservesOriginalAndAudio(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val root = File(context.cacheDir, "general-av1-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = fixture(context, root, size = "320x180", rate = 30)
            val second = source.copyTo(File(root, "second.mp4"))
            val hash = EnhancementModels.hash(source)
            val preferences = com.plugin.compatibility.createPluginPreferences().copyPluginOptions(mediaProcessing = true,
                transcodeAv1 = true, keepAv1Original = true, classifyMedia = false, av1Preset = 12, selectedFolder = null)
            val outputs = com.plugin.compatibility.MediaLibrary.finish(listOf(source.path, second.path), preferences,
                "general-av1-${java.util.UUID.randomUUID()}")
            assertEquals(4, outputs.size); assertTrue(source.path in outputs); assertTrue(second.path in outputs)
            for (output in outputs.filter { it != source.path && it != second.path }) {
            val result = VideoEnhancer.inspect(File(output))
            assertEquals("video/av01", result.format.getString(android.media.MediaFormat.KEY_MIME))
            assertEquals(320, result.width); assertEquals(180, result.height); assertEquals(1, result.audioTracks)
            assertEquals(30, result.frames.toInt()); assertEquals(hash, EnhancementModels.hash(source))
            assertEquals(hash, EnhancementModels.hash(second))
            }
        } finally { root.deleteRecursively() }
    }
    /** AVC 유지와 MP4에 기록할 수 없는 VP8의 AV1 대체를 실제 디코딩·저장으로 확인합니다. */
    @Test fun retainsCodecOrFallsBackToAv1(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 34)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val root = File(context.cacheDir, "video-codecs-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            for ((codec, expected) in listOf("libx264" to "video/avc", "libx265" to "video/hevc", "libvpx" to "video/av01")) {
                val work = File(root, codec).apply { mkdirs() }
                val source = fixture(context, work, codec = codec, size = if (codec == "libx265") "320x240" else "64x48")
                val hash = EnhancementModels.hash(source); source.copyTo(File(work, "source"))
                EnhancementModels.stage(context, EnhancementModels.SMALL_MODEL, File(work, "model.tflite"))
                val data = Bundle().apply { putInt("option", 2); putInt("cpu_threads", 1); putBoolean("original_codec", true) }
                VideoEnhancer.process(context, work, data) {}
                val output = VideoEnhancer.inspect(File(work, "preview.mp4"))
                assertEquals(expected, output.format.getString(android.media.MediaFormat.KEY_MIME))
                assertEquals(expected, data.getString("codec")); assertEquals(1, output.audioTracks)
                assertEquals(3, output.frames.toInt()); assertEquals(hash, EnhancementModels.hash(source))
                if (codec == "libx265" && android.os.Build.MODEL == "SM-S937N") {
                    val selected = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                        .first { it.name == data.getString("encoder") }
                    assertTrue("S25의 H.265 하드웨어 인코더를 우선하지 않았습니다.", selected.isHardwareAccelerated)
                }
            }
        } finally { VideoEnhancer.stop(); root.deleteRecursively() }
    }

    /** 같은 7초·30 FPS의 최소 설정과 선택한 GPU 보간을 측정하고 코덱별 시간·크기를 반환합니다. */
    @Test fun sevenSecondMinimumBenchmark(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val args = InstrumentationRegistry.getArguments()
        val size = args.getString("benchmark_size") ?: "320x180"
        val requestedFps = args.getString("benchmark_fps", "0").toInt()
        require(requestedFps in VideoRules.frameRates)
        require(Regex("[1-9][0-9]{1,3}x[1-9][0-9]{1,3}").matches(size))
        val root = File(context.cacheDir, "video-timing-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = fixture(context, root, size = size, rate = 30, seconds = 7)
            source.copyTo(File(root, "source"))
            EnhancementModels.stage(context, EnhancementModels.SMALL_MODEL, File(root, "model.tflite"))
            if (requestedFps > 30) {
                val supplied = args.getString("rife_models") ?: error("보간 모델 경로가 필요합니다.")
                val destination = File(root, "rife").apply { mkdirs() }
                for (name in listOf("flownet.param", "flownet.bin")) File(supplied, name).copyTo(File(destination, name))
            }
            for (preserve in if (requestedFps > 30) listOf(false) else listOf(false, true)) {
            val data = Bundle().apply {
                putInt("option", EnhancementRules.MINIMUM); putInt("cpu_threads", 2)
                putBoolean("accelerated", args.getString("benchmark_gpu", "true") == "true")
                putBoolean("original_codec", preserve)
                putInt("fps", requestedFps)
            }
            VideoEnhancer.process(context, root, data) {}
            val output = VideoEnhancer.inspect(File(root, "preview.mp4"))
            assertEquals(maxOf(30, requestedFps) * 7, output.frames.toInt()); assertEquals(1, output.audioTracks)
            assertEquals(maxOf(30, requestedFps).toDouble(), output.fps, 0.1)
            assertEquals(if (preserve) "video/avc" else "video/av01", data.getString("codec"))
            if (data.getBoolean("accelerated")) assertTrue(data.getString("engine").orEmpty().contains("GPU"))
            if (requestedFps > 30) assertTrue(data.getString("engine").orEmpty().contains("RIFE Vulkan GPU"))
            data.putLong("output_bytes", File(root, "preview.mp4").length()); data.putLong("source_bytes", source.length())
            instrumentation.sendStatus(0, Bundle(data).apply { remove("directory"); putString("benchmark_source", "$size · 7초 · 30 FPS") })
            }
        } finally { VideoEnhancer.stop(); root.deleteRecursively() }
    }
    /** CPU 대체 없이 실제 휴대폰의 LiteRT GPU와 Vulkan 보간 결과를 확인합니다. */
    @Test fun phoneGpuModelsAndInterpolation(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val root = File(context.cacheDir, "phone-gpu-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val first = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val second = first.copy(Bitmap.Config.ARGB_8888, true).apply { setPixel(32, 24, Color.WHITE) }
        try {
            for (model in listOf(EnhancementModels.SMALL_MODEL, EnhancementModels.MEDIUM_MODEL)) {
                val file = File(root, "model.tflite")
                EnhancementModels.stage(context, model, file)
                LiteRtSession(context, file.path, true, 1, false).use { session ->
                    assertTrue(session.route().contains("GPU"))
                    val start = android.os.SystemClock.elapsedRealtime()
                    val result = session.infer(FloatArray(128 * 128 * 3) { 0.5f })
                    assertEquals(512 * 512 * 3, result.size)
                    assertTrue(result.all { it.isFinite() })
                    android.util.Log.i("PhoneGpuCheck", "$model: ${session.route()}, ${android.os.SystemClock.elapsedRealtime() - start}ms")
                }
            }
            val models = InstrumentationRegistry.getArguments().getString("rife_models") ?: error("보간 모델 경로가 필요합니다.")
            RifeSession(context, models).use { session ->
                val result = session.interpolate(first, second, 0.5f)
                try { assertFalse(result.sameAs(first)); assertFalse(result.sameAs(second)) }
                finally { result.recycle() }
                android.util.Log.i("PhoneGpuCheck", "RIFE Vulkan GPU: 실제 보간 성공")
                val a = first.copy(Bitmap.Config.ARGB_8888, false); val b = second.copy(Bitmap.Config.ARGB_8888, false)
                try {
                    RifeSession(context, models).use { fresh ->
                        for ((left, right) in listOf(a to b, b to a, a to b)) {
                            val mutableLeft = left.copy(Bitmap.Config.ARGB_8888, true)
                            val mutableRight = right.copy(Bitmap.Config.ARGB_8888, true)
                            val expected = fresh.interpolate(mutableLeft, mutableRight, 0.5f)
                            val actual = session.interpolate(left, right, 0.5f)
                            try { assertTrue("불변 프레임 버퍼 재사용이 결과를 바꿨습니다.", actual.sameAs(expected)) }
                            finally { mutableLeft.recycle(); mutableRight.recycle(); expected.recycle(); actual.recycle() }
                        }
                    }
                } finally { a.recycle(); b.recycle() }
            }
        } finally { first.recycle(); second.recycle(); root.deleteRecursively() }
    }
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
                assertEquals(result.codec, com.plugin.compatibility.PluginMediaMetadataStore.get(output)?.quality?.codec)
                assertEquals(VideoEnhancer.inspect(File(output)).format.getString(android.media.MediaFormat.KEY_MIME), result.codec)
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
    private fun fixture(context: android.content.Context, root: File, vfr: Boolean = false,
        codec: String = "libx264", size: String = "64x48", rate: Int = 3, seconds: Int = 1): File {
        FFmpeg.getInstance().init(context)
        val source = File(root, if (codec == "libvpx") "fixture.mkv" else "fixture.mp4")
        val command = listOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").path, "-y", "-nostdin", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=$size:rate=$rate:duration=$seconds", "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds",
            "-c:v", codec, "-pix_fmt", "yuv420p", "-bf", "0", "-c:a", "aac", "-shortest") +
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
            val gpu = InstrumentationRegistry.getArguments().getString("rife_gpu") == "true"
            for (fps in listOf(0, 24)) {
                val work = File(root, "fps-$fps").apply { mkdirs() }; source.copyTo(File(work, "source"))
                EnhancementModels.stage(context, EnhancementModels.SMALL_MODEL, File(work, "model.tflite"))
                if (fps > 0) {
                    val supplied = InstrumentationRegistry.getArguments().getString("rife_models") ?: error("보간 검사 모델 경로가 필요합니다.")
                    val destination = File(work, "rife").apply { mkdirs() }
                    for (name in listOf("flownet.param", "flownet.bin")) File(supplied, name).copyTo(File(destination, name))
                }
                val data = Bundle().apply {
                    putInt("option", 2); putInt("fps", fps); putBoolean("accelerated", gpu); putInt("cpu_threads", 1)
                    putInt("sampling", EnhancementSampling.BILINEAR); putBoolean("rife_reference_test", !gpu)
                }
                VideoEnhancer.process(context, work, data) {}
                if (gpu) assertTrue(data.getString("engine").orEmpty().contains("GPU"))
                if (fps > 0) assertTrue(data.getString("engine").orEmpty().contains(if (gpu) "RIFE Vulkan GPU" else "RIFE CPU 기준 검사"))
                val output = VideoEnhancer.inspect(File(work, "preview.mp4"))
                assertEquals(128, output.width); assertEquals(96, output.height); assertEquals(1, output.audioTracks)
                assertEquals(if (fps == 0) original.fps else fps.toDouble(), output.fps, 0.1)
                assertEquals(original.duration.toDouble(), output.duration.toDouble(), 150_000.0)
                assertEquals(hash, EnhancementModels.hash(source))
            }
        } finally { VideoEnhancer.stop(); root.deleteRecursively() }
    }
}
