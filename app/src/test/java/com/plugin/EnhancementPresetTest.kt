package com.plugin

import com.plugin.compatibility.enhancement.EnhancementPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 설정 단계의 기본값이 유효하고 저장 문자열이 손실·오염 없이 복원되는지 검사합니다. */
class EnhancementPresetTest {
    /** 모든 단계의 기본값이 저장 형식을 통과하고(허용 이름만 사용) 그대로 복원되어야 합니다. */
    @Test fun defaultsRoundTrip() {
        for (image in listOf(false, true)) for ((id, _) in EnhancementPreset.names.drop(1)) {
            val values = EnhancementPreset.defaultValues(id, image)
            assertEquals("$id/$image", values, EnhancementPreset.Values.decode(values.encode()))
            assertNotNull(EnhancementPreset.describe(values, image))
        }
    }

    /** 이미지 단계는 모델·선 축소를 포함하지 않아 영상 설정을 건드리지 않습니다. */
    @Test fun imageDefaultsLeaveVideoOnlyFieldsEmpty() {
        val image = EnhancementPreset.defaultValues("quality", true)
        assertEquals("", image.model); assertEquals("off", image.shrink)
    }

    /** 칸 수·알 수 없는 이름·범위 밖 값은 거부해 기본값으로 대체되게 합니다. */
    @Test fun corruptTextIsRejected() {
        assertNull(EnhancementPreset.Values.decode(""))
        assertNull(EnhancementPreset.Values.decode("a\tauto\tbuiltin\tnormal\toff\toff"))
        assertNull(EnhancementPreset.Values.decode("a\tbogus\tbuiltin\tnormal\toff\toff\t1"))
        assertNull(EnhancementPreset.Values.decode("a\tauto\tbogus\tnormal\toff\toff\t1"))
        assertNull(EnhancementPreset.Values.decode("a\tauto\tbuiltin\tnormal\toff\toff\t9"))
        assertNotNull(EnhancementPreset.Values.decode("a\tauto\tffmpeg\tnormal\toff\toff\t1"))
    }

    /** 내장 필터의 "보통"(σ 14)은 hqdn3d의 기본 인자(4:3:6:4.5)와 같아야 하고 강도에 비례해야 합니다. */
    @Test fun hqdn3dArgumentsFollowSigma() {
        assertEquals("hqdn3d=4.00:3.00:6.00:4.50", com.plugin.compatibility.enhancement.PreDenoise.hqdn3d(14f))
        assertEquals("hqdn3d=8.00:6.00:12.00:9.00", com.plugin.compatibility.enhancement.PreDenoise.hqdn3d(28f))
    }
}
