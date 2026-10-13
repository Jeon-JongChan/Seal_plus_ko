package com.plugin.faces

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.nio.FloatBuffer
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector as MediaPipeDetector

/** 스캔 전체에서 선택 모델 하나를 재사용하고 사용 후 네이티브 자원을 닫습니다. */
class FaceDetector(private val context: Context, model: String, private val threshold: Float,
    private val resolution: Int = 640, backend: String = "gpu") : AutoCloseable {
    private var session: OrtSession? = null
    private var mediaPipe: MediaPipeDetector? = null
    private val environment by lazy { OrtEnvironment.getEnvironment() }
    var engine: String = "YuNet · CPU 1스레드"
        private set
    private var gpu = false
    private var liteRt: YuNetLiteRt? = null
    private var gpuInput: FloatArray? = null

    init {
        require(model in setOf("yunet", "mediapipe") && threshold in 0.1f..0.9f &&
            resolution in FaceRules.resolutions && backend in setOf("gpu", "cpu"))
        if (model == "yunet") {
            if (backend == "gpu" && resolution != 0) {
                try {
                    liteRt = YuNetLiteRt(context, resolution)
                    engine = "YuNet · LiteRT GPU · 첫 실행 확인 중"
                } catch (failure: Exception) { useOnnxCpu(failure) }
                catch (failure: LinkageError) { useOnnxCpu(failure) }
            } else {
                useOnnxCpu()
                if (backend == "gpu") engine = "YuNet · CPU · 원본 크기 분석"
            }
        } else {
            try {
                gpu = backend == "gpu"
                mediaPipe = createMediaPipe(if (gpu) Delegate.GPU else Delegate.CPU)
                engine = if (gpu) "MediaPipe · GPU" else "MediaPipe · CPU"
            } catch (failure: RuntimeException) {
                if (!gpu) throw failure
                android.util.Log.w("FaceDetector", "GPU 초기화 실패로 CPU 사용", failure)
                gpu = false
                mediaPipe = createMediaPipe(Delegate.CPU)
                engine = "MediaPipe · CPU 대체"
            }
        }
    }

    /** 가속 실패 시 원본 ONNX와 동일한 점수 규칙으로 같은 이미지를 다시 검사합니다. */
    private fun useOnnxCpu(failure: Throwable? = null) {
        if (failure != null) android.util.Log.w("FaceDetector", "YuNet GPU 실패로 ONNX CPU 사용", failure)
        liteRt?.close(); liteRt = null; gpuInput = null
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            session = environment.createSession(context.assets.open("face-sort/yunet.onnx").use { it.readBytes() }, options)
        }
        engine = if (failure == null) "YuNet · CPU 1스레드" else "YuNet · CPU 대체"
    }

    /** GPU 생성·검사·해제는 서비스의 동일한 전용 스레드에서 실행합니다. */
    private fun createMediaPipe(delegate: Delegate): MediaPipeDetector = MediaPipeDetector.createFromOptions(context,
        MediaPipeDetector.FaceDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face-sort/blaze_face_short_range.tflite")
                .setDelegate(delegate).build())
            .setRunningMode(RunningMode.IMAGE).setMinDetectionConfidence(threshold).build())

    /** 얼굴이 하나라도 검출되면 원래 위치를 유지합니다. */
    fun hasFace(bitmap: Bitmap): Boolean {
        require(!bitmap.isRecycled) { "이미 해제된 이미지는 검사할 수 없습니다." }
        liteRt?.let { detector ->
            require(bitmap.width <= resolution && bitmap.height <= resolution)
            val input = gpuInput ?: FloatArray(resolution * resolution * 3).also { gpuInput = it }
            // 비율을 유지하고 오른쪽·아래를 패딩합니다. 이전 사진의 픽셀이 남지 않게 매번 초기화합니다.
            input.fill(0f)
            val pixels = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in pixels.indices) {
                    val offset = (y * resolution + x) * 3
                    input[offset] = (pixels[x] and 255).toFloat()
                    input[offset + 1] = (pixels[x] ushr 8 and 255).toFloat()
                    input[offset + 2] = (pixels[x] ushr 16 and 255).toFloat()
                }
            }
            try {
                val score = detector.score(input)
                engine = "YuNet · LiteRT GPU"
                return score >= threshold
            } catch (failure: Exception) { useOnnxCpu(failure) }
            catch (failure: LinkageError) { useOnnxCpu(failure) }
        }
        mediaPipe?.let { detector ->
            // MPImage.close()는 전달받은 Bitmap을 해제하므로 호출자가 소유한 입력은 복사합니다.
            val image = BitmapImageBuilder(bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("얼굴 검사 이미지를 준비할 수 없습니다.")).build()
            try {
                return try { detector.detect(image).detections().isNotEmpty() }
                catch (failure: RuntimeException) {
                    if (!gpu) throw failure
                    // 실행 오류를 얼굴 없음으로 취급하지 않고 같은 이미지를 CPU로 다시 확인합니다.
                    detector.close()
                    mediaPipe = null
                    gpu = false
                    mediaPipe = createMediaPipe(Delegate.CPU)
                    engine = "MediaPipe · CPU 대체"
                    mediaPipe!!.detect(image).detections().isNotEmpty()
                }
            }
            finally { image.close() }
        }
        // OpenCV와 동일한 BGR·0~255 입력과 오른쪽·아래쪽 0 패딩을 사용합니다.
        val width = ((bitmap.width + 31) / 32) * 32
        val height = ((bitmap.height + 31) / 32) * 32
        val plane = width * height
        val input = FloatArray(plane * 3)
        val pixels = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in pixels.indices) {
                val offset = y * width + x
                input[offset] = (pixels[x] and 255).toFloat()
                input[plane + offset] = (pixels[x] ushr 8 and 255).toFloat()
                input[plane * 2 + offset] = (pixels[x] ushr 16 and 255).toFloat()
            }
        }
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, height.toLong(), width.toLong())).use { tensor ->
            val requested = setOf("cls_8", "cls_16", "cls_32", "obj_8", "obj_16", "obj_32")
            session!!.run(mapOf("input" to tensor), requested).use { result ->
                for (stride in listOf(8, 16, 32)) {
                    val classes = (result.get("cls_$stride").get() as OnnxTensor).floatBuffer
                    val objects = (result.get("obj_$stride").get() as OnnxTensor).floatBuffer
                    check(classes.remaining() == objects.remaining()) { "YuNet 출력 크기가 맞지 않습니다." }
                    // 존재 여부만 필요하므로 최고 점수를 남기는 중복 박스 제거는 생략합니다.
                    while (classes.hasRemaining()) if (FaceRules.score(classes.get(), objects.get()) >= threshold) return true
                }
            }
        }
        return false
    }

    /** 모델 실행 세션만 해제하며 앱 전체에서 공유하는 ONNX 환경은 보존합니다. */
    override fun close() {
        try { liteRt?.close() } finally {
            gpuInput = null
            try { mediaPipe?.close() } finally { session?.close() }
        }
    }
}
