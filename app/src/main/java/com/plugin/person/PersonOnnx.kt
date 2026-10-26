package com.plugin.person

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sqrt

/** 검출 박스와 다섯 랜드마크를 원본 비트맵 좌표로 유지합니다. */
data class PersonFace(val bounds: RectF, val landmarks: FloatArray, val confidence: Float)

/** 사진과 영상에서 재사용하는 오프라인 YuNet 검출·SFace 추출 경로입니다. */
class PersonOnnx(private val context: Context, private val modelId: String = "sface-int8",
    private val backendMode: String = "auto", private val allowFallback: Boolean = true) : AutoCloseable {
    companion object {
        private val sessions = java.util.concurrent.atomic.AtomicInteger()
        /** 저장소 전환은 얼굴 분석의 파일 접근·네이티브 자원 해제까지 기다립니다. */
        fun isBusy(): Boolean = sessions.get() > 0
    }
    private val environment = OrtEnvironment.getEnvironment()
    private var detector: OrtSession? = null
    private var embedder: OrtSession? = null
    private var liteEmbedder: PersonLiteRt? = null
    private var liteDetector: PersonLiteRt? = null
    private var detectorRoute = 0
    private var useOnnxDetector = false
    private var routeIndex = 0
    private val routes = when (backendMode) {
        "auto", "npu" -> listOf("NPU","GPU","CPU")
        "gpu" -> listOf("GPU","CPU")
        "cpu" -> listOf("CPU")
        else -> throw IllegalArgumentException("지원하지 않는 가속 설정입니다.")
    }
    private val failures = mutableListOf<String>()
    private var detectorBackend = "초기화 전"
    private var embeddingBackend = "초기화 전"
    val backend: String get() = "검출 $detectorBackend · 임베딩 $embeddingBackend"+
        if (failures.isEmpty()) "" else "\n대체 사유: ${failures.joinToString(" / ")}"
    var detectionNs = 0L
        private set
    var alignmentNs = 0L
        private set
    var embeddingNs = 0L
        private set
    var initializationNs = 0L
        private set
    private var closed = false
    private var detectionPixels: IntArray? = null
    private var detectionInput: FloatArray? = null
    private var onnxDetectionInput: FloatArray? = null
    private var onnxDetectionOutput: FloatArray? = null
    private var embeddingPixels: IntArray? = null
    private var embeddingInput: FloatArray? = null

    init {
        synchronized(com.plugin.compatibility.TemporarySession) {
            check(!com.plugin.compatibility.TemporarySession.changing) { "저장소 전환을 마친 뒤 얼굴 분석을 시작하세요." }
            sessions.incrementAndGet()
        }
    }

    /** 얼굴 모델은 같은 분석의 전용 스레드에서 한 번만 초기화합니다. */
    private fun session(asset: String): OrtSession {
        val start = System.nanoTime()
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            environment.createSession(context.assets.open(asset).use { it.readBytes() }, options)
        }.also { initializationNs += System.nanoTime() - start }
    }

    /** 비율·BGR 값·오른쪽/아래 패딩을 보존해 LiteRT NHWC 입력에 바로 씁니다. */
    fun detect(bitmap: Bitmap): List<PersonFace> {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0)
        val start = System.nanoTime()
        val initialized = initializationNs
        val size = 640
        val scale = minOf(1.0, size.toDouble() / maxOf(bitmap.width, bitmap.height))
        val width = maxOf(1, (bitmap.width * scale).toInt())
        val height = maxOf(1, (bitmap.height * scale).toInt())
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = detectionPixels ?: IntArray(size*size).also { detectionPixels=it }
        try { resized.getPixels(pixels, 0, width, 0, 0, width, height) }
        finally { if (resized !== bitmap) resized.recycle() }
        val plane = size * size
        val values = detectionInput ?: FloatArray(plane*3).also { detectionInput=it }
        // 해상도·종횡비가 바뀔 때 이전 프레임이 패딩 영역에 남지 않게 먼저 비웁니다.
        values.fill(0f)
        for (y in 0 until height) for (x in 0 until width) {
            val color = pixels[y * width + x]; val offset = (y * size + x)*3
            values[offset] = (color and 255).toFloat()
            values[offset+1] = (color ushr 8 and 255).toFloat()
            values[offset+2] = (color ushr 16 and 255).toFloat()
        }
        val candidates = mutableListOf<PersonFace>()
        val packed = detectValues(values)
        var offset = 0
                for (stride in listOf(8, 16, 32)) {
                    val columns = size / stride; val count = columns * columns
                    for (i in 0 until count) {
                        val base = offset+i*16
                        val confidence = sqrt(packed[base].coerceIn(0f, 1f) * packed[base+1].coerceIn(0f, 1f))
                        if (!confidence.isFinite() || confidence < .7f) continue
                        val column = i % columns; val row = i / columns
                        val cx = (column + packed[base+2]) * stride
                        val cy = (row + packed[base+3]) * stride
                        val w = exp(packed[base+4]) * stride; val h = exp(packed[base+5]) * stride
                        if (!w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) continue
                        // 패딩에만 존재하는 검출은 얼굴로 노출하지 않습니다.
                        if (cx !in 0f..width.toFloat() || cy !in 0f..height.toFloat()) continue
                        val landmarks = FloatArray(10) { j ->
                            ((packed[base+6+j] + if (j % 2 == 0) column else row) * stride / scale).toFloat()
                        }
                        if (landmarks.any { !it.isFinite() }) continue
                        candidates += PersonFace(RectF(((cx-w/2)/scale).toFloat(), ((cy-h/2)/scale).toFloat(),
                            ((cx+w/2)/scale).toFloat(), ((cy+h/2)/scale).toFloat()), landmarks, confidence)
                    }
                    offset += count*16
                }
        val kept = mutableListOf<PersonFace>()
        for (face in candidates.sortedByDescending { it.confidence }.take(5000)) {
            if (kept.none { overlap(face.bounds, it.bounds) > .3f }) kept += face
        }
        detectionNs += System.nanoTime() - start - (initializationNs-initialized)
        return kept
    }

    /** 검출과 인식은 서로 독립된 세션·대체 순서로 같은 픽셀을 재시도합니다. */
    private fun detectValues(nhwc: FloatArray): FloatArray {
        if (!useOnnxDetector) {
            while (true) {
                val route = routes[detectorRoute]
                try {
                    val runtime = liteDetector ?: createLite(PersonModels.requireDetector(context),640,134400,route).also { liteDetector = it }
                    val result = runtime.run(nhwc)
                    detectorBackend = "LiteRT $route"+if (route == "CPU") " 실행" else " 요청 경로 실행 · 연산별 분배 미검증"
                    return result
                } catch (failure: Exception) { detectorFallback(failure) }
                catch (failure: LinkageError) { detectorFallback(failure) }
                if (useOnnxDetector) break
            }
        }
        val runtime = detector ?: session("face-sort/yunet.onnx").also { detector = it }
        detectorBackend = "ONNX CPU 대체"
        // 원본 ONNX 대체 경로에서만 평면 입력을 만들며 같은 세션에서 재사용합니다.
        val plane = 640*640
        val nchw = onnxDetectionInput ?: FloatArray(nhwc.size).also { onnxDetectionInput=it }
        for (i in nchw.indices) nchw[i] = nhwc[(i%plane)*3+i/plane]
        val result = onnxDetectionOutput ?: FloatArray(134400).also { onnxDetectionOutput=it }
        var offset = 0
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(nchw),longArrayOf(1,3,640,640)).use { tensor ->
            runtime.run(mapOf("input" to tensor)).use { outputs ->
                for (stride in listOf(8,16,32)) {
                    val cls = (outputs.get("cls_$stride").get() as OnnxTensor).floatBuffer
                    val obj = (outputs.get("obj_$stride").get() as OnnxTensor).floatBuffer
                    val boxes = (outputs.get("bbox_$stride").get() as OnnxTensor).floatBuffer
                    val points = (outputs.get("kps_$stride").get() as OnnxTensor).floatBuffer
                    val count = (640/stride)*(640/stride)
                    check(cls.remaining() == count && obj.remaining() == count && boxes.remaining() == count*4 && points.remaining() == count*10)
                    repeat(count) { i ->
                        val base = offset+i*16
                        result[base] = cls[i]; result[base+1] = obj[i]
                        for (j in 0..3) result[base+2+j] = boxes[i*4+j]
                        for (j in 0..9) result[base+6+j] = points[i*10+j]
                    }
                    offset += count*16
                }
            }
        }
        return result
    }

    /** 검출기의 마지막 LiteRT CPU 오류는 원본 ONNX CPU로도 복구할 수 있습니다. */
    private fun detectorFallback(failure: Throwable) {
        liteDetector?.close(); liteDetector = null
        failures += "검출 ${routes[detectorRoute]}: ${failure.message ?: failure.javaClass.simpleName}"
        if (!allowFallback) throw failure
        if (detectorRoute == routes.lastIndex) useOnnxDetector = true else detectorRoute++
    }

    /** 성공·실패한 초기화 시간을 모두 측정하며 두 종류 모델이 같은 런타임 계약을 사용합니다. */
    private fun createLite(file: java.io.File, size: Int, dimensions: Int, route: String): PersonLiteRt {
        val start = System.nanoTime()
        try { return PersonLiteRt(context,file,size,dimensions,route) }
        finally { initializationNs += System.nanoTime()-start }
    }

    /** 겹친 검출만 제거하며 서로 다른 얼굴의 선택권은 유지합니다. */
    private fun overlap(a: RectF, b: RectF): Float {
        val intersection = maxOf(0f, minOf(a.right,b.right)-maxOf(a.left,b.left)) *
            maxOf(0f, minOf(a.bottom,b.bottom)-maxOf(a.top,b.top))
        val union = a.width()*a.height()+b.width()*b.height()-intersection
        return if (union > 0) intersection/union else 0f
    }

    /** 단순 확대 대신 다섯 랜드마크 유사변환으로 SFace 학습 좌표에 정렬합니다. */
    fun align(bitmap: Bitmap, face: PersonFace): Bitmap {
        val start = System.nanoTime()
        val affine = PersonMath.alignment(face.landmarks)
        val matrix = Matrix().apply { setValues(floatArrayOf(affine[0],affine[1],affine[2],affine[3],affine[4],affine[5],0f,0f,1f)) }
        return Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888).also { aligned ->
            Canvas(aligned).drawBitmap(bitmap,matrix,Paint(Paint.FILTER_BITMAP_FLAG))
            alignmentNs += System.nanoTime()-start
        }
    }

    /** 기본 MobileFaceNet 계열 SFace INT8 모델에서 단위 임베딩을 추출합니다. */
    fun embed(aligned: Bitmap): FloatArray {
        require(aligned.width == 112 && aligned.height == 112 && !aligned.isRecycled)
        val start = System.nanoTime()
        val initialized = initializationNs
        if (PersonModels.models.single { it.id == modelId }.recommendation.name.endsWith(".tflite")) {
            val pixels = embeddingPixels ?: IntArray(112*112).also { embeddingPixels=it }
            aligned.getPixels(pixels,0,112,0,0,112,112)
            val values = embeddingInput ?: FloatArray(pixels.size*3).also { embeddingInput=it }
            for (i in values.indices) values[i] = (pixels[i/3] ushr (16-i%3*8) and 255).toFloat()
            while (true) {
                val route = routes[routeIndex]
                try {
                    val session = liteEmbedder ?: createLite(PersonModels.requireFile(context,modelId),112,128,route).also { liteEmbedder = it }
                    val result = PersonMath.normalize(session.run(values))
                    embeddingNs += System.nanoTime()-start-(initializationNs-initialized)
                    embeddingBackend = "LiteRT $route"+(if (route == "CPU") " 실행" else " 요청 경로 실행 · 연산별 CPU 분배 미검증")
                    return result
                } catch (failure: Exception) { fallback(failure) }
                catch (failure: LinkageError) { fallback(failure) }
            }
        }
        check(allowFallback || backendMode == "cpu") { "이 ONNX 모델은 CPU만 지원합니다. CPU 모드 또는 대체 허용을 선택하세요." }
        embeddingBackend = "ONNX CPU · 선택한 ONNX 모델은 NPU/GPU 미지원"
        val runtime = embedder ?: openEmbedder().also { embedder = it }
        val pixels = embeddingPixels ?: IntArray(112*112).also { embeddingPixels=it }
        aligned.getPixels(pixels,0,112,0,0,112,112)
        // 공식 ONNX 모델 내부에서 정규화하므로 여기서는 RGB 0~255만 전달합니다.
        val values = embeddingInput ?: FloatArray(pixels.size*3).also { embeddingInput=it }
        for (i in values.indices) values[i] = (pixels[i%pixels.size] ushr (16-i/pixels.size*8) and 255).toFloat()
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(values),longArrayOf(1,3,112,112)).use { tensor ->
            runtime.run(mapOf(runtime.inputNames.single() to tensor)).use { result ->
                val buffer = (result[0] as OnnxTensor).floatBuffer
                check(buffer.remaining() == 128) { "SFace 출력 차원이 다릅니다." }
                val vector = FloatArray(128); buffer.get(vector)
                embeddingNs += System.nanoTime()-start-(initializationNs-initialized)
                return PersonMath.normalize(vector)
            }
        }
    }

    /** 초기화·추론 실패 시 같은 입력을 다음 가속기로 다시 실행합니다. */
    private fun fallback(failure: Throwable) {
        liteEmbedder?.close(); liteEmbedder = null
        failures += "${routes[routeIndex]}: ${failure.message ?: failure.javaClass.simpleName}"
        if (!allowFallback || routeIndex == routes.lastIndex) throw failure
        routeIndex++
    }

    /** 모델 교체 시 규격·해시를 다시 확인하고 검증 실패 세션도 반환합니다. */
    private fun openEmbedder(): OrtSession {
        val start = System.nanoTime()
        val file = PersonModels.requireFile(context,modelId)
        val model = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            environment.createSession(file.absolutePath,options)
        }
        try {
            check(model.inputNames.size == 1 && model.outputNames.size == 1) { "SFace 단일 입출력 모델이 필요합니다." }
            val info = model.inputInfo.values.single().info as TensorInfo
            check(info.shape.contentEquals(longArrayOf(1,3,112,112)) && info.type == ai.onnxruntime.OnnxJavaType.FLOAT) { "SFace 입력 규격이 다릅니다." }
            initializationNs += System.nanoTime()-start
            return model
        } catch (failure: Throwable) { model.close(); throw failure }
    }

    /** 분석 종료·취소에서 세션을 해제하고 전역 ONNX 환경은 보존합니다. */
    override fun close() {
        if (closed) return
        closed = true
        try { try { detector?.close() } finally {
            detector = null
            try { embedder?.close() } finally {
                embedder = null
                try { liteEmbedder?.close() } finally { liteEmbedder = null; liteDetector?.close(); liteDetector = null }
            }
        } } finally {
            detectionPixels?.fill(0); detectionPixels=null
            detectionInput?.fill(0f); detectionInput=null
            onnxDetectionInput?.fill(0f); onnxDetectionInput=null
            onnxDetectionOutput?.fill(0f); onnxDetectionOutput=null
            embeddingPixels?.fill(0); embeddingPixels=null
            embeddingInput?.fill(0f); embeddingInput=null
            sessions.decrementAndGet()
        }
    }
}
