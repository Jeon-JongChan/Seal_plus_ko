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
        val target = VideoRules.size(info.width, info.height, data.getInt("option"))
        check((info.width.toLong() * info.height * 3 + target.first.toLong() * target.second * 2) * 4 + 48 * 1024 * 1024 <=
            Runtime.getRuntime().maxMemory() * 7 / 10) { "영상 처리 메모리가 부족합니다. 더 낮은 해상도를 선택하세요." }
        val requested = data.getInt("fps"); val increase = VideoRules.increases(requested, info.fps, info.frames)
        val fps = if (increase) requested.toDouble() else info.fps
        val reference = data.getBoolean("rife_reference_test") && context.packageName.endsWith(".devicecheck")
        val rifeEngine = if (reference) "RIFE CPU 기준 검사" else "RIFE Vulkan GPU"
        val sampling = EnhancementSampling.normalized(data.getInt("sampling", EnhancementSampling.BILINEAR))
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
                data.putString("engine", "LiteRT CPU 대체${if (increase) " · $rifeEngine" else ""}")
                run()
            }
        }
        /** 결과 하나를 인코더에 전달한 뒤 프레임 메모리를 즉시 반환합니다. */
        suspend fun emit(frame: Bitmap, time: Long) {
            currentCoroutineContext().ensureActive()
            val begin = SystemClock.elapsedRealtime()
            val output = upscale(frame)
            upscaleMs += SystemClock.elapsedRealtime() - begin
            val writing = SystemClock.elapsedRealtime()
            try { checkNotNull(encoder).write(output, time); frames++ }
            finally { if (output !== frame) output.recycle() }
            writeMs += SystemClock.elapsedRealtime() - writing
            progress(((time - info.first).toDouble() / info.duration * 95).toInt().coerceIn(0, 95))
        }
        try {
            if (model.isFile) {
                lite = try { newLite(data.getBoolean("accelerated")) }
                catch (failure: Exception) { if (!data.getBoolean("accelerated")) throw failure; newLite(false) }
                catch (failure: LinkageError) { if (!data.getBoolean("accelerated")) throw failure; newLite(false) }
            } else check(File(directory, "model.onnx").isFile) { "영상 보정 모델이 없습니다." }
            if (increase) rife = RifeSession(context, File(directory, "rife").path, reference)
            data.putString("engine", "${lite?.let { "LiteRT ${it.route()}" } ?: "ONNX"}${if (increase) " · $rifeEngine" else " · 프레임 유지"}")
            val sourceMime = info.format.getString(MediaFormat.KEY_MIME)!!
            var encoderFailure: Exception? = null
            for (mime in VideoRules.encoderMimes(sourceMime, android.os.Build.VERSION.SDK_INT, data.getBoolean("original_codec"))) {
                try { encoder = Encoder(video, target.first, target.second, fps, info.rotation, mime); break }
                catch (failure: Exception) { encoderFailure = failure }
            }
            check(encoder != null) { "원본 코덱과 AV1 인코더를 사용할 수 없습니다. ${encoderFailure?.message ?: "AV1 MP4 저장은 Android 14 이상이 필요합니다."}" }
            data.putString("encoder", encoder.name)
            extractor.setDataSource(source.path); extractor.selectTrack(info.track)
            info.format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder = mediaCodec(info.format.getString(MediaFormat.KEY_MIME)!!, info.width, info.height, false)
            decoder.configure(info.format, null, null, 0); decoder.start()
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
                            val frame = image.use { read(it, decoder.outputFormat, decodedPixels) }
                            readMs += SystemClock.elapsedRealtime() - reading
                            val time = bufferInfo.presentationTimeUs
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
        }
    }

    /** 기존 FFmpeg를 재사용해 모든 오디오·메타데이터를 영상 결과와 결합합니다. */
    private suspend fun muxAudio(context: Context, source: File, video: File, output: File, audioTracks: Int) {
        FFmpeg.getInstance().init(context)
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        for (audioCodec in listOf("copy", "aac")) {
            currentCoroutineContext().ensureActive()
            val command = listOf(File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").path, "-y", "-nostdin", "-loglevel", "error",
                "-copyts", "-i", video.path, "-i", source.path, "-map", "0:v:0", "-map", "1:a?", "-map_metadata", "1",
                "-c:v", "copy", "-c:a", audioCodec, "-movflags", "+faststart", output.path)
            val process = ProcessBuilder(command).redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").path }
            }.start()
            child = process; val errors = ArrayDeque<String>()
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    currentCoroutineContext().ensureActive(); errors.addLast(line); if (errors.size > 20) errors.removeFirst()
                } }
                if (process.waitFor() == 0 && output.length() > 0) return
                if (audioCodec == "aac" || audioTracks == 0) error("오디오 저장 실패: ${errors.joinToString("\n")}")
            } finally { process.destroy(); child = null }
        }
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
        val buffers = planes.map { it.buffer }; val positions = buffers.map { it.position() }
        val rows = planes.map { it.rowStride }; val strides = planes.map { it.pixelStride }
        /** 평면의 버퍼 시작 위치를 포함하며 프레임 픽셀마다 배열을 만들지 않습니다. */
        fun value(index: Int, x: Int, y: Int): Int {
            val divisor = if (index == 0) 1 else 2
            return buffers[index].get(positions[index] + ((y + crop.top) / divisor) * rows[index] + ((x + crop.left) / divisor) * strides[index]).toInt() and 255
        }
        for (y in 0 until height) for (x in 0 until width) {
            pixels[y * width + x] = VideoRules.rgb(value(0, x, y), value(1, x, y), value(2, x, y), full, standard == 1)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    }

    /** 지원되지 않는 선택 메타데이터는 명시한 기본값으로 읽습니다. */
    private fun MediaFormat.integer(key: String, fallback: Int): Int = if (containsKey(key)) getInteger(key) else fallback

    /** 메타데이터 검사 실패에도 추출기를 해제합니다. */
    private inline fun <T> MediaExtractor.useExtractor(block: (MediaExtractor) -> T): T = try { block(this) } finally { release() }

    /** 해상도·YUV 접근을 지원하는 코덱 중 하드웨어를 우선하고 소프트웨어는 대체로 사용합니다. */
    private fun mediaCodec(mime: String, width: Int, height: Int, encoding: Boolean, fps: Double = 0.0): MediaCodec {
        val selected = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.filter { candidate ->
            candidate.isEncoder == encoding && mime in candidate.supportedTypes && runCatching {
                val capabilities = candidate.getCapabilitiesForType(mime)
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in capabilities.colorFormats &&
                    capabilities.videoCapabilities.isSizeSupported(width, height) &&
                    (!encoding || capabilities.videoCapabilities.areSizeAndRateSupported(width, height, fps))
            }.getOrDefault(false)
        }.sortedBy { candidate ->
            // Android 10부터는 플랫폼의 실제 분류를 사용하고 이전 버전은 표준 소프트웨어 이름을 구분합니다.
            if (android.os.Build.VERSION.SDK_INT >= 29) !candidate.isHardwareAccelerated
            else candidate.name.startsWith("OMX.google.", true) || candidate.name.startsWith("c2.android.", true)
        }.firstOrNull() ?: error("이 기기의 영상 코덱이 해상도·프레임률·YUV 접근을 지원하지 않습니다.")
        return MediaCodec.createByCodecName(selected.name)
    }

    /** 선택한 원본 또는 AV1 인코더와 MP4 기록기를 한 요청 동안 소유합니다. */
    private class Encoder(file: File, private val width: Int, private val height: Int, fps: Double, rotation: Int, mime: String) : AutoCloseable {
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
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
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
                val buffers = planes.map { it.buffer }; val positions = buffers.map { it.position() }
                val rows = planes.map { it.rowStride }; val strides = planes.map { it.pixelStride }
                /** 평면 간격은 기기 코덱이 제공한 값을 따릅니다. */
                fun put(index: Int, x: Int, y: Int, value: Int) {
                    buffers[index].put(positions[index] + y * rows[index] + x * strides[index], value.toByte())
                }
                for (y in 0 until height) for (x in 0 until width) {
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
                }
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
