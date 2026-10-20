package com.plugin

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.plugin.compatibility.enhancement.EnhancementModels
import com.plugin.compatibility.enhancement.VideoEnhancer
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 16차: H.264 선택·원본 유지 인코딩이 여러 해상도·원본 코덱에서 실패하지 않는지 검사합니다. */
class Revision16Test {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 원본 코덱·크기·코덱 선택 조합을 모두 실행하고 실패한 조합을 한 번에 보고합니다. */
    @Test fun h264MatrixEncodes(): Unit = runBlocking {
        check(context.packageName.endsWith(".devicecheck"))
        val root = File(context.cacheDir, "rev16-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val failures = mutableListOf<String>()
        val report = StringBuilder()
        try {
            // (원본 코덱, 크기, 선택 코덱, 원본 유지)
            val cases = listOf(
                listOf("libx264", "64x48", "h264", false), listOf("libx264", "322x242", "h264", false),
                listOf("libx264", "426x240", "h264", false), listOf("libx264", "360x640", "h264", false),
                listOf("libx264", "640x360", "h264", false), listOf("libx264", "426x240", "auto", true),
                listOf("libx264", "322x242", "auto", true), listOf("libx264", "360x640", "original", true),
                listOf("libx265", "320x240", "h264", false), listOf("libvpx", "320x240", "h264", false),
                // 업스케일 결과가 단일 인코더 한계를 넘는 경우(예: 2560x1440): 코덱 대체로 성공해야 합니다.
                listOf("libx264", "1280x720", "h264", false), listOf("libx264", "1280x720", "auto", true),
                listOf("libx264", "1280x720", "original", true),
                // 원본 H.265를 유지하는 경로: 지원 기기에서 H.265로 저장돼야 합니다.
                listOf("libx265", "320x240", "original", true), listOf("libx265", "320x240", "h265", false),
            )
            for ((index, case) in cases.withIndex()) {
                val (codec, size, selected, preserve) = case
                val work = File(root, "case$index").apply { mkdirs() }
                val label = "$codec $size 선택=$selected 원본유지=$preserve"
                try {
                    val source = fixture(work, codec as String, size as String)
                    source.copyTo(File(work, "source"))
                    EnhancementModels.stage(context, EnhancementModels.SMALL_MODEL, File(work, "model.tflite"))
                    val data = Bundle().apply {
                        putInt("option", 2); putInt("cpu_threads", 2); putBoolean("original_codec", preserve as Boolean)
                        putString("video_codec", selected as String)
                    }
                    VideoEnhancer.process(context, work, data) {}
                    val output = VideoEnhancer.inspect(File(work, "preview.mp4"))
                    // H.265 유지·선택은 기기에 출력 크기(640x480)를 지원하는 HEVC 인코더가 있을 때만 요구합니다.
                    // (에뮬레이터의 c2.android.hevc.encoder는 이 크기를 지원하지 않아 대체가 정상 동작입니다.)
                    val hevcFits = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any {
                        it.isEncoder && "video/hevc" in it.supportedTypes &&
                            runCatching { it.getCapabilitiesForType("video/hevc").videoCapabilities.isSizeSupported(640, 480) }.getOrDefault(false)
                    }
                    if (hevcFits && (selected == "h265" || codec == "libx265" && selected == "original"))
                        check(output.format.getString(android.media.MediaFormat.KEY_MIME) == "video/hevc") { "H.265가 유지되지 않았습니다: ${data.getString("engine")}" }
                    report.append("OK  $label → ${output.format.getString(android.media.MediaFormat.KEY_MIME)} ${output.width}x${output.height} 인코더=${data.getString("encoder")} 엔진=${data.getString("engine")?.replace('\n', '|')}\n")
                } catch (failure: Throwable) {
                    failures += "$label: ${failure::class.simpleName} ${failure.message}"
                    report.append("ERR $label → ${failure::class.simpleName} ${failure.message}\n")
                } finally { VideoEnhancer.stop() }
            }
            File(context.cacheDir, "rev16-report.txt").writeText(report.toString())
            android.util.Log.i("Revision16", "\n$report")
            assertTrue("H.264 조합 실패:\n" + failures.joinToString("\n"), failures.isEmpty())
        } finally { root.deleteRecursively() }
    }

    /** 노이즈 제거·선 축소·샤프닝을 모두 켜도 결과 크기·프레임 수·오디오가 유지되는지 검사합니다. */
    @Test fun preAndPostOptionsKeepOutputContract(): Unit = runBlocking {
        check(context.packageName.endsWith(".devicecheck"))
        val work = File(context.cacheDir, "rev16-options-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            fixture(work, "libx264", "320x180").copyTo(File(work, "source"))
            EnhancementModels.stage(context, EnhancementModels.SMALL_MODEL, File(work, "model.tflite"))
            val data = Bundle().apply {
                putInt("option", 2); putInt("cpu_threads", 2); putString("denoise", "always"); putFloat("denoise_sigma", 14f)
                putString("shrink", "always"); putFloat("sharpen", 0.6f); putString("video_codec", "h264")
            }
            VideoEnhancer.process(context, work, data) {}
            val output = VideoEnhancer.inspect(File(work, "preview.mp4"))
            assertTrue("크기 ${output.width}x${output.height}", output.width == 640 && output.height == 360)
            assertTrue("프레임 ${output.frames}", output.frames == 3L)
            assertTrue("오디오 ${output.audioTracks}", output.audioTracks == 1)
        } finally { VideoEnhancer.stop(); work.deleteRecursively() }
    }

    /**
     * 노이즈 제거 엔진을 FFmpeg로 두면 전처리 임시 영상을 거쳐도 결과 크기·프레임 수·오디오가 같고,
     * 엔진 정보에 전처리 시간이 남으며 대체 없이 FFmpeg가 실제로 쓰였는지(선 축소 켬/끔 모두) 검사합니다.
     */
    @Test fun ffmpegDenoiseEngineKeepsOutputContract(): Unit = runBlocking {
        check(context.packageName.endsWith(".devicecheck"))
        for (shrink in listOf("always", "off")) {
            val work = File(context.cacheDir, "rev18-ffmpeg-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            try {
                fixture(work, "libx264", "320x180").copyTo(File(work, "source"))
                EnhancementModels.stage(context, EnhancementModels.SMALL_MODEL, File(work, "model.tflite"))
                val data = Bundle().apply {
                    putInt("option", 2); putInt("cpu_threads", 2); putString("denoise", "always"); putFloat("denoise_sigma", 14f)
                    putString("denoise_engine", "ffmpeg"); putString("shrink", shrink); putString("video_codec", "h264")
                }
                VideoEnhancer.process(context, work, data) {}
                val output = VideoEnhancer.inspect(File(work, "preview.mp4"))
                val engine = data.getString("engine").orEmpty()
                assertTrue("shrink=$shrink 크기 ${output.width}x${output.height}", output.width == 640 && output.height == 360)
                assertTrue("shrink=$shrink 프레임 ${output.frames}", output.frames == 3L)
                assertTrue("shrink=$shrink 오디오 ${output.audioTracks}", output.audioTracks == 1)
                assertTrue("FFmpeg 전처리가 쓰이지 않았습니다: $engine", "FFmpeg 전처리 " in engine && "실패" !in engine && "hqdn3d" in engine)
                assertTrue("shrink=$shrink scale 필터 여부: $engine", ("scale=" in engine) == (shrink == "always"))
                assertTrue("임시 영상이 남았습니다.", !File(work, "prefiltered.mp4").exists())
            } finally { VideoEnhancer.stop(); work.deleteRecursively() }
        }
    }

    /** 합성 SDR 영상·오디오를 지정한 코덱과 크기로 만듭니다. */
    private fun fixture(root: File, codec: String, size: String): File {
        FFmpeg.getInstance().init(context)
        val source = File(root, if (codec == "libvpx") "fixture.mkv" else "fixture.mp4")
        val command = listOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").path, "-y", "-nostdin", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=$size:rate=3:duration=1", "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
            "-c:v", codec, "-pix_fmt", "yuv420p", "-bf", "0", "-c:a", "aac", "-shortest", source.path)
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python", "aria2c").joinToString(":") {
                File(context.noBackupFilesDir, "youtubedl-android/packages/$it/usr/lib").path
            }
        }.start()
        val error = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { error }
        return source
    }
}
