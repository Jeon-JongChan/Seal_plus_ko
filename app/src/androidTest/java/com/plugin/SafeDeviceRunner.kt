package com.plugin

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.tencent.mmkv.MMKV
import com.junkfood.seal.util.AUDIO_DIRECTORY
import com.junkfood.seal.util.PRIVATE_DIRECTORY
import com.junkfood.seal.util.VIDEO_DIRECTORY
import java.io.File

/** 실기기 전용 별도 검사 패키지는 앱 시작 전부터 다운로드 루트를 자체 캐시로 제한합니다. */
class SafeDeviceRunner : AndroidJUnitRunner() {
    /** 기존 Release 앱의 설정을 읽거나 바꾸지 않고 테스트 앱의 초기 경로만 준비합니다. */
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        val application = super.newApplication(cl, className, context)
        val target = requireNotNull(context)
        check(target.packageName.endsWith(".devicecheck")) { "실기기 검사는 별도 패키지에서만 실행합니다." }
        MMKV.initialize(target)
        val root = File(target.cacheDir, "validation-downloads").apply { mkdirs() }
        MMKV.defaultMMKV().apply {
            encode(VIDEO_DIRECTORY, root.path)
            encode(AUDIO_DIRECTORY, File(root, "Audio").path)
            encode(PRIVATE_DIRECTORY, false)
        }
        return application
    }
}
