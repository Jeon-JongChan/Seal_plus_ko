package com.plugin.compatibility

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import com.junkfood.seal.App
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 실제 파일 부재와 확인 불가를 구분하여 다운로드 이력만 정리합니다. */
object HistoryCleanup {
    /** 삭제 후보와 권한·공급자 문제로 보존하는 항목 수입니다. */
    data class Plan(val missing: List<DownloadedVideoInfo>, val unknown: Int)
    /** 파일 접근 결과는 권한 오류를 부재로 해석하지 않습니다. */
    enum class Presence { PRESENT, MISSING, UNKNOWN }

    /** 먼저 현재 폴더의 이동 파일을 재연결하고 남은 이력을 검사합니다. */
    suspend fun prepare(): Plan = withContext(Dispatchers.IO) {
        MediaLibrary.registerExternalFiles(MediaLibrary.listVideos(createPluginPreferences()))
        inspect(PluginDatabaseUtil.getDownloadHistory())
    }

    /** 검사 결과를 명시적 삭제 승인에 사용할 고정 목록으로 만듭니다. */
    suspend fun inspect(history: List<DownloadedVideoInfo>): Plan = withContext(Dispatchers.IO) {
        val checked = history.filterNot { PluginMediaMetadataStore.isProcessingExcluded(it.videoPath) }.map { it to presence(it.videoPath) }
        Plan(checked.filter { it.second == Presence.MISSING }.map { it.first }, checked.count { it.second == Presence.UNKNOWN })
    }

    /** 확인 이후 복구·재연결·생성된 파일은 다시 검사해 보존하고 파일 삭제는 하지 않습니다. */
    suspend fun remove(plan: Plan): Int = withContext(Dispatchers.IO) {
        val current = PluginDatabaseUtil.getDownloadHistory().associateBy { it.id }
        val removable = plan.missing.filter { info ->
            !PluginMediaMetadataStore.isProcessingExcluded(info.videoPath) &&
            current[info.id]?.let { it.videoPath == info.videoPath && it.videoUrl == info.videoUrl } == true &&
                presence(info.videoPath) == Presence.MISSING
        }
        PluginDatabaseUtil.deleteInfoListSafe(removable, deleteFile = false)
        removable.size
    }

    /** 공유 저장소 권한·읽기 가능한 상위 폴더·문서 조회를 확인한 경우만 부재를 확정합니다. */
    fun presence(path: String): Presence {
        if (path.isBlank()) return Presence.MISSING
        return try {
            val context = App.context
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
                    return Presence.UNKNOWN
                context.contentResolver.query(uri, null, null, null, null)?.use {
                    if (it.count > 0) Presence.PRESENT else Presence.MISSING
                } ?: Presence.UNKNOWN
            } else {
                val raw = File(if (path.startsWith("file://")) Uri.parse(path).path ?: return Presence.UNKNOWN else path)
                if (!raw.isAbsolute) return Presence.UNKNOWN
                val file = raw.canonicalFile
                if (file.isFile) return Presence.PRESENT
                val privateRoots = listOf(context.filesDir, context.cacheDir) + context.getExternalFilesDirs(null).filterNotNull()
                // Scoped Storage에서는 권한 없는 파일이 부재처럼 보일 수 있으므로 삭제 후보로 삼지 않습니다.
                if (file.path.startsWith("/storage/") && privateRoots.none { file.path.startsWith(it.canonicalPath + File.separator) }) {
                    val allowed = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
                        else context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
                            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    if (!allowed) return Presence.UNKNOWN
                }
                var parent = file.parentFile
                while (parent != null && !parent.exists()) parent = parent.parentFile
                if (parent?.isDirectory != true || !parent.canRead() || !parent.canExecute()) return Presence.UNKNOWN
                if (Build.VERSION.SDK_INT >= 26) {
                    try { java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java); Presence.UNKNOWN }
                    catch (_: java.nio.file.NoSuchFileException) { Presence.MISSING }
                } else if (!file.exists()) Presence.MISSING else Presence.UNKNOWN
            }
        } catch (_: Exception) { Presence.UNKNOWN }
    }
}
