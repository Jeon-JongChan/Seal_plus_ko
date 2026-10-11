package com.plugin.compatibility

import com.junkfood.seal.database.VideoInfoDao
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadUtil

/** 데이터 형식·저장소·기존 이력을 보존하며 사용자가 비활성화할 수 없습니다. */
object CompatibilityPlugin {
    const val enabled: Boolean = true

    /** 기존 이력의 메타데이터를 보완하고 같은 경로의 중복을 방지합니다. */
    suspend fun insertInfo(dao: VideoInfoDao, info: DownloadedVideoInfo) {
        val quality = info.quality ?: MediaLibrary.readMediaQuality(info.videoPath)
        PluginMediaMetadataStore.save(info.videoPath, quality = quality)
        dao.insertInfoDistinctByPath(info)
    }

    /** 파일 이동 후에도 이전 이력의 경로를 보존·복구합니다. */
    suspend fun resolveHistory(list: List<DownloadedVideoInfo>): List<DownloadedVideoInfo> {
        val preferences = com.plugin.compatibility.createPluginPreferences()
        return list.map { info ->
            val path = MediaLibrary.resolveDownloadedPath(info.videoPath, preferences)
            if (path != null && path != info.videoPath) {
                PluginDatabaseUtil.updateMediaPath(info.videoPath, path)
                info.copy(videoPath = path)
            } else info
        }.reversed()
    }
}
