package com.plugin.compatibility

import com.tencent.mmkv.MMKV
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot

/** 소셜 미디어 게시글의 수집된 메타데이터 모델입니다. */
@Serializable
data class SocialPostMetadata(
    val postUrl: String = "",
    val author: String = "",
    val authorProfilePic: String = "",
    val text: String = "",
    val hashtags: List<String> = emptyList(),
    val authorUrl: String = "",
    val platform: String = "",
)

/** 게시글 상세 정보 플러그인의 표시 항목 설정(프로필, 계정명, 본문, 해시태그) 관리자입니다. */
object SocialPostMetadataPreferences {
    private const val KEY_SHOW_PROFILE = "plugin_post_info_show_profile"
    private const val KEY_SHOW_AUTHOR = "plugin_post_info_show_author"
    private const val KEY_SHOW_TEXT = "plugin_post_info_show_text"
    private const val KEY_SHOW_HASHTAGS = "plugin_post_info_show_hashtags"

    private val mmkv by lazy { runCatching { MMKV.mmkvWithID("social_post_metadata_prefs") }.getOrNull() }

    /** 계정주 프로필 사진 표시 여부 (기본값: true) */
    var showProfile: Boolean
        get() = mmkv?.decodeBool(KEY_SHOW_PROFILE, true) ?: true
        set(value) { mmkv?.encode(KEY_SHOW_PROFILE, value) }

    /** 계정명(@username) 표시 여부 (기본값: true) */
    var showAuthor: Boolean
        get() = mmkv?.decodeBool(KEY_SHOW_AUTHOR, true) ?: true
        set(value) { mmkv?.encode(KEY_SHOW_AUTHOR, value) }

    /** 게시글 본문 텍스트 표시 여부 (기본값: true) */
    var showText: Boolean
        get() = mmkv?.decodeBool(KEY_SHOW_TEXT, true) ?: true
        set(value) { mmkv?.encode(KEY_SHOW_TEXT, value) }

    /** 해시태그 표시 여부 (기본값: true) */
    var showHashtags: Boolean
        get() = mmkv?.decodeBool(KEY_SHOW_HASHTAGS, true) ?: true
        set(value) { mmkv?.encode(KEY_SHOW_HASHTAGS, value) }
}

