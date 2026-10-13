package com.plugin

import androidx.compose.runtime.mutableStateMapOf
import com.junkfood.seal.util.DownloadType
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.plugin.compatibility.*

/** 서로 독립적으로 활성화할 수 있는 내장 확장 기능입니다. */
enum class PluginId {
    SOCIAL, LIST, MEDIA, LIBRARY, QUEUE, INTERACTION, IMAGE_AI, ACCOUNTS, POST_INFO, FOLDERS, FACE_SORT, VIDEO_AI, SHARE_MENU;

    val preferenceKey: String get() = "plugin_enabled_${name.lowercase()}"
}

/** 기존 MMKV 저장소를 재사용하며 변경 즉시 Compose 화면에 반영합니다. */
object PluginState {
    private val enabled = mutableStateMapOf<PluginId, Boolean>()

    /** 화면의 스냅샷이 생성되기 전에 저장된 상태를 준비합니다. */
    fun initialize() {
        PluginId.entries.forEach { id -> enabled[id] = id.preferenceKey.getBoolean(false) }
    }

    /** 기존 설치를 포함해 저장된 활성화 값이 없으면 기능을 끕니다. */
    fun isEnabled(id: PluginId): Boolean = enabled[id] ?: id.preferenceKey.getBoolean(false)

    /** 설정만 변경하고 기존 작업·파일·기능별 세부 설정은 보존합니다. */
    fun setEnabled(id: PluginId, value: Boolean) {
        id.preferenceKey.updateBoolean(value)
        enabled[id] = value
        if (id == PluginId.SHARE_MENU) ShareMenuSettings.synchronize(com.junkfood.seal.App.context)
    }

    /** 새 요청에는 현재 켜진 기능의 설정만 적용합니다. */
    fun preferences(value: DownloadPreferences): DownloadPreferences =
        PluginPolicy.preferences(value, PluginId.entries.filter(::isEnabled).toSet())

    /** 사용 가능한 유형 목록은 기존 사용자 명령 설정도 존중합니다. */
    fun downloadTypes(command: Boolean): List<DownloadType> =
        PluginPolicy.downloadTypes(PluginId.entries.filter(::isEnabled).toSet(), command)
}

/** Android 저장소 없이 검사할 수 있는 기능별 설정 적용 규칙입니다. */
object PluginPolicy {
    /** 비활성화한 기능의 저장값을 지우지 않고 요청 사본에서만 배제합니다. */
    fun preferences(value: DownloadPreferences, enabled: Set<PluginId>): DownloadPreferences =
        value.copyPluginOptions(
            mediaSelection = if (PluginId.SOCIAL in enabled || (PluginId.LIST in enabled && value.listProcessing)) value.mediaSelection else 0,
            imageResolution = if (PluginId.SOCIAL in enabled || (PluginId.LIST in enabled && value.listProcessing)) value.imageResolution else 0,
            downloadAllMedia = (PluginId.LIST in enabled || PluginId.SOCIAL in enabled) && value.downloadAllMedia,
            selectedFolder = if (PluginId.FOLDERS in enabled) value.selectedFolder else null,
            classificationUpperFirst = PluginId.LIBRARY in enabled && value.classificationUpperFirst,
            classifyMedia = PluginId.LIBRARY in enabled && value.classifyMedia,
            websiteFolderTarget = if (PluginId.LIBRARY in enabled) value.websiteFolderTarget else 0,
            transcodeAv1 = PluginId.MEDIA in enabled && value.transcodeAv1,
            keepAv1Original = PluginId.MEDIA in enabled && value.keepAv1Original,
            mediaProcessing = PluginId.MEDIA in enabled || PluginId.LIBRARY in enabled,
            imageFolderName = if (PluginId.LIBRARY in enabled) value.imageFolderName else "",
            videoFolderName = if (PluginId.LIBRARY in enabled) value.videoFolderName else "",
            platformFolders = PluginId.LIBRARY in enabled && value.platformFolders,
            reuseExisting = PluginId.MEDIA in enabled,
            queueEnhancements = PluginId.QUEUE in enabled,
            socialProcessing = PluginId.SOCIAL in enabled,
            listProcessing = PluginId.LIST in enabled && value.listProcessing,
            videoContainer = if (PluginId.MEDIA in enabled) value.videoContainer else "mp4",
            cookieProfileId = if (PluginId.INTERACTION in enabled || value.cookieProfileExplicit) value.cookieProfileId else -1,
        )

    /** 추가 유형은 해당 기능이 켜졌을 때만 제공하고 원본 유형의 순서를 유지합니다. */
    fun downloadTypes(enabled: Set<PluginId>, command: Boolean): List<DownloadType> = buildList {
        add(DownloadType.Video)
        add(DownloadType.Audio)
        add(DownloadType.Playlist)
        if (command) add(DownloadType.Command)
    }
}
