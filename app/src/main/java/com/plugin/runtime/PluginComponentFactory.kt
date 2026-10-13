package com.plugin.runtime

import android.app.Application
import androidx.core.app.CoreComponentFactory

/** AI 프로세스에서는 원본 앱의 다운로드·쿠키 초기화를 실행하지 않습니다. */
class PluginComponentFactory : CoreComponentFactory() {
    /** Android 9 이상에서 보정 전용 프로세스에는 최소 Application만 생성합니다. */
    override fun instantiateApplication(cl: ClassLoader, className: String): Application =
        if (Application.getProcessName().substringAfterLast(':') in setOf("image_ai", "image_ai_gpu", "image_ai_cpu")) Application()
        else super.instantiateApplication(cl, className)
}