/** MMKV 기반의 소셜 게시글 메타데이터 저장소입니다. */
object SocialPostMetadataStore {
    private val mmkv by lazy { runCatching { MMKV.mmkvWithID("social_post_metadata") }.getOrNull() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val memoryCache = mutableStateMapOf<String, SocialPostMetadata>()

    /** 기존 플랫폼 식별자를 재사용하며 게시글 페이지 해시만 제외합니다. */
    private fun normalizeKey(urlOrKey: String): String {
        return MediaRules.identity(urlOrKey.substringBefore('#'))
    }

    /** 두 다운로드 파서가 동일한 실제 응답을 사용하며 순수 변환에서는 저장소를 읽지 않습니다. */
    internal fun instagramMetadata(post: JsonObject): SocialPostMetadata? {
        /** 누락되거나 객체인 필드는 문자열로 오인하지 않습니다. */
        fun text(node: JsonObject?, key: String) = (node?.get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val code = text(post, "code")
        val owner = post["user"] as? JsonObject ?: post["owner"] as? JsonObject
        val author = text(owner, "username")
        if (!code.matches(Regex("[A-Za-z0-9_-]+")) || !author.matches(Regex("[A-Za-z0-9._]{1,30}")) || author in setOf(".", "..")) return null
        val picture = listOf(text(owner, "profile_pic_url_hd"), text(owner, "profile_pic_url"))
            .firstOrNull(::validProfilePicture).orEmpty()
        val caption = text(post["caption"] as? JsonObject, "text").ifEmpty {
            val edges = (post["edge_media_to_caption"] as? JsonObject)?.get("edges") as? JsonArray
            text((edges?.firstOrNull() as? JsonObject)?.get("node") as? JsonObject, "text")
        }
        return SocialPostMetadata("https://www.instagram.com/${if (text(post, "product_type") == "clips") "reel" else "p"}/$code/",
            author, picture, caption, Regex("""#([^\s#]+)""").findAll(caption).map { "#${it.groupValues[1]}" }.distinct().toList(),
            authorUrl = "https://www.instagram.com/$author/", platform = "instagram")
    }

    /** 쿠키나 로그인 페이지 이미지를 임의 호스트로 보내지 않고 실제 프로필 CDN만 허용합니다. */
    internal fun validProfilePicture(value: String): Boolean = runCatching {
        java.net.URI(value).let { uri -> uri.scheme == "https" && uri.userInfo == null &&
            (uri.host?.endsWith(".cdninstagram.com") == true || uri.host?.endsWith(".fbcdn.net") == true) }
    }.getOrDefault(false)

    /** 실제 조회에서만 활성 계정·게시글 플러그인에 정보를 전달합니다. */
    internal fun collectInstagramPost(post: JsonObject) {
        val accounts = com.plugin.PluginState.isEnabled(com.plugin.PluginId.ACCOUNTS)
        val details = com.plugin.PluginState.isEnabled(com.plugin.PluginId.POST_INFO)
        if (!accounts && !details) return
        val metadata = instagramMetadata(post) ?: return
        if (accounts) FrequentAccountStore.recordVisit(metadata.author, metadata.authorProfilePic)
        save(metadata)
    }

    /** 일반 영상 분석 응답의 본문·태그도 실제 조회 결과에서 수집합니다. */
    fun collectVideoInfo(info: com.junkfood.seal.util.VideoInfo, url: String) {
        if (!com.plugin.PluginState.isEnabled(com.plugin.PluginId.POST_INFO) && !com.plugin.PluginState.isEnabled(com.plugin.PluginId.ACCOUNTS)) return
        save(videoMetadata(info, url, get(url)))
    }

    /** 기존 사진을 보존하면서 분석 엔진이 제공한 계정 ID·설명·태그를 병합합니다. */
    internal fun videoMetadata(info: com.junkfood.seal.util.VideoInfo, url: String, old: SocialPostMetadata? = null): SocialPostMetadata {
        val platform = socialPlatform(url)
        val author = info.uploaderId?.takeIf { it.isNotBlank() }?.removePrefix("@")
            ?: old?.author?.takeIf { it.isNotBlank() } ?: info.uploader.orEmpty()
        val body = info.description?.takeIf { it.isNotBlank() } ?: old?.text.orEmpty()
        val tags = ((info.tags.orEmpty().map { "#${it.removePrefix("#")}" }) +
            Regex("""#([^\s#]+)""").findAll(body).map { "#${it.groupValues[1]}" }).distinct().filter { it.length > 1 }
        val profile = when {
            platform == "instagram" && author.matches(Regex("[A-Za-z0-9._]{1,30}")) -> "https://www.instagram.com/$author/"
            platform == "tiktok" && author.matches(Regex("[A-Za-z0-9._]{1,30}")) -> "https://www.tiktok.com/@$author"
            platform == "youtube" && info.uploaderId?.startsWith("@") == true -> "https://www.youtube.com/${info.uploaderId}"
            platform == "youtube" && author.matches(Regex("UC[A-Za-z0-9_-]{22}")) -> "https://www.youtube.com/channel/$author"
            else -> old?.authorUrl.orEmpty()
        }
        return SocialPostMetadata(url, author, old?.authorProfilePic.orEmpty(), body,
            tags.ifEmpty { old?.hashtags.orEmpty() }, profile, platform)
    }

    /** 게시글 메타데이터를 메모리와 MMKV에 저장합니다. */
    @Synchronized
    fun save(metadata: SocialPostMetadata) {
        if (metadata.postUrl.isBlank()) return
        val key = normalizeKey(metadata.postUrl)
        // 네트워크 조회가 끝나면 이미 열린 카드도 같은 게시글의 최신 값을 다시 표시합니다.
        Snapshot.withMutableSnapshot { memoryCache[key] = metadata }
        mmkv?.encode(key, json.encodeToString(metadata))
    }

    /** 게시글 URL 또는 고유 키에 해당하는 메타데이터를 조회합니다. */
    @Synchronized
    fun get(urlOrKey: String): SocialPostMetadata? {
        if (urlOrKey.isBlank()) return null
        val key = normalizeKey(urlOrKey)
        memoryCache[key]?.let { return it }
        val raw = mmkv?.decodeString(key) ?: return null
        return runCatching {
            json.decodeFromString<SocialPostMetadata>(raw).also { metadata -> Snapshot.withMutableSnapshot { memoryCache[key] = metadata } }
        }.getOrNull()
    }
}


/** 실제 호스트에서 플랫폼을 식별하며 파일명·표시 이름으로 사이트를 추측하지 않습니다. */
internal fun socialPlatform(url: String): String {
    val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()
    return when {
        host == "instagram.com" || host.endsWith(".instagram.com") -> "instagram"
        host == "tiktok.com" || host.endsWith(".tiktok.com") -> "tiktok"
        host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") -> "youtube"
        else -> host
    }
}
