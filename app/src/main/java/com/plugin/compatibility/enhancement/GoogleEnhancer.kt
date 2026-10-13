package com.plugin.compatibility.enhancement

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.android.gms.common.api.Status
import com.google.android.gms.media.effect.enhancement.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google이 지원한다고 응답한 기기에서만 사진 보정 모듈을 사용합니다. */
object GoogleEnhancer {
    /** 지원 여부와 모듈 설치 상태를 분리해 표시합니다. */
    suspend fun availability(context: Context): Pair<Boolean, Boolean> {
        if (Build.VERSION.SDK_INT < 30) return false to false
        return withTimeout(15_000) {
            val client = Enhancement.getClient(context)
            val supported = client.isDeviceSupported().awaitAi()
            supported to (supported && client.isModuleInstalled().awaitAi())
        }
    }

    /** 사용자가 요청할 때만 Google 모델 모듈을 설치합니다. */
    suspend fun install(context: Context): Boolean = withTimeout(180_000) {
        check(Build.VERSION.SDK_INT >= 30) { "Google 보정은 Android 11 이상에서 확인할 수 있습니다." }
        val client = Enhancement.getClient(context)
        check(client.isDeviceSupported().awaitAi()) { "이 기기는 Google 보정을 지원하지 않습니다." }
        client.installModule(null).awaitAi()
    }

    /** 사진 보정과 네이티브 업스케일을 실행하고 세션을 반드시 해제합니다. */
    suspend fun process(context: Context, source: Bitmap, upscale: Boolean): Bitmap = withTimeout(120_000) {
        check(Build.VERSION.SDK_INT >= 30) { "Google 보정을 지원하지 않습니다." }
        val options = EnhancementOptions(source.width, source.height, EnhancementMode.BITMAP,
            true, true, false, upscale, false)
        val session = Enhancement.getClient(context).createSession(options, object : EnhancementSessionCallback {
            /** 반환 Task에서 세션을 받아 처리합니다. */
            override fun onSessionCreated(session: EnhancementSession) {}
            /** 생성 실패는 반환 Task 또는 제한 시간에서 처리합니다. */
            override fun onSessionCreationFailed(status: Status) {}
            /** 연결 종료는 처리 콜백 또는 제한 시간에서 처리합니다. */
            override fun onSessionDisconnected(status: Status) {}
            /** 세션 해제 후 추가 작업을 수행하지 않습니다. */
            override fun onSessionDestroyed() {}
        }).awaitAi() ?: error("Google 보정 세션을 만들 수 없습니다.")
        try {
            suspendCancellableCoroutine { continuation ->
                session.process(source, options, object : EnhancementCallback {
                    /** 입력과 다른 결과 비트맵은 취소된 경우 바로 해제합니다. */
                    override fun onBitmapProcessed(bitmap: Bitmap) {
                        if (continuation.isActive) continuation.resume(bitmap)
                        else if (bitmap !== source) bitmap.recycle()
                    }
                    /** 기기의 처리 오류를 ONNX 대체 처리 여부 판단에 전달합니다. */
                    override fun onError(statusCode: Int) {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Google 보정 오류: $statusCode"))
                    }
                    /** 이미지 요청에서는 Surface 결과를 사용하지 않습니다. */
                    override fun onSurfaceProcessed(timestamp: Long) {}
                })
            }
        } finally { session.release() }
    }
}

/** 추가 의존성 없이 Google 비동기 결과를 코루틴에서 기다립니다. */
internal suspend fun <T> Task<T>.awaitAi(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
