package com.plugin.enhancement

import android.content.Context
import android.graphics.Bitmap

/** LiteRT v2의 공통 NPU·GPU API를 사용하고 타일 처리는 ONNX와 공유합니다. */
object LiteRtEnhancer {
    /** GPU 환경 생성과 추론을 같은 코루틴 구간·스레드에서 수행합니다. */
    suspend fun process(context: Context, source: Bitmap, model: String, target: Pair<Int, Int>,
        progress: (Int) -> Unit, accelerated: Boolean, threads: Int, allowNpu: Boolean = true, engine: (String) -> Unit): Bitmap {
        LiteRtSession(context, model, accelerated, EnhancementRules.cpuThreads(threads, Runtime.getRuntime().availableProcessors()), allowNpu).use { session ->
            engine("LiteRT v2 · ${session.route()} · 첫 타일 확인 중")
            var measured = false
            val inspect = {
                if (!measured) {
                    measured = true
                    engine(if (!accelerated) "실제 실행 · LiteRT v2 ${session.route()}"
                        else "LiteRT v2 · ${session.route()} 실행 · 실제 연산 분배 정보는 제공되지 않습니다.")
                }
            }
            // 타일 루프에는 스레드를 바꾸는 중단 지점이 없어 GPU 핸들을 같은 스레드에서 사용합니다.
            return ImageTiles.process(source, target, session.inputWidth(), session.inputHeight(), false, progress, inspect) { floats, consume ->
                consume(session.infer(floats), session.outputWidth(), session.outputHeight())
            }
        }
    }
}
