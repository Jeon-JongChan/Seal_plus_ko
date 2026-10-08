package com.junkfood.seal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 Android 환경에서 빌드 변형에 맞는 앱 컨텍스트를 검증합니다. */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    /** 디버그·배포 채널 접미사를 포함한 실제 앱 ID를 비교합니다. */
    @Test
    fun useAppContext() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, appContext.packageName)
    }
}
