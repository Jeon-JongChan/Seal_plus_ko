package com.plugin.compatibility

import android.database.sqlite.SQLiteDatabase
import com.junkfood.seal.App
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.plugin.runtime.pluginQueue

/**
 * 플러그인 전용 데이터베이스 보조 유틸리티입니다.
 * 원본 DatabaseUtil 및 VideoInfoDao의 수정을 방지하고 필요한 작업을 처리합니다.
 */
object PluginDatabaseUtil {
    private const val DATABASE_NAME = "app_database"

    /** 원본 SQLite 데이터베이스를 열어 직접 쿼리를 실행할 때 사용합니다. */
    private fun getWritableDatabase(): SQLiteDatabase {
        return SQLiteDatabase.openDatabase(
            TemporarySession.databasePath(),
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
    }

    /** 이력을 즉시 저장하고 메타데이터를 MMKV에 보관합니다. */
    suspend fun insertInfoImmediately(info: DownloadedVideoInfo) = withContext(Dispatchers.IO) {
        DatabaseUtil.insertInfo(info)
        // 원본 삽입은 별도 코루틴이므로 이력이 실제 표시될 때까지 기다립니다.
        kotlinx.coroutines.withTimeout(10_000) {
            DatabaseUtil.getDownloadHistoryFlow().first { items -> items.any { it.videoPath == info.videoPath } }
        }
        Unit
    }

    /** 파일 이동 시 이력 경로를 변경하고 메타데이터 키를 갱신합니다. */
    suspend fun updateMediaPath(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        if (oldPath.isBlank() || newPath.isBlank() || oldPath == newPath) return@withContext
        getWritableDatabase().use { db ->
                db.execSQL(
                    "UPDATE DownloadedVideoInfo SET videoPath = ?, thumbnailUrl = CASE WHEN thumbnailUrl = ? THEN ? ELSE thumbnailUrl END, videoUrl = CASE WHEN videoUrl = ? THEN ? ELSE videoUrl END WHERE videoPath = ?",
                    arrayOf(newPath, oldPath, newPath, oldPath, newPath, oldPath)
                )
        }
        PluginMediaMetadataStore.updatePath(oldPath, newPath)
        // 폴더 이동·분류·뷰어의 경로 복구가 완료 대기열에도 같은 결과를 전달합니다.
        withContext(Dispatchers.Main.immediate) {
            // 화면 진입 전에는 원본 다운로더가 있을 수 있어 연결된 플러그인 대기열만 갱신합니다.
            (org.koin.core.context.GlobalContext.getOrNull()?.getOrNull<com.junkfood.seal.download.DownloaderV2>()
                as? com.plugin.runtime.PluginDownloaderV2)?.queue?.let { queue ->
                queue.getTaskStateMap().keys.toList().forEach { task ->
                    queue.getTaskStateMap().updateTaskState(task) { QueueMedia.relocate(it, oldPath, newPath) }
                }
            }
        }
    }

    /** 백업 이력을 이동된 파일에 연결하며 같은 파일의 임시 외부 이력만 원자적으로 합칩니다. */
    suspend fun reconnectMediaPath(oldPath: String, newPath: String) = withContext(Dispatchers.IO) {
        getWritableDatabase().use { db ->
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM DownloadedVideoInfo WHERE videoPath = ? AND extractor = 'External'",
                    arrayOf(newPath))
                db.execSQL("UPDATE DownloadedVideoInfo SET videoPath = ?, thumbnailUrl = CASE WHEN thumbnailUrl = ? THEN ? ELSE thumbnailUrl END WHERE videoPath = ?",
                    arrayOf(newPath, oldPath, newPath, oldPath))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        PluginMediaMetadataStore.updatePath(oldPath, newPath)
    }

    /** 경로로 이력 레코드와 메타데이터를 삭제합니다. */
    suspend fun deleteInfoByPath(path: String, removedFromStorage: Boolean = false) = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext
        runCatching {
            getWritableDatabase().use { db ->
                db.execSQL("DELETE FROM DownloadedVideoInfo WHERE videoPath = ?", arrayOf(path))
            }
        }
        PluginMediaMetadataStore.delete(path)
        if (removedFromStorage) removeCompletedPath(path)
    }

    /** 파일 삭제만 대기열에 반영하고 이력 정리나 같은 경로의 파일 교체는 보존합니다. */
    private fun removeCompletedPath(path: String) {
        // 삭제 직후 완료 대기열의 경로도 제거해 앱 재시작 전 빈 페이지가 남지 않게 합니다.
        runCatching {
            val queue = org.koin.core.context.GlobalContext.get().get<com.junkfood.seal.download.DownloaderV2>().pluginQueue()
            queue.getTaskStateMap().toMap().forEach { (task, _) ->
                queue.getTaskStateMap().updateTaskState(task) { state ->
                    val completed = state.downloadState as? com.plugin.queue.PluginTask.DownloadState.Completed
                    // 뷰어가 복구한 경로는 작업에 저장된 경로와 표기가 다를 수 있어 동일 파일 비교를 사용합니다.
                    if (completed == null || completed.filePaths.none { MediaRules.sameFilePath(it, path) }) state else {
                        val remaining = completed.filePaths.filterNot { MediaRules.sameFilePath(it, path) }
                        state.copy(downloadState = com.plugin.queue.PluginTask.DownloadState.Completed(
                            remaining.firstOrNull(), remaining, remaining.isEmpty()))
                    }
                }
            }
        }
    }

    /** 전체 다운로드 이력 목록을 조회합니다. */
    suspend fun getDownloadHistory(): List<DownloadedVideoInfo> = withContext(Dispatchers.IO) {
        DatabaseUtil.getDownloadHistoryFlow().first()
    }

    /** 파일 삭제 실패 시 DB 이력을 보존하며 안전하게 이력을 삭제합니다. */
    suspend fun deleteInfoListSafe(infoList: List<DownloadedVideoInfo>, deleteFile: Boolean = false) = withContext(Dispatchers.IO) {
        val removable = if (!deleteFile) infoList else infoList.filter { info ->
            CompatibilityFiles.deleteFile(info.videoPath).onFailure {
                ToastUtil.makeToastSuspend(App.context.getString(com.junkfood.seal.R.string.loc_file_delete_failed))
            }.isSuccess
        }
        DatabaseUtil.deleteInfoList(removable)
        removable.forEach {
            PluginMediaMetadataStore.delete(it.videoPath)
            if (deleteFile) removeCompletedPath(it.videoPath)
        }
    }
}

