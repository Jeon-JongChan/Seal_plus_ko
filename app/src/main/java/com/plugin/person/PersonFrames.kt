package com.plugin.person

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import com.plugin.compatibility.enhancement.VideoEnhancer
import com.plugin.compatibility.enhancement.VideoRules
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 하드웨어 우선 코덱으로 순차 디코딩하고 선택한 프레임만 최대 640픽셀 RGB로 읽습니다. */
class PersonFrames(context: Context, uri: Uri) : AutoCloseable {
    private val extractor = MediaExtractor()
    private var codec: MediaCodec? = null
    private var started = false
    private var closed = false
    private var rotation = 0
    private var origin = 0L
    var timeUs = 0L
        private set
    val durationMs: Long
    val backend: String get() = codec?.name.orEmpty()

    init {
        try {
            extractor.setDataSource(context,uri,null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("영상 트랙을 찾을 수 없습니다.")
            val format = extractor.getTrackFormat(track)
            durationMs = format.getLong(MediaFormat.KEY_DURATION)/1000
            require(durationMs > 0) { "영상 길이가 유효하지 않습니다." }
            rotation = integer(format,MediaFormat.KEY_ROTATION,0)
            require(rotation in setOf(0,90,180,270)) { "지원하지 않는 영상 회전입니다." }
            // HDR을 SDR 수식에 그대로 넣으면 색·명암 및 인식 결과가 달라지므로 명시적으로 거부합니다.
            require(integer(format,MediaFormat.KEY_COLOR_TRANSFER,3) !in setOf(6,7)) {
                "HDR 영상은 SDR로 변환한 뒤 분석하세요. 이 경로는 SDR 영상만 지원합니다."
            }
            extractor.selectTrack(track)
            origin = extractor.sampleTime.coerceAtLeast(0)
            format.setInteger(MediaFormat.KEY_ROTATION,0)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val rejected = mutableSetOf<String>()
            val failures = mutableListOf<Throwable>()
            while (!started) {
                val candidate = try {
                    VideoEnhancer.mediaCodec(requireNotNull(format.getString(MediaFormat.KEY_MIME)),
                        format.getInteger(MediaFormat.KEY_WIDTH),format.getInteger(MediaFormat.KEY_HEIGHT),false,excluded = rejected)
                } catch (failure: Exception) {
                    failures.forEach { failure.addSuppressed(it) }; throw failure
                }
                codec = candidate
                try { candidate.configure(format,null,null,0); candidate.start(); started = true }
                catch (failure: Exception) {
                    // 초기화 실패한 하드웨어를 반복 선택하지 않고 다음 지원 코덱으로 내려갑니다.
                    rejected += candidate.name; failures += failure
                    runCatching { candidate.release() }; codec = null
                }
            }
        } catch (failure: Throwable) { close(); throw failure }
    }

    /** 콜백의 양수 간격만큼 건너뛰며 실제 PTS를 전달하고 프레임 소유권은 호출 중에만 빌려줍니다. */
    suspend fun read(startUs: Long = 0, consume: suspend (Bitmap,Long) -> Long) {
        check(!closed && started)
        require(startUs >= 0)
        if (startUs > 0) extractor.seekTo(Math.addExact(origin,startUs),MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val decoder = checkNotNull(codec)
        val info = MediaCodec.BufferInfo()
        var inputEnded = false; var outputEnded = false; var nextUs = Math.addExact(origin,startUs)
        var lastActivity = SystemClock.elapsedRealtime()
        while (!outputEnded) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime()-lastActivity < 30_000) { "영상 디코더가 응답하지 않습니다." }
            if (!inputEnded) {
                val slot = decoder.dequeueInputBuffer(10_000)
                if (slot >= 0) {
                    val buffer = checkNotNull(decoder.getInputBuffer(slot)).apply { clear() }
                    val count = extractor.readSampleData(buffer,0)
                    if (count < 0) {
                        decoder.queueInputBuffer(slot,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                    } else {
                        decoder.queueInputBuffer(slot,0,count,extractor.sampleTime,0); extractor.advance()
                    }
                    lastActivity = SystemClock.elapsedRealtime()
                }
            }
            val slot = decoder.dequeueOutputBuffer(info,10_000)
            if (slot >= 0) {
                try {
                    if (info.size > 0 && info.presentationTimeUs >= nextUs &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val image = decoder.getOutputImage(slot) ?: error("선택한 영상 코덱은 YUV 프레임 읽기를 지원하지 않습니다.")
                        val bitmap = image.use { rgb(it,decoder.outputFormat) }
                        try {
                            timeUs = info.presentationTimeUs-origin
                            val step = consume(bitmap,timeUs/1000)
                            require(step in 1..60_000) { "영상 분석 간격이 유효하지 않습니다." }
                            nextUs = Math.addExact(info.presentationTimeUs,step*1000)
                        } finally { bitmap.recycle() }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                } finally { decoder.releaseOutputBuffer(slot,false) }
                lastActivity = SystemClock.elapsedRealtime()
            } else if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) lastActivity = SystemClock.elapsedRealtime()
        }
    }

    /** 크롭·평면 간격·색 범위를 보존하며 전체 해상도의 RGB 배열을 만들지 않습니다. */
    private fun rgb(image: Image, format: MediaFormat): Bitmap {
        check(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "SDR YUV420 영상만 지원합니다." }
        val crop = image.cropRect
        require(crop.width()>0 && crop.height()>0)
        val scale = minOf(1.0,640.0/maxOf(crop.width(),crop.height()))
        val width = maxOf(1,(crop.width()*scale).toInt()); val height = maxOf(1,(crop.height()*scale).toInt())
        val planes = image.planes
        val buffers = Array(3) { planes[it].buffer }
        val positions = IntArray(3) { buffers[it].position() }
        val rows = IntArray(3) { planes[it].rowStride }; val strides = IntArray(3) { planes[it].pixelStride }
        /** 평면별 시작 위치와 픽셀 간격을 반영해 절대 위치로 읽습니다. */
        fun value(plane: Int,x: Int,y: Int): Int {
            val divisor = if (plane==0) 1 else 2
            return buffers[plane].get(positions[plane]+y/divisor*rows[plane]+x/divisor*strides[plane]).toInt() and 255
        }
        val full = integer(format,MediaFormat.KEY_COLOR_RANGE,2)==1
        val bt709 = integer(format,MediaFormat.KEY_COLOR_STANDARD,if (image.width>=1280 || image.height>=720) 1 else 2)==1
        val pixels = IntArray(width*height)
        for (y in 0 until height) for (x in 0 until width) {
            val sx = crop.left+minOf(crop.width()-1,(x/scale).toInt())
            val sy = crop.top+minOf(crop.height()-1,(y/scale).toInt())
            pixels[y*width+x] = VideoRules.rgb(value(0,sx,sy),value(1,sx,sy),value(2,sx,sy),full,bt709)
        }
        val raw = Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
        if (rotation==0) return raw
        return try { Bitmap.createBitmap(raw,0,0,width,height,Matrix().apply { postRotate(rotation.toFloat()) },true) }
        finally { raw.recycle() }
    }

    /** 선택 메타데이터가 없는 원본에는 SDR 표준 기본값을 사용합니다. */
    private fun integer(format: MediaFormat,key: String,fallback: Int): Int = if (format.containsKey(key)) format.getInteger(key) else fallback

    /** 중간 초기화 실패·취소·중복 종료에도 모든 네이티브 자원을 해제합니다. */
    override fun close() {
        if (closed) return
        closed = true
        try { if (started) runCatching { codec?.stop() } }
        finally { try { codec?.release() } finally { extractor.release() } }
    }
}
