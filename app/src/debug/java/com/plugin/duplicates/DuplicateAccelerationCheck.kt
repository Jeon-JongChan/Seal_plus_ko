package com.plugin.duplicates

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlinx.serialization.json.Json

/** 별도 테스트 프레임워크 없이 실제 Android 모델과 가속 대체 경로를 검사합니다. */
class DuplicateAccelerationCheck : Instrumentation() {
    /** am instrument가 생성한 검사 스레드에서 GPU 세션 수명 전체를 유지합니다. */
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    /** 두 모델의 전처리 동등성·기본 옵션·가속 선택·실행 중 실패 대체를 검증합니다. */
    override fun onStart() {
        val output = Bundle()
        try {
            waitForIdleSync()
            check(Json.decodeFromString<DuplicateOptions>("{}").backend == DuplicateBackend.AUTO)
            check(Json.decodeFromString<DuplicateOptions>("{\"model\":\"large\"}").backend == DuplicateBackend.AUTO)
            val saved = Json.encodeToString(DuplicateOptions.serializer(), DuplicateOptions(backend = DuplicateBackend.CPU))
            check(Json.decodeFromString<DuplicateOptions>(saved).backend == DuplicateBackend.CPU)
            val bitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(224 * 224) { index ->
                val x = index % 224; val y = index / 224
                android.graphics.Color.rgb(x, y, (x * 3 + y * 5) % 256)
            }
            bitmap.setPixels(pixels, 0, 224, 0, 0, 224, 224)
            val values = FloatArray(pixels.size * 3) { (pixels[it / 3] ushr (16 - it % 3 * 8) and 255) / 255f }
            val routes = mutableListOf<String>()
            try {
                for (size in listOf("small", "large")) {
                    val cpu = DuplicateLiteRt(targetContext, size, "CPU").use { it.embed(values) }
                    check(cpu.size == if (size == "small") 1024 else 1280)
                    ImageEmbedder.createFromOptions(targetContext, ImageEmbedder.ImageEmbedderOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("duplicate-ai/mobilenet_v3_$size.tflite")
                            .setDelegate(Delegate.CPU).build()).build()).use { reference ->
                        val image = BitmapImageBuilder(bitmap.copy(Bitmap.Config.ARGB_8888, false)).build()
                        try {
                            val similarity = DuplicateRules.cosine(cpu, reference.embed(image).embeddingResult().embeddings().first().floatEmbedding())
                            check(similarity > 0.999) { "$size 전처리 불일치: $similarity" }
                        } finally { image.close() }
                    }
                    DuplicateStore(targetContext).use { store ->
                        val vectors = mutableListOf<FloatArray>()
                        for (backend in DuplicateBackend.entries) {
                            DuplicateEngine(targetContext, DuplicateOptions(model = size, backend = backend), store).use { engine ->
                                val frameMethod = DuplicateEngine::class.java.getDeclaredMethod("frame", Bitmap::class.java).apply { isAccessible = true }
                                val first = frameMethod.invoke(engine, bitmap) as DuplicateFrame
                                val second = frameMethod.invoke(engine, bitmap) as DuplicateFrame
                                check(DuplicateRules.cosine(first.vector, second.vector) > 0.999)
                                vectors.add(first.vector)
                                check(engine.engine.contains("경로 실행"))
                                if (backend == DuplicateBackend.CPU) check(engine.engine == "LiteRT CPU 경로 실행")
                                routes.add("$size/${backend.name}: ${engine.engine}")
                            }
                        }
                        check(vectors.all { DuplicateRules.cosine(it, vectors.last()) > 0.995 }) { "$size 실행 장치별 임베딩 불일치" }
                        // 이미 닫힌 세션을 넣어 초기화 성공 뒤 첫 추론 실패도 같은 프레임으로 대체하는지 확인합니다.
                        DuplicateEngine(targetContext, DuplicateOptions(model = size), store).use { engine ->
                            val closed = DuplicateLiteRt(targetContext, size, "CPU").apply { close() }
                            DuplicateEngine::class.java.getDeclaredField("embedder").apply { isAccessible = true }.set(engine, closed)
                            val frame = DuplicateEngine::class.java.getDeclaredMethod("frame", Bitmap::class.java).apply { isAccessible = true }
                                .invoke(engine, bitmap) as DuplicateFrame
                            check(frame.vector.size == cpu.size && engine.engine.contains("대체"))
                        }
                    }
                }
            } finally { bitmap.recycle() }
            output.putString("stream", "두 모델 CPU 동등성·자동 기본값·옵션 저장·가속 실행·추론 실패 대체 통과\n" + routes.joinToString("\n"))
            finish(Activity.RESULT_OK, output)
        } catch (failure: Throwable) {
            output.putString("stream", android.util.Log.getStackTraceString(failure))
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
