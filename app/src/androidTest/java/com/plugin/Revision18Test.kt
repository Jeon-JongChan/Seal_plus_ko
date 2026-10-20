package com.plugin

import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.plugin.compatibility.enhancement.EnhancementModels
import com.plugin.compatibility.enhancement.EnhancementPreset
import com.plugin.compatibility.enhancement.EnhancementSampling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18차: 설정 단계가 개별 옵션을 채우고, 수정·저장·초기화가 실제 저장소에서 동작하는지 검사합니다. */
class Revision18Test {
    private val stringKeys = listOf(EnhancementModels.VIDEO_DENOISE, EnhancementModels.IMAGE_DENOISE, EnhancementModels.DENOISE_STRENGTH,
        EnhancementModels.SHARPEN, EnhancementModels.VIDEO_SHRINK, EnhancementModels.VIDEO_SELECTED,
        "video_ai_preset", "image_ai_preset", "ai_preset_video_quality", "ai_preset_image_best")

    /** 사용자의 기존 설정을 보존하도록 시작 값을 저장해 두었다가 끝나면 되돌립니다. */
    @Test fun presetApplySaveAndReset() {
        val before = stringKeys.associateWith { it.getString("") }; val sampling = EnhancementSampling.KEY.getInt(1)
        try {
            // 단계를 고르면 개별 옵션이 그 단계의 기본값으로 채워집니다(영상: 모델 포함).
            EnhancementPreset.select("quality", false)
            assertEquals(EnhancementPreset.defaultValues("quality", false), EnhancementPreset.current(false))
            assertEquals(EnhancementModels.MEDIUM_MODEL, EnhancementModels.VIDEO_SELECTED.getString(""))
            assertEquals("quality", EnhancementPreset.selected(false))

            // 개별 옵션을 바꾸면 단계 값과 달라지고, 저장하면 다시 같아집니다.
            EnhancementModels.SHARPEN.updateString("strong"); EnhancementPreset.edited()
            assertNotEquals(EnhancementPreset.values("quality", false), EnhancementPreset.current(false))
            EnhancementPreset.save("quality", false)
            assertTrue(EnhancementPreset.hasSaved("quality", false))
            assertEquals(EnhancementPreset.values("quality", false), EnhancementPreset.current(false))

            // 다른 단계로 갔다가 돌아오면 저장한 값이 복원됩니다.
            EnhancementPreset.select("speed", false)
            assertEquals(EnhancementModels.SMALL_MODEL, EnhancementModels.VIDEO_SELECTED.getString(""))
            assertEquals("off", EnhancementModels.SHARPEN.getString(""))
            EnhancementPreset.select("quality", false)
            assertEquals("strong", EnhancementModels.SHARPEN.getString(""))

            // 되돌리기는 저장값을 지우고 기본값(light)을 적용합니다.
            EnhancementPreset.reset("quality", false)
            assertFalse(EnhancementPreset.hasSaved("quality", false))
            assertEquals("light", EnhancementModels.SHARPEN.getString(""))

            // 커스텀은 값을 바꾸지 않습니다.
            val keep = EnhancementPreset.current(false)
            EnhancementPreset.select(EnhancementPreset.CUSTOM, false)
            assertEquals(keep, EnhancementPreset.current(false))

            // 이미지 단계는 영상 모델·선 축소를 건드리지 않습니다.
            val model = EnhancementModels.VIDEO_SELECTED.getString(""); val shrink = EnhancementModels.VIDEO_SHRINK.getString("")
            EnhancementPreset.select("best", true)
            assertEquals(model, EnhancementModels.VIDEO_SELECTED.getString("")); assertEquals(shrink, EnhancementModels.VIDEO_SHRINK.getString(""))
            assertEquals(EnhancementPreset.defaultValues("best", true), EnhancementPreset.current(true))
        } finally {
            before.forEach { (key, value) -> key.updateString(value) }; EnhancementSampling.KEY.updateInt(sampling)
        }
    }
}
