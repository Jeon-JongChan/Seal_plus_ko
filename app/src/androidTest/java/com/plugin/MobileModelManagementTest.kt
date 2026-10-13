package com.plugin

import androidx.test.platform.app.InstrumentationRegistry
import com.plugin.compatibility.enhancement.EnhancementModels
import com.plugin.compatibility.enhancement.LiteRtSession
import com.plugin.compatibility.enhancement.EnhancementClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 모바일 공식 모델의 실제 CPU 입출력과 승인된 모델 삭제 경계를 검증합니다. */
class MobileModelManagementTest {
    /** 선택 모델 삭제 후 기본 모델로 전환하며 내장 자산과 폴더 밖 파일을 보호합니다. */
    @Test fun deletionProtectsAssetsAndResetsSelection(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val file = File(EnhancementModels.internalRoot(context), "check-${java.util.UUID.randomUUID()}.tflite").apply { writeText("검사") }
        val outside = File(context.cacheDir, "check-${java.util.UUID.randomUUID()}.onnx").apply { writeText("검사") }
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val existed = kv.containsKey(EnhancementModels.SELECTED)
        val previous = kv.decodeString(EnhancementModels.SELECTED, "")
        try {
            assertTrue(EnhancementModels.scan(context).any { it.source == file.path })
            kv.encode(EnhancementModels.SELECTED, file.path)
            assertTrue(runCatching { EnhancementModels.delete(context, EnhancementModels.Model("외부", outside.path, outside.length())) }.isFailure)
            assertTrue(outside.exists())
            assertTrue(runCatching { EnhancementModels.delete(context, EnhancementModels.Model("기본", EnhancementModels.DEFAULT_MODEL, 0)) }.isFailure)
            EnhancementModels.delete(context, EnhancementModels.Model(file.name, file.path, file.length()))
            assertFalse(file.exists())
            assertEquals(EnhancementModels.DEFAULT_MODEL, kv.decodeString(EnhancementModels.SELECTED))
        } finally {
            if (existed) kv.encode(EnhancementModels.SELECTED, previous) else kv.removeValueForKey(EnhancementModels.SELECTED)
            file.delete(); outside.delete()
        }
    }

    /** 공식 고품질 모델의 다른 텐서 이름과 128→512 크기를 실제 Android LiteRT로 확인합니다. */
    @Test fun officialMobileModelRunsOnCpu(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = InstrumentationRegistry.getArguments().getString("mobile_model") ?: error("공식 검증 모델 경로가 필요합니다.")
        val staged = File(context.cacheDir, "mobile-check-${java.util.UUID.randomUUID()}.tflite")
        try {
            EnhancementModels.stage(context, source, staged)
            assertEquals("e343ce22fe9b3ddd43bee4812bd061b5a03dce527bc05d1843c4aeef1590aab3", EnhancementModels.hash(staged))
            LiteRtSession(context, staged.path, false, 1, false).use { session ->
                assertEquals(128, session.inputWidth()); assertEquals(128, session.inputHeight())
                assertEquals(512, session.outputWidth()); assertEquals(512, session.outputHeight())
                val output = session.infer(FloatArray(128 * 128 * 3) { 0.5f })
                assertEquals(512 * 512 * 3, output.size)
                assertTrue(output.all { it.isFinite() })
                assertEquals(0.51052254, output.average(), 0.0002)
            }
            val kv = com.tencent.mmkv.MMKV.defaultMMKV()
            val keys = listOf(EnhancementModels.SELECTED, EnhancementModels.ACCELERATED, EnhancementModels.GOOGLE_FIRST, EnhancementModels.BACKEND, EnhancementModels.NPU_FIRST)
            val existed = keys.filter(kv::containsKey).toSet()
            val previousModel = kv.decodeString(keys[0], ""); val previousAccelerated = kv.decodeBool(keys[1], true)
            val previousGoogle = kv.decodeBool(keys[2], false); val previousBackend = kv.decodeString(keys[3], "litert")
            val previousNpu = kv.decodeBool(keys[4], false)
            val image = File(context.cacheDir, "mobile-image-${java.util.UUID.randomUUID()}.png")
            var preview: EnhancementClient.Preview? = null
            try {
                val bitmap = android.graphics.Bitmap.createBitmap(16, 12, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GRAY) }
                image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                val original = image.readBytes()
                kv.encode(keys[0], staged.path); kv.encode(keys[1], true); kv.encode(keys[2], false)
                // 이전 ONNX 설정이 남아 있어도 실제 TFLite 파일은 LiteRT로 실행해야 합니다.
                kv.encode(keys[3], "onnx"); kv.encode(keys[4], false)
                preview = EnhancementClient.enhance(context, image.path, 2) {}
                assertEquals(32, preview.width); assertEquals(24, preview.height)
                assertTrue(preview.engine.contains("LiteRT"))
                assertArrayEquals(original, image.readBytes())
                assertTrue(kv.decodeBool(keys[1]))
                android.util.Log.i("MobileModelCheck", "실행 경로: ${preview.engine}")
            } finally {
                preview?.let(EnhancementClient::discard); image.delete()
                kv.encode(keys[0], previousModel); kv.encode(keys[1], previousAccelerated); kv.encode(keys[2], previousGoogle)
                kv.encode(keys[3], previousBackend); kv.encode(keys[4], previousNpu)
                (keys - existed).forEach(kv::removeValueForKey)
            }
        } finally { staged.delete() }
    }
}
