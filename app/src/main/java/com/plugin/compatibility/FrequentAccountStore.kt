package com.plugin.compatibility

import com.tencent.mmkv.MMKV
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 자주 방문하는 계정 정보 데이터 모델입니다. */
@Serializable
data class FrequentAccount(
    val username: String,
    val profilePicUrl: String = "",
    val visitCount: Int = 1,
    val lastVisited: Long = System.currentTimeMillis(),
    val platform: String = "instagram",
    val profileUrl: String = "",
)

/** 자주 방문하는 계정을 MMKV 저장소에 보관하고 조회하는 싱글톤 객체입니다. */
object FrequentAccountStore {
    private val mmkv get() = runCatching { TemporarySession.storage("frequent_accounts") }.getOrNull()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val KEY = "accounts_list"
    private val memoryCache = androidx.compose.runtime.mutableStateMapOf<String, FrequentAccount>()

    /** 세션 전환 시 계정 캐시도 선택 저장소에서 다시 읽습니다. */
    fun reload() { androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { memoryCache.clear(); loadAll() } }

    init {
        loadAll()
    }

    /** 다운로드 DB를 작성자별로 묶고 여러 파일로 된 한 게시글은 한 번만 셉니다. */
    fun fromHistory(history: List<com.junkfood.seal.database.objects.DownloadedVideoInfo>,
        metadata: (String) -> SocialPostMetadata? = SocialPostMetadataStore::get): List<FrequentAccount> {
        val records = history.mapNotNull { info ->
            val post = metadata(info.videoUrl)
            val platform = post?.platform?.ifBlank { socialPlatform(info.videoUrl) } ?: socialPlatform(info.videoUrl)
            val author = (post?.author?.takeIf { it.isNotBlank() } ?: info.videoAuthor).trim().removePrefix("@")
            if (platform.isBlank() || author.isBlank() || author in setOf("null", "playlist", "Instagram", "TikTok")) return@mapNotNull null
            Triple(info, post, platform to author)
        }
        // ponytail: 구형 DB에 계정 ID가 없으면 플랫폼·작성자명으로 묶습니다. ID가 있는 새 응답은 해당 ID를 사용합니다.
        return records.groupBy { it.third.first + ":" + it.third.second.lowercase() }.values.map { group ->
            val recent = group.maxBy { it.first.id }
            val author = recent.third.second
            val platform = recent.third.first
            val cached = memoryCache[author.lowercase()]?.takeIf { it.platform == platform }
            val picture = cached?.profilePicUrl?.takeIf(String::isNotBlank) ?: group.sortedByDescending { it.first.id }
                .firstNotNullOfOrNull { it.second?.authorProfilePic?.takeIf(String::isNotBlank) }.orEmpty()
            val profile = group.firstNotNullOfOrNull { it.second?.authorUrl?.takeIf(String::isNotBlank) }
                ?: when {
                    platform == "instagram" && author.matches(Regex("[A-Za-z0-9._]{1,30}")) -> "https://www.instagram.com/$author/"
                    platform == "tiktok" && author.matches(Regex("[A-Za-z0-9._]{1,30}")) -> "https://www.tiktok.com/@$author"
                    else -> ""
                }
            FrequentAccount(author, picture, group.map { MediaRules.identity(it.first.videoUrl.substringBefore('#')) }.distinct().size,
                recent.first.id.toLong(), platform, profile)
        }.sortedWith(compareByDescending<FrequentAccount> { it.visitCount }.thenByDescending { it.lastVisited })
    }

    /** 저장된 전체 계정 정보를 메모리 캐시로 로드합니다. */
    private fun loadAll() {
        val raw = mmkv?.decodeString(KEY) ?: return
        runCatching {
            val list = json.decodeFromString<List<FrequentAccount>>(raw)
            list.forEach { memoryCache[it.username.lowercase()] = it }
        }
    }

    /**
     * 계정 방문을 기록하고 방문 횟수 및 최신 프로필 사진 URL을 갱신합니다.
     * 기존 항목이 있으면 방문 횟수를 1 증가시키고, 없으면 새로 등록합니다.
     */
    @Synchronized
    fun recordVisit(username: String, profilePicUrl: String? = null, platform: String = "instagram"): Boolean {
        val key = username.trim().removePrefix("@").lowercase()
        if (!Regex("[a-z0-9._]{1,30}").matches(key) || key in setOf(".", "..")) return false
        val existing = memoryCache[key]
        val updated = if (existing != null) {
            existing.copy(
                visitCount = existing.visitCount + 1,
                lastVisited = System.currentTimeMillis(),
                profilePicUrl = profilePicUrl?.takeIf { it.isNotBlank() } ?: existing.profilePicUrl,
                platform = platform
            )
        } else {
            FrequentAccount(
                username = key,
                profilePicUrl = profilePicUrl.orEmpty(),
                visitCount = 1,
                lastVisited = System.currentTimeMillis(),
                platform = platform
            )
        }
        memoryCache[key] = updated
        persist()
        return true
    }

    /** 사진 갱신은 수집 횟수를 늘리거나 다운로드 DB에 없는 계정을 화면에 추가하지 않습니다. */
    @Synchronized
    fun updateProfilePicture(username: String, picture: String, platform: String = "instagram") {
        require(platform == "instagram" && SocialPostMetadataStore.validProfilePicture(picture))
        val key = username.trim().removePrefix("@").lowercase()
        require(key.matches(Regex("[a-z0-9._]{1,30}")) && key !in setOf(".", ".."))
        val account = memoryCache[key]?.takeIf { it.platform == platform } ?: FrequentAccount(key, visitCount = 0, platform = platform)
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { memoryCache[key] = account.copy(profilePicUrl = picture) }
        persist()
    }

    /** 메모리 캐시의 전체 계정 목록을 MMKV에 직렬화하여 저장합니다. */
    private fun persist() {
        mmkv?.encode(KEY, json.encodeToString(memoryCache.values.toList()))
    }
}

