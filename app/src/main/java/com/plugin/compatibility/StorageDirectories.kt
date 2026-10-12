package com.plugin.compatibility

import android.os.Environment
import com.junkfood.seal.util.PRIVATE_DIRECTORY
import com.junkfood.seal.util.VIDEO_DIRECTORY
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getString
import java.io.File

/** 앱 시작·임시 다운로드에서 현재 비공개·사용자 지정 저장 경로를 공통으로 사용합니다. */
object StorageDirectories {
    /** 비공개 모드는 .Seal, 공개 모드는 지정 경로를 사용하고 기본 경로는 설정이 없을 때만 선택합니다. */
    internal fun selected(privateDirectory: Boolean, configured: String?, downloads: File): File =
        if (privateDirectory) File(downloads, ".Seal")
        else configured?.takeIf { it.isNotBlank() && !it.startsWith("content://") }?.let(::File) ?: File(downloads, "Seal")

    /** 실제 필요한 저장 루트만 만들며 쓰기 접근 실패는 기존 다운로드 경로에서 처리합니다. */
    fun externalDownloadDirectory(): File = selected(PRIVATE_DIRECTORY.getBoolean(), VIDEO_DIRECTORY.getString(""),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)).also { it.mkdirs() }
}
