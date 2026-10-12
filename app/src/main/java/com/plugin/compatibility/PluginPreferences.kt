package com.plugin.compatibility

/** 선택 기능 설정을 Seal 설정 저장소와 분리해 관리합니다. */
object PluginPreferences {
    const val EXPLICIT_COOKIE_PROFILE = "explicit_cookie_profile"
    const val QUICK_COOKIE_PROFILE = "quick_cookie_profile"
    const val MEDIA_SELECTION = "media_selection"
    const val DOWNLOAD_ALL_MEDIA = "download_all_media"
    const val MEDIA_INFO_OVERLAY = "media_info_overlay"
    const val HISTORY_SORT_BY_NAME = "plugin_history_sort_by_name"
    const val QUEUE_INCREMENTAL_LOADING = "plugin_queue_incremental_loading"
    const val QUEUE_SORT_BY_NAME = "plugin_queue_sort_by_name"
    const val MEDIA_REPLACE_DUPLICATES = "plugin_media_replace_duplicates"
    const val MEDIA_VOLUME = "plugin_media_volume"
    const val IMAGE_FOLDER_NAME = "image_folder_name"
    const val VIDEO_FOLDER_NAME = "video_folder_name"
    const val PLATFORM_FOLDERS = "platform_folders"
    const val IMAGE_TIERS = "image_tiers"
    const val VIDEO_TIERS = "video_tiers"
    const val CODEC_RATIOS = "codec_ratios"
    const val IMAGE_RESOLUTION = "image_resolution"
    const val CLASSIFY_MEDIA = "classify_media"
    const val CLASSIFICATION_UPPER_FIRST = "classification_upper_first"
    const val CLASSIFICATION_TARGET = "classification_target"
    const val WEBSITE_FOLDER_TARGET = "website_folder_target"
    const val QUALITY_THRESHOLD = "quality_threshold_mbps"
    const val TRANSCODE_AV1 = "transcode_av1"
    const val KEEP_AV1_ORIGINAL = "keep_av1_original"
    const val VIDEO_CONTAINER = "video_container"
    const val AV1_CRF = "av1_crf"
    const val AV1_PRESET = "av1_preset"
    const val CLASSIFICATION_RULES = "classification_rules_json"
    const val INSTAGRAM_ACCOUNTS = "instagram_accounts"

    private val kv get() = com.tencent.mmkv.MMKV.defaultMMKV()

    /** 다운로드 목록의 정렬을 저장하며 기본값은 시간순입니다. */
    var historySortByName: Boolean
        get() = kv.decodeBool(HISTORY_SORT_BY_NAME, false)
        set(value) { kv.encode(HISTORY_SORT_BY_NAME, value) }

    /** 대기열의 정렬은 다운로드 목록과 독립적으로 저장합니다. */
    var queueSortByName: Boolean
        get() = kv.decodeBool(QUEUE_SORT_BY_NAME, false)
        set(value) { kv.encode(QUEUE_SORT_BY_NAME, value) }

    /** 대기열은 기본 다섯 게시글부터 표시하며 전체 표시 선택도 재실행 후 유지합니다. */
    var queueIncrementalLoading: Boolean
        get() = kv.decodeBool(QUEUE_INCREMENTAL_LOADING, true)
        set(value) { kv.encode(QUEUE_INCREMENTAL_LOADING, value) }

    /** 파일 관리 스캔의 중복은 기본 번호 추가이며 사용자가 선택하면 기존 파일을 대체합니다. */
    var mediaReplaceDuplicates: Boolean
        get() = kv.decodeBool(MEDIA_REPLACE_DUPLICATES, false)
        set(value) { kv.encode(MEDIA_REPLACE_DUPLICATES, value) }

    /** 두 게시글 미리보기가 공유하는 음량이며 잘못된 저장값은 안전한 범위로 보정합니다. */
    var mediaVolume: Float
        get() = (kv.decodeFloat(MEDIA_VOLUME, 1f).takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)
        set(value) { kv.encode(MEDIA_VOLUME, (value.takeIf { it.isFinite() } ?: 1f).coerceIn(0f, 1f)) }

    /** 사용자가 지정한 메뉴 즐겨찾기를 읽습니다. */
    fun getMenuFavorites(): Set<String> = kv.decodeStringSet("menu_favorites") ?: emptySet()

    /** 메뉴 즐겨찾기를 저장합니다. */
    fun updateMenuFavorites(routes: Set<String>) = kv.encode("menu_favorites", routes)

    /** 저장된 Instagram 계정 이름을 읽습니다. */
    fun getInstagramAccounts(): Set<String> = kv.decodeStringSet(INSTAGRAM_ACCOUNTS) ?: emptySet()

    /** Instagram 계정 이름 목록을 저장합니다. */
    fun updateInstagramAccounts(accounts: Set<String>) = kv.encode(INSTAGRAM_ACCOUNTS, accounts)
}
