package com.plugin.compatibility.enhancement

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.SystemClock
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** 원본 시각을 보존하는 코덱 스트림에 공통 업스케일 전략과 Vulkan 보간을 적용합니다. */
object VideoEnhancer {
    const val MAX_SOURCE_BYTES = 8L * 1024 * 1024 * 1024
    @Volatile private var child: java.lang.Process? = null

    /** 원본 트랙에서 확인한 영상 크기·시각·SDR 색 공간입니다. */
    data class Info(val track: Int, val format: MediaFormat, val width: Int, val height: Int,
        val first: Long, val duration: Long, val fps: Double, val rotation: Int, val audioTracks: Int, val frames: Long)

    /** 시각을 스캔해 VFR도 원본 프레임 수 기준으로 비교합니다. */
    fun inspect(file: File, checkCancelled: () -> Unit = {}): Info {
        MediaExtractor().useExtractor { extractor ->
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: error("영상 트랙이 없습니다.")
            val format = extractor.getTrackFormat(track)
            check(!format.containsKey("crypto-key")) { "암호화된 영상은 처리하지 않습니다." }
            val transfer = format.integer(MediaFormat.KEY_COLOR_TRANSFER, 3)
            check(transfer != 6 && transfer != 7 && format.integer(MediaFormat.KEY_COLOR_STANDARD, 1) != 6) {
                "HDR 영상은 색상을 보존하기 위해 아직 보정하지 않습니다."
            }
            val width = format.getInteger(MediaFormat.KEY_WIDTH); val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            check(width > 0 && height > 0 && width.toLong() * height <= EnhancementRules.MAX_PIXELS) { "영상 해상도가 너무 큽니다." }
            extractor.selectTrack(track)
            var count = 0L; var first = Long.MAX_VALUE; var last = 0L
            while (extractor.sampleTime >= 0) {
                // 긴 영상의 전체 시각 검사도 작업 취소에 응답합니다.
                if (count % 1024 == 0L) checkCancelled()
                check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "암호화된 영상은 처리하지 않습니다." }
                val time = extractor.sampleTime; first = minOf(first, time); last = maxOf(last, time); count++
                if (!extractor.advance()) break
            }
            check(count > 0 && first >= 0) { "영상 프레임이 없습니다." }
            val hinted = format.integer(MediaFormat.KEY_FRAME_RATE, 30).coerceAtLeast(1).toDouble()
            val fps = if (count > 1 && last > first) (count - 1) * 1_000_000.0 / (last - first) else hinted
            check(fps.isFinite() && fps in 0.1..240.0) { "영상 프레임률을 지원하지 않습니다." }
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION)
                else last - first + (1_000_000 / fps).toLong()
            check(duration > 0) { "영상 길이를 확인할 수 없습니다." }
            return Info(track, format, width, height, first, duration, fps,
                format.integer(MediaFormat.KEY_ROTATION, 0), (0 until extractor.trackCount).count {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
                }, count)
        }
    }

    /** 원본·보간 프레임을 순차 처리하며 전체 프레임을 디스크나 메모리에 쌓지 않습니다. */
    suspend fun process(context: Context, directory: File, data: Bundle, progress: (Int) -> Unit) {
        val jobContext = currentCoroutineContext()
        val source = File(directory, "source"); val info = inspect(source) { jobContext.ensureActive() }
        val requested = data.getInt("fps"); val increase = VideoRules.increases(requested, info.fps, info.frames)
        val fps = if (increase) requested.toDouble() else info.fps
        val sourceMime = info.format.getString(MediaFormat.KEY_MIME)!!
        val mimes = VideoRules.encoderMimes(sourceMime, android.os.Build.VERSION.SDK_INT, data.getBoolean("original_codec"), data.getString("video_codec", "auto"))
        // 기기 인코더가 지원하는 최대 해상도를 넘는 목표(예: 1080p 한계에서 2K·4K)는 지원 범위까지 낮춰 실패를 막습니다.
        val wanted = VideoRules.size(info.width, info.height, data.getInt("option"))
        val target = fitTarget(mimes, wanted, fps)
        check((info.width.toLong() * info.height * 3 + target.first.toLong() * target.second * 2) * 4 + 48 * 1024 * 1024 <=
            Runtime.getRuntime().maxMemory() * 7 / 10) { "영상 처리 메모리가 부족합니다. 더 낮은 해상도를 선택하세요." }
        val enhanceFirst = VideoRules.enhanceFirst(info.width, info.height, target, increase && data.getBoolean("interp_low"))
        val frameNote = VideoRules.frameNote(requested, info.fps, info.frames)
        val orderNote = if (enhanceFirst) "화질 개선 → 보간" else "보간 → 화질 개선"
        val rifeEngine = "RIFE Vulkan GPU"
        val sampling = EnhancementSampling.normalized(data.getInt("sampling", EnhancementSampling.BILINEAR))
        val denoise = PreDenoise.enabled(data.getString("denoise", PreDenoise.AUTO), source.length(), info.duration, info.width, info.height, info.fps)
        val denoiseSigma = data.getFloat("denoise_sigma", 14f)
        val denoiseDistance = data.getBoolean("denoise_distance", true)
        val sharpen = data.getFloat("sharpen", 0f)
        // 다운스케일 트릭: 저비트레이트 원본은 75%로 줄여 블록 노이즈를 평탄화한 뒤 모델에 전달합니다. 목표 크기는 원본 기준으로 유지됩니다.
        val shrink = PreDenoise.enabled(data.getString("shrink", PreDenoise.OFF), source.length(), info.duration, info.width, info.height, info.fps)
        // FFmpeg 전처리 엔진: 노이즈 제거(+선 축소)를 영상 전체에 한 번에 처리한 임시 영상을 만들어 이후 단계의 입력으로 씁니다.
        // 프레임마다 비트맵 복사·필터를 반복하지 않고 시간축 필터(hqdn3d)를 쓸 수 있으며, 실패하면 내장 필터로 대체합니다.
        val useFfmpeg = denoise && data.getString("denoise_engine", PreDenoise.BUILTIN) == PreDenoise.FFMPEG
        val prefiltered = File(directory, "prefiltered.mp4")
        var decodeFile = source; var decodeInfo = info; var ffDenoised = false; var ffShrunk = false; var prepassNote = ""
        val model = File(directory, "model.tflite")
        var lite: LiteRtSession? = null; var rife: RifeSession? = null
        var previous: Bitmap? = null; var previousTime = -1L; var grid = 0L; var frames = 0
        val video = File(directory, "video-only.mp4")
        val extractor = MediaExtractor(); var decoder: MediaCodec? = null; var encoder: Encoder? = null
        val decodedPixels = IntArray(info.width * info.height)
        val started = SystemClock.elapsedRealtime()
        var readMs = 0L; var upscaleMs = 0L; var writeMs = 0L
        var inferenceMs = 0L; var interpolationMs = 0L
        /** 같은 스레드에서 만든 LiteRT 세션은 영상 전체에 재사용합니다. */
        fun newLite(accelerated: Boolean): LiteRtSession = LiteRtSession(context, model.path, accelerated,
            EnhancementRules.cpuThreads(data.getInt("cpu_threads", 2), Runtime.getRuntime().availableProcessors()),
            accelerated && data.getBoolean("allow_npu"))
        /** 타일 처리를 공유하되 가속 실행 실패는 같은 모델의 CPU로 한 번만 복구합니다. */
        suspend fun upscale(frame: Bitmap): Bitmap {
            if (!model.isFile) return OnnxEnhancer.process(frame, File(directory, "model.onnx").path, target, {},
                data.getBoolean("accelerated") && data.getBoolean("allow_npu"), {},
                cpuThreads = data.getInt("cpu_threads", 2), sampling = sampling)
            /** 모델 출력 배치와 공통 타일 처리 규칙을 연결합니다. */
            suspend fun run(): Bitmap {
                val session = checkNotNull(lite)
                return ImageTiles.process(frame, target, session.inputWidth(), session.inputHeight(), false, {}, {}, sampling) { input, consume ->
                    val begin = SystemClock.elapsedRealtime(); val values = session.infer(input)
                    inferenceMs += SystemClock.elapsedRealtime() - begin
                    consume(values, session.outputWidth(), session.outputHeight())
                }
            }
            return try { run() } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (lite?.route()?.startsWith("CPU") == true) throw failure
                lite?.close(); lite = newLite(false)
                data.putString("engine", "LiteRT CPU 대체${if (increase) " · $rifeEngine" else ""}\n$frameNote · $orderNote")
                run()
            }
        }
        /** 결과 하나를 인코더에 전달한 뒤 프레임 메모리를 즉시 반환합니다. */
        suspend fun emit(frame: Bitmap, time: Long) {
            currentCoroutineContext().ensureActive()
            // 일부 VFR 파일은 종료 시각에도 표본을 노출합니다. 표시 시간이 없는 표본으로 길이를 늘리지 않습니다.
            if (time >= info.first + info.duration) return
            val begin = SystemClock.elapsedRealtime()
            val upscaled = if (enhanceFirst) frame else upscale(frame)
            // 선택 시 최종 크기에서 한 번만 샤프닝합니다. 원본·보간 프레임 모두 같은 후처리를 거칩니다.
            // 샤프닝이 실패해도 업스케일 결과가 새지 않도록 finally에서 해제합니다.
            val output = if (sharpen > 0f) try { PostSharpen.apply(upscaled, sharpen) } finally { if (upscaled !== frame) upscaled.recycle() } else upscaled
            upscaleMs += SystemClock.elapsedRealtime() - begin
            val writing = SystemClock.elapsedRealtime()
            try { checkNotNull(encoder).write(output, time); frames++ }
            finally { if (output !== frame) output.recycle() }
            writeMs += SystemClock.elapsedRealtime() - writing
            progress(((time - info.first).toDouble() / info.duration * 95).toInt().coerceIn(0, 95))
        }
        try {
            if (useFfmpeg) {
                val began = SystemClock.elapsedRealtime()
                try {
                    // 내장 선 축소와 같은 크기 규칙(75%, 짝수)을 FFmpeg scale에도 적용해 이후 단계가 동일하게 동작하게 합니다.
                    val expected = if (shrink) maxOf(2, info.width * 3 / 4 / 2 * 2) to maxOf(2, info.height * 3 / 4 / 2 * 2) else info.width to info.height
                    val flag = when (sampling) { EnhancementSampling.NEAREST -> "neighbor"; EnhancementSampling.AREA -> "area"; else -> "bilinear" }
                    val filter = PreDenoise.hqdn3d(denoiseSigma) + if (shrink) ",scale=${expected.first}:${expected.second}:flags=$flag" else ""
                    data.putString("engine", "FFmpeg 전처리 중 · $filter")
                    data.putString("stage", "1. 전처리 · FFmpeg 노이즈 제거${if (shrink) " + 선 축소" else ""} (영상 전체를 먼저 한 번 처리)"); progress(0)
                    prefilter(context, source, prefiltered, filter)
                    val made = inspect(prefiltered) { jobContext.ensureActive() }
                    // 프레임 수·크기가 어긋나면 원본 시각과 맞출 수 없으므로 임시 영상을 버립니다.
                    check(made.frames == info.frames && made.width == expected.first && made.height == expected.second) {
                        "임시 영상이 원본과 맞지 않습니다(${made.width}x${made.height}, ${made.frames}프레임)."
                    }
                    decodeFile = prefiltered; decodeInfo = made; ffDenoised = true; ffShrunk = shrink
                    prepassNote = "FFmpeg 전처리 ${SystemClock.elapsedRealtime() - began}ms · $filter"
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (failure: Exception) {
                    prefiltered.delete()
                    prepassNote = "FFmpeg 전처리 실패 → 내장 필터로 대체: ${failure.message.orEmpty().take(160)}"
                    android.util.Log.w("VideoTiming", prepassNote)
                }
            }
            data.putString("stage", "AI 모델 초기화 · 업스케일${if (increase) " + 프레임 보간(RIFE)" else ""}"); progress(0)
            if (model.isFile) {
                lite = try { newLite(data.getBoolean("accelerated")) }
                catch (failure: Exception) { if (!data.getBoolean("accelerated")) throw failure; newLite(false) }
                catch (failure: LinkageError) { if (!data.getBoolean("accelerated")) throw failure; newLite(false) }
            } else check(File(directory, "model.onnx").isFile) { "영상 보정 모델이 없습니다." }
            if (increase) rife = RifeSession(File(directory, "rife").path)
            data.putString("engine", "${lite?.let { "LiteRT ${it.route()}" } ?: "ONNX"}${if (increase) " · $rifeEngine" else " · 프레임 유지"}\n$frameNote · $orderNote")
            val encoderFailures = mutableListOf<String>()
            for (mime in mimes) {
                try { encoder = Encoder(video, target.first, target.second, fps, info.rotation, mime); break }
                catch (failure: Exception) { encoderFailures += "$mime: ${failure.message}" }
            }
            check(encoder != null) { "이 기기에서 ${target.first}x${target.second} ${fps.toInt()}fps 영상을 인코딩할 수 없습니다.\n${encoderFailures.joinToString("\n")}" }
            data.putString("encoder", encoder.name)
            // 선택한 코덱이 이 해상도를 지원하지 않아 대체됐다면 결과 화면에서 이유를 볼 수 있게 남깁니다.
            if (encoderFailures.isNotEmpty()) data.putString("engine", "${data.getString("engine")}\n코덱 대체: ${encoderFailures.joinToString(" / ") { it.take(160) }} → ${encoder.mime}")
            if (encoderFailures.isNotEmpty()) android.util.Log.w("VideoTiming", "인코더 대체: ${encoderFailures.joinToString(" / ")} → ${encoder.mime}")
            if (target != wanted) data.putString("engine", "${data.getString("engine")}\n해상도 제한: ${wanted.first}x${wanted.second} → ${target.first}x${target.second} (기기 인코더 한계)")
            if (prepassNote.isNotEmpty()) data.putString("engine", "${data.getString("engine")}\n$prepassNote")
            // 프레임마다 실제로 거치는 단계만 실행 순서대로 표시합니다(보간 시점에 따라 업스케일·보간 순서가 바뀝니다).
            val upscaleStep = "AI 업스케일(${target.first}x${target.second})"
            data.putString("stage", "프레임 처리 · " + buildList {
                add("디코드")
                if (denoise && !ffDenoised) add("노이즈 제거")
                if (shrink && !ffShrunk) add("선 축소")
                if (enhanceFirst) { add(upscaleStep); if (increase) add("프레임 보간") }
                else { if (increase) add("프레임 보간"); add(upscaleStep) }
                if (sharpen > 0f) add("샤프닝")
                add("인코딩 ${encoder.mime.substringAfter('/').uppercase()}")
            }.joinToString(" → "))
            // 전처리 임시 영상의 시작 시각이 원본과 다를 수 있어 원본 시각으로 되돌리는 보정값입니다.
            val timeOffset = info.first - decodeInfo.first
            extractor.setDataSource(decodeFile.path); extractor.selectTrack(decodeInfo.track)
            decodeInfo.format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder = mediaCodec(decodeInfo.format.getString(MediaFormat.KEY_MIME)!!, decodeInfo.width, decodeInfo.height, false)
            decoder.configure(decodeInfo.format, null, null, 0); decoder.start()
            val bufferInfo = MediaCodec.BufferInfo(); var inputDone = false; var outputDone = false
            var lastActivity = SystemClock.elapsedRealtime()
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                check(SystemClock.elapsedRealtime() - lastActivity < 30_000) { "영상 디코더가 응답하지 않습니다." }
                if (!inputDone) {
                    val slot = decoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(slot)); buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) { decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(slot, 0, count, extractor.sampleTime, 0); extractor.advance() }
                        lastActivity = SystemClock.elapsedRealtime()
                    }
                }
                val slot = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (slot >= 0) {
                    try {
                        if (bufferInfo.size > 0) {
                            val image = decoder.getOutputImage(slot) ?: error("이 디코더는 CPU에서 읽을 수 있는 SDR 프레임을 지원하지 않습니다.")
                            val reading = SystemClock.elapsedRealtime()
                            // 저비트레이트 원본은 노이즈가 디테일로 확대되지 않도록 CPU 양방향 필터를 먼저 적용합니다.
                            val decoded = image.use { read(it, decoder.outputFormat, decodedPixels) }
                                .let { raw -> if (denoise && !ffDenoised) PreDenoise.apply(raw, denoiseSigma, denoiseDistance).also { raw.recycle() } else raw }
                                .let { raw -> if (shrink && !ffShrunk) EnhancementSampling.resize(raw, maxOf(2, raw.width * 3 / 4 / 2 * 2), maxOf(2, raw.height * 3 / 4 / 2 * 2), sampling)
                                    .also { raw.recycle() } else raw }
                            readMs += SystemClock.elapsedRealtime() - reading
                            // 크기 유지에서는 원본마다 한 번만 보정하고 보간 결과를 다시 보정하지 않습니다.
                            val frame = if (enhanceFirst) {
                                val begin = SystemClock.elapsedRealtime()
                                try { upscale(decoded).also { if (it !== decoded) decoded.recycle() } }
                                catch (failure: Throwable) { decoded.recycle(); throw failure }
                                finally { upscaleMs += SystemClock.elapsedRealtime() - begin }
                            } else decoded
                            val time = bufferInfo.presentationTimeUs + timeOffset
                            try {
                                check(time >= info.first && (previousTime < 0 || time > previousTime)) { "영상 프레임 시각이 올바르지 않습니다." }
                                if (!increase) emit(frame, time)
                                else if (previous == null) {
                                    previous = frame; previousTime = time
                                    emit(frame, time); grid = 1
                                } else {
                                    var next = VideoRules.timestamp(info.first, grid, requested)
                                    while (next <= time) {
                                        val old = checkNotNull(previous)
                                        val middle = if (next <= previousTime) old else if (next == time) frame
                                            else {
                                                val begin = SystemClock.elapsedRealtime()
                                                val interpolated = checkNotNull(rife).interpolate(old, frame, ((next - previousTime).toDouble() / (time - previousTime)).toFloat())
                                                interpolationMs += SystemClock.elapsedRealtime() - begin
                                                interpolated
                                            }
                                        try { emit(middle, next) } finally { if (middle !== old && middle !== frame) middle.recycle() }
                                        next = VideoRules.timestamp(info.first, ++grid, requested)
                                    }
                                    previous?.recycle(); previous = frame; previousTime = time
                                }
                            } finally { if (frame !== previous) frame.recycle() }
                        }
                        outputDone = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(slot, false) }
                    lastActivity = SystemClock.elapsedRealtime()
                } else if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) lastActivity = SystemClock.elapsedRealtime()
            }
            if (increase && previous != null) {
                var next = VideoRules.timestamp(info.first, grid, requested)
                // 마지막 원본 이후에는 미래 프레임이 없으므로 영상 끝까지만 마지막 프레임을 유지합니다.
                while (next < info.first + info.duration) {
                    emit(checkNotNull(previous), next); next = VideoRules.timestamp(info.first, ++grid, requested)
                }
            }
            check(frames > 0) { "보정할 프레임이 없습니다." }
            encoder.finish(info.first + info.duration)
            data.putString("stage", "저장 · 오디오 합치기 (FFmpeg, 영상 재인코딩 없음)"); progress(95)
            muxAudio(context, source, video, File(directory, "preview.mp4"), info.audioTracks)
            val result = inspect(File(directory, "preview.mp4")) { jobContext.ensureActive() }
            check(result.width == target.first && result.height == target.second && result.audioTracks == info.audioTracks &&
                kotlin.math.abs(result.duration - info.duration) <= maxOf(150_000L, (2_000_000 / fps).toLong())) { "보정 영상의 트랙·길이 검증에 실패했습니다." }
            data.putInt("width", result.width); data.putInt("height", result.height)
            data.putDouble("frame_rate", result.fps); data.putDouble("duration", result.duration / 1_000_000.0)
            data.putString("codec", result.format.getString(MediaFormat.KEY_MIME))
            data.putLong("total_ms", SystemClock.elapsedRealtime() - started)
            data.putLong("read_ms", readMs); data.putLong("upscale_ms", upscaleMs); data.putLong("write_ms", writeMs)
            data.putLong("inference_ms", inferenceMs); data.putLong("interpolation_ms", interpolationMs)
            android.util.Log.i("VideoTiming", "${info.width}x${info.height} → ${result.width}x${result.height}, $frames 프레임, ${data.getString("engine")}, 코덱=${data.getString("codec")}, 총=${SystemClock.elapsedRealtime() - started}ms, 읽기=${readMs}ms, 보정=${upscaleMs}ms, 쓰기=${writeMs}ms")
            data.putInt("worker_pid", android.os.Process.myPid()); progress(100)
        } finally {
            stop(); previous?.recycle(); rife?.close(); lite?.close()
            decoder?.let { runCatching { it.stop() }; it.release() }; extractor.release(); encoder?.close()
            prefiltered.delete() // 전처리 임시 영상은 작업이 끝나면 필요 없습니다.
        }
    }

    /** 기존 FFmpeg를 재사용해 모든 오디오·메타데이터를 영상 결과와 결합합니다. */
    private suspend fun muxAudio(context: Context, source: File, video: File, output: File, audioTracks: Int) {
        for (audioCodec in listOf("copy", "aac")) {
            val failure = runFfmpeg(context, listOf("-copyts", "-i", video.path, "-i", source.path, "-map", "0:v:0", "-map", "1:a?",
                "-map_metadata", "1", "-c:v", "copy", "-c:a", audioCodec, "-movflags", "+faststart"), output) ?: return
            if (audioCodec == "aac" || audioTracks == 0) error("오디오 저장 실패: $failure")
        }
    }

    /**
     * 전처리 FFmpeg 패스: 원본 영상 트랙만 [filter]로 처리해 MediaCodec이 읽을 수 있는 임시 H.264로 저장합니다.
     * 프레임 시각은 `passthrough`로 그대로 두고(프레임 추가·삭제 없음), 회전 정보는 원본에서 따로 쓰므로 `-noautorotate`로 화소를 건드리지 않습니다.
     * CRF 12는 임시 파일 크기보다 재인코딩 손실이 눈에 띄지 않는 것을 우선한 값입니다.
     */
    private suspend fun prefilter(context: Context, source: File, output: File, filter: String) {
        val failure = runFfmpeg(context, listOf("-noautorotate", "-i", source.path, "-map", "0:v:0", "-vf", filter,
            "-fps_mode", "passthrough", "-c:v", "libx264", "-preset", "veryfast", "-crf", "12", "-pix_fmt", "yuv420p",
            "-an", "-sn", "-dn", "-map_metadata", "-1"), output)
        if (failure != null) error(failure)
    }

    /**
     * 번들 FFmpeg를 [arguments] + 출력 파일로 실행하고 성공하면 null, 실패하면 마지막 오류 줄을 반환합니다.
     * 취소는 실행 중에도 반영되며 프로세스는 항상 정리합니다.
     */
    private suspend fun runFfmpeg(context: Context, arguments: List<String>, output: File): String? {
        currentCoroutineContext().ensureActive()
        FFmpeg.getInstance().init(context)
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val command = listOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").path, "-y", "-nostdin", "-loglevel", "error") +
            arguments + output.path
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").path }
        }.start()
        child = process; val errors = ArrayDeque<String>()
        try {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                currentCoroutineContext().ensureActive(); errors.addLast(line); if (errors.size > 20) errors.removeFirst()
            } }
            return if (process.waitFor() == 0 && output.length() > 0) null else errors.joinToString("\n").ifBlank { "FFmpeg 종료 코드 오류" }
        } finally { process.destroy(); child = null }
    }

    /** 프로세스 종료 전에 외부 FFmpeg도 종료해 고아 작업을 방지합니다. */
    fun stop() { child?.destroyForcibly(); child = null }

    /** YUV420 평면의 행·픽셀 간격과 크롭을 적용해 원본 SDR RGB로 읽습니다. */
    private fun read(image: Image, format: MediaFormat, pixels: IntArray): Bitmap {
        check(image.format == android.graphics.ImageFormat.YUV_420_888 && image.planes.size == 3) { "SDR YUV420 영상만 지원합니다." }
        val crop = image.cropRect; val width = crop.width(); val height = crop.height()
        check(width > 0 && height > 0 && width.toLong() * height <= pixels.size) { "영상 도중 해상도가 변경되어 처리할 수 없습니다." }
        val full = format.integer(MediaFormat.KEY_COLOR_RANGE, 2) == 1
        val standard = format.integer(MediaFormat.KEY_COLOR_STANDARD, if (width >= 1280 || height >= 720) 1 else 2)
        val planes = image.planes
        // 픽셀마다 평면·버퍼 속성을 반복 조회하지 않고 프레임마다 한 번만 가져옵니다.
        // 기본형 배열로 두어 픽셀마다 List 조회·Int 박싱이 생기지 않게 합니다.
        val buffers = Array(3) { planes[it].buffer }; val positions = IntArray(3) { buffers[it].position() }
        val rows = IntArray(3) { planes[it].rowStride }; val strides = IntArray(3) { planes[it].pixelStride }
        /** 평면의 버퍼 시작 위치를 포함하며 프레임 픽셀마다 배열을 만들지 않습니다. */
        fun value(index: Int, x: Int, y: Int): Int {
            val divisor = if (index == 0) 1 else 2
            return buffers[index].get(positions[index] + ((y + crop.top) / divisor) * rows[index] + ((x + crop.left) / divisor) * strides[index]).toInt() and 255
        }
        // 행마다 서로 다른 출력 구간을 쓰고 버퍼는 절대 위치 읽기만 하므로 동기화 없이 병렬 처리합니다.
        java.util.stream.IntStream.range(0, height).parallel().forEach { y ->
            for (x in 0 until width) pixels[y * width + x] = VideoRules.rgb(value(0, x, y), value(1, x, y), value(2, x, y), full, standard == 1)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    }

    /** 지원되지 않는 선택 메타데이터는 명시한 기본값으로 읽습니다. */
    private fun MediaFormat.integer(key: String, fallback: Int): Int = if (containsKey(key)) getInteger(key) else fallback

    /** 메타데이터 검사 실패에도 추출기를 해제합니다. */
    private inline fun <T> MediaExtractor.useExtractor(block: (MediaExtractor) -> T): T = try { block(this) } finally { release() }

    /** 해상도·YUV 접근을 지원하는 코덱 중 하드웨어를 우선하고 소프트웨어는 대체로 사용합니다. */
    internal fun mediaCodec(mime: String, width: Int, height: Int, encoding: Boolean, fps: Double = 0.0,
        excluded: Set<String> = emptySet()): MediaCodec =
        MediaCodec.createByCodecName((codecInfo(mime, width, height, encoding, fps,excluded)
            ?: error("지원하는 코덱 없음(${width}x$height${if (encoding) " ${fps.toInt()}fps" else ""}, 해상도·프레임률·YUV 접근 필요) 후보: ${describeCodecs(mime, width, height, encoding)}")).name)

    /** 코덱을 찾지 못했을 때 후보별 거부 원인(YUV·크기·프레임률 범위)을 오류 문구에 남깁니다. */
    private fun describeCodecs(mime: String, width: Int, height: Int, encoding: Boolean): String =
        codecList
            .filter { it.isEncoder == encoding && mime in it.supportedTypes }
            .joinToString(", ") { info ->
                runCatching {
                    val caps = info.getCapabilitiesForType(mime)
                    "${info.name}[flex=${MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in caps.colorFormats}, size=${caps.videoCapabilities.isSizeSupported(width, height)}, rate=${runCatching { caps.videoCapabilities.getSupportedFrameRatesFor(width, height) }.getOrNull()}]"
                }.getOrElse { "${info.name}[${it.message}]" }
            }.ifEmpty { "없음" }

    /**
     * 목표 크기를 어느 후보 인코더도 처리하지 못하면 종횡비를 유지한 채 5%씩 줄여 처음 지원되는 짝수 크기를 반환합니다.
     * 후보가 모두 한계 이하라면 원래 크기를 그대로 돌려줍니다. 지원 크기가 없으면 원래 크기로 두어 이후 오류가 이유를 보고합니다.
     */
    internal fun fitTarget(mimes: List<String>, wanted: Pair<Int, Int>, fps: Double): Pair<Int, Int> {
        var scale = 1.0
        while (wanted.first * scale >= 64 && wanted.second * scale >= 64) {
            val size = maxOf(2, (wanted.first * scale).toInt() / 2 * 2) to maxOf(2, (wanted.second * scale).toInt() / 2 * 2)
            if (mimes.any { codecInfo(it, size.first, size.second, true, fps) != null }) return size
            scale *= 0.95
        }
        return wanted
    }

    /** 기기 코덱 목록은 프로세스 동안 변하지 않으므로 한 번만 만듭니다(`fitTarget`이 크기마다 반복 조회). */
    private val codecList: List<MediaCodecInfo> by lazy {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.toList()
    }

    /** 요청 크기·FPS·YUV 입력을 지원하는 코덱 정보를 반환하며 하드웨어를 먼저 고릅니다. */
    private fun codecInfo(mime: String, width: Int, height: Int, encoding: Boolean, fps: Double,
        excluded: Set<String> = emptySet()): MediaCodecInfo? {
        return codecList.filter { candidate ->
            candidate.name !in excluded && candidate.isEncoder == encoding && mime in candidate.supportedTypes && runCatching {
                val capabilities = candidate.getCapabilitiesForType(mime)
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in capabilities.colorFormats &&
                    capabilities.videoCapabilities.isSizeSupported(width, height) &&
                    (!encoding || capabilities.videoCapabilities.areSizeAndRateSupported(width, height, fps))
            }.getOrDefault(false)
        }.sortedBy { candidate ->
            // Android 10부터는 플랫폼의 실제 분류를 사용하고 이전 버전은 표준 소프트웨어 이름을 구분합니다.
            if (android.os.Build.VERSION.SDK_INT >= 29) !candidate.isHardwareAccelerated
            else candidate.name.startsWith("OMX.google.", true) || candidate.name.startsWith("c2.android.", true)
        }.firstOrNull()
    }

    /** 선택한 원본 또는 AV1 인코더와 MP4 기록기를 한 요청 동안 소유합니다. */
    private class Encoder(file: File, private val width: Int, private val height: Int, fps: Double, rotation: Int, val mime: String) : AutoCloseable {
        private val codec = mediaCodec(mime, width, height, true, fps)
        /** 실제 선택된 플랫폼 인코더를 성능 검사에서 구분합니다. */
        val name: String get() = codec.name
        private val muxer = try { MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
            catch (failure: Throwable) { codec.release(); throw failure }
        private val info = MediaCodec.BufferInfo()
        // 프레임마다 큰 픽셀 배열을 재할당하지 않고 같은 영상에서 재사용합니다.
        private val pixels = IntArray(width * height)
        private var track = -1; private var started = false; private var ended = false
        init {
            try {
                val capabilities = codec.codecInfo.getCapabilitiesForType(mime)
                check(capabilities.videoCapabilities.isSizeSupported(width, height)) { "이 기기의 영상 인코더가 목표 해상도를 지원하지 않습니다." }
                val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, capabilities.videoCapabilities.bitrateRange.clamp((width * height * fps * 0.2).toInt().coerceIn(1_000_000, 50_000_000)))
                    // 장면별로 필요한 비트만 사용하며 고정 비트레이트로 용량을 채우지 않습니다.
                    if (capabilities.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR))
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat()); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, 1); setInteger(MediaFormat.KEY_COLOR_RANGE, 2); setInteger(MediaFormat.KEY_COLOR_TRANSFER, 3)
                }
                if (rotation in listOf(0, 90, 180, 270)) muxer.setOrientationHint(rotation)
                // 일부 제조사 인코더는 색상 표준·VBR 같은 선택 키에서 configure를 거부하므로 필수 키만으로 한 번 더 시도합니다.
                try { codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
                catch (failure: RuntimeException) {
                    codec.reset()
                    val minimal = MediaFormat.createVideoFormat(mime, width, height).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        setInteger(MediaFormat.KEY_BIT_RATE, format.getInteger(MediaFormat.KEY_BIT_RATE))
                        setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat()); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    }
                    codec.configure(minimal, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                codec.start()
            } catch (failure: Throwable) { codec.release(); muxer.release(); throw failure }
        }
        /** 인코더 출력을 비우면서 빈 입력 버퍼를 기다려 교착을 방지합니다. */
        private suspend fun input(): Int {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                currentCoroutineContext().ensureActive(); drain(false)
                val slot = codec.dequeueInputBuffer(10_000); if (slot >= 0) return slot
                check(SystemClock.elapsedRealtime() - start < 30_000) { "영상 인코더가 응답하지 않습니다." }
            }
        }
        /** RGB를 제한 범위 BT.709 YUV420으로 변환하고 원본 시각으로 전달합니다. */
        suspend fun write(bitmap: Bitmap, time: Long) {
            check(bitmap.width == width && bitmap.height == height)
            val slot = input()
            // 같은 슬롯의 getInputBuffer는 이전 Image를 무효화하므로 크기를 먼저 읽습니다.
            val capacity = checkNotNull(codec.getInputBuffer(slot)).capacity()
            val image = codec.getInputImage(slot) ?: error("이 인코더는 YUV 프레임 입력을 지원하지 않습니다.")
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            image.use {
                val planes = image.planes
                val buffers = Array(3) { planes[it].buffer }; val positions = IntArray(3) { buffers[it].position() }
                val rows = IntArray(3) { planes[it].rowStride }; val strides = IntArray(3) { planes[it].pixelStride }
                /** 평면 간격은 기기 코덱이 제공한 값을 따릅니다. */
                fun put(index: Int, x: Int, y: Int, value: Int) {
                    buffers[index].put(positions[index] + y * rows[index] + x * strides[index], value.toByte())
                }
                // 각 행은 서로 다른 버퍼 위치에만 쓰므로 병렬 변환해도 충돌하지 않습니다.
                java.util.stream.IntStream.range(0, height).parallel().forEach { y -> for (x in 0 until width) {
                    val color = pixels[y * width + x]; val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
                    put(0, x, y, (16 + ((47 * r + 157 * g + 16 * b + 128) shr 8)).coerceIn(16, 235))
                    if (x % 2 == 0 && y % 2 == 0) {
                        var red = 0; var green = 0; var blue = 0
                        for (dy in 0..1) for (dx in 0..1) {
                            val pixel = pixels[(y + dy) * width + x + dx]
                            red += pixel ushr 16 and 255; green += pixel ushr 8 and 255; blue += pixel and 255
                        }
                        red /= 4; green /= 4; blue /= 4
                        put(1, x / 2, y / 2, (128 + ((-26 * red - 87 * green + 113 * blue + 128) shr 8)).coerceIn(16, 240))
                        put(2, x / 2, y / 2, (128 + ((112 * red - 102 * green - 10 * blue + 128) shr 8)).coerceIn(16, 240))
                    }
                } }
            }
            codec.queueInputBuffer(slot, 0, capacity, time, 0); drain(false)
        }
        /** 종료 신호 뒤에도 남은 모든 프레임을 저장합니다. */
        suspend fun finish(time: Long) {
            codec.queueInputBuffer(input(), 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            val start = SystemClock.elapsedRealtime()
            while (!ended) {
                currentCoroutineContext().ensureActive(); drain(true)
                check(SystemClock.elapsedRealtime() - start < 30_000) { "영상 인코더 종료가 지연됩니다." }
            }
            check(started)
            // 가변 프레임률의 마지막 유지 시간은 이전 프레임 간격으로 추측하지 않습니다.
            val end = MediaCodec.BufferInfo().apply { set(0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
            muxer.writeSampleData(track, java.nio.ByteBuffer.allocate(0), end)
            muxer.stop(); started = false
        }
        /** 코덱 설정 패킷은 제외하고 실제 영상 패킷만 MP4에 기록합니다. */
        private fun drain(wait: Boolean) {
            while (true) {
                val slot = codec.dequeueOutputBuffer(info, if (wait) 10_000 else 0)
                if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(track < 0); track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                } else if (slot >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            // 종료 시각은 finish에서 기록하므로 영상 데이터의 종료 플래그는 분리합니다.
                            info.flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                            check(started); muxer.writeSampleData(track, checkNotNull(codec.getOutputBuffer(slot)), info)
                        }
                        if (eos) ended = true
                    } finally { codec.releaseOutputBuffer(slot, false) }
                } else return
            }
        }
        /** 오류·취소·정상 완료 모두 플랫폼 자원을 반환합니다. */
        override fun close() { runCatching { codec.stop() }; codec.release(); if (started) runCatching { muxer.stop() }; muxer.release() }
    }
}
