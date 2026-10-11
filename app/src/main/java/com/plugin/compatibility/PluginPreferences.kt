package com.plugin.compatibility

/** 선택 기능 설정을 Seal 설정 저장소와 분리해 관리합니다. */
object PluginPreferences {
    const val QUICK_COOKIE_PROFILE = "quick_cookie_profile"
    const val MEDIA_SELECTION = "media_selection"
    const val DOWNLOAD_ALL_MEDIA = "download_all_media"
    const val MEDIA_INFO_OVERLAY = "media_info_overlay"
    const val IMAGE_FOLDER_NAME = "image_folder_name"
    const val VIDEO_FOLDER_NAME = "video_folder_name"
    const val PLATFORM_FOLDERS = "platform_folders"
    const val IMAGE_TIERS = "image_tiers"
    const val VIDEO_TIERS = "video_tiers"
    const val CODEC_RATIOS = "codec_ratios"
    const val IMAGE_RESOLUTION = "image_resolution"
    const val CLASSIFY_MEDIA = "classify_media"
    const val CLASSIFICATION_TARGET = "classification_target"
    const val QUALITY_THRESHOLD = "quality_threshold_mbps"
    const val TRANSCODE_AV1 = "transcode_av1"
    const val KEEP_AV1_ORIGINAL = "keep_av1_original"
    const val VIDEO_CONTAINER = "video_container"
    const val AV1_CRF = "av1_crf"
    const val AV1_PRESET = "av1_preset"
    const val CLASSIFICATION_RULES = "classification_rules_json"
    const val INSTAGRAM_ACCOUNTS = "instagram_accounts"

    private val kv get() = com.tencent.mmkv.MMKV.defaultMMKV()

    /** 사용자가 지정한 메뉴 즐겨찾기를 읽습니다. */
    fun getMenuFavorites(): Set<String> = kv.decodeStringSet("menu_favorites") ?: emptySet()

    /** 메뉴 즐겨찾기를 저장합니다. */
    fun updateMenuFavorites(routes: Set<String>) = kv.encode("menu_favorites", routes)

    /** 저장된 Instagram 계정 이름을 읽습니다. */
    fun getInstagramAccounts(): Set<String> = kv.decodeStringSet(INSTAGRAM_ACCOUNTS) ?: emptySet()

    /** Instagram 계정 이름 목록을 저장합니다. */
    fun updateInstagramAccounts(accounts: Set<String>) = kv.encode(INSTAGRAM_ACCOUNTS, accounts)
}
