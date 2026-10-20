package com.plugin.compatibility

import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.database.objects.VideoQuality
import com.tencent.mmkv.MMKV
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 플러그인 전용 미디어 확장 메타데이터를 관리합니다.
 * 원본 DownloadedVideoInfo 및 Room DB 스키마(v5)를 변경하지 않고 MMKV에 보관합니다.
 */
@Serializable
data class MediaMetadataRecord(
    val quality: VideoQuality? = null,
    val sourceQuality: VideoQuality? = null,
    val postPageIndex: Int = 0,
    val postPageCount: Int = 1,
    val excludedFromProcessing: Boolean = false,
    val aiDuplicate: Boolean = false,
    val aiReplaced: Boolean = false,
)

/**
 * MMKV 기반의 메타데이터 저장소입니다.
 */
object PluginMediaMetadataStore {
    val revision = MutableStateFlow(0L)
    private val mmkv get() = runCatching { TemporarySession.storage("plugin_media_metadata") }.getOrNull()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val memoryCache = ConcurrentHashMap<String, MediaMetadataRecord>()
    private val duplicateName = Regex("-AI-[0-9a-f]{8}(?: \\[[^]]+])?\\.(?:png|mp4)$")

    /** 저장소 전환 후 이전 세션의 메모리 자료를 표시하지 않습니다. */
    fun clearMemory() { memoryCache.clear(); revision.update { it + 1 } }

    /** 경로에 해당하는 메타데이터를 조회합니다. */
    fun get(videoPath: String): MediaMetadataRecord? {
        if (videoPath.isBlank()) return null
        memoryCache[videoPath]?.let { return it }
        val raw = mmkv?.decodeString(videoPath) ?: return null
        return runCatching {
            json.decodeFromString<MediaMetadataRecord>(raw).also { memoryCache[videoPath] = it }
        }.getOrNull()
    }

    /** 메타데이터를 저장하거나 갱신합니다. */
    fun save(
        videoPath: String,
        quality: VideoQuality? = null,
        sourceQuality: VideoQuality? = null,
        postPageIndex: Int = 0,
        postPageCount: Int = 1,
        excludedFromProcessing: Boolean? = null,
        aiDuplicate: Boolean? = null,
        aiReplaced: Boolean? = null,
    ) {
        if (videoPath.isBlank()) return
        val existing = get(videoPath)
        val record = MediaMetadataRecord(
            quality = quality ?: existing?.quality,
            sourceQuality = sourceQuality ?: existing?.sourceQuality,
            postPageIndex = if (postPageIndex != 0) postPageIndex else existing?.postPageIndex ?: 0,
            postPageCount = maxOf(postPageCount, existing?.postPageCount ?: 1),
            excludedFromProcessing = excludedFromProcessing ?: existing?.excludedFromProcessing ?: false,
            aiDuplicate = aiDuplicate ?: existing?.aiDuplicate ?: false,
            aiReplaced = aiReplaced ?: existing?.aiReplaced ?: false,
        )
        memoryCache[videoPath] = record
        mmkv?.encode(videoPath, json.encodeToString(record))
        revision.update { it + 1 }
    }

    /** 파일 이동 시 메타데이터 키를 갱신합니다. */
    fun updatePath(oldPath: String, newPath: String) {
        if (oldPath.isBlank() || newPath.isBlank() || oldPath == newPath) return
        val record = get(oldPath) ?: return
        mmkv?.removeValueForKey(oldPath)
        memoryCache.remove(oldPath)
        memoryCache[newPath] = record
        mmkv?.encode(newPath, json.encodeToString(record))
        revision.update { it + 1 }
    }

    /** 메타데이터를 삭제합니다. */
    fun delete(videoPath: String) {
        if (videoPath.isBlank()) return
        memoryCache.remove(videoPath)
        mmkv?.removeValueForKey(videoPath)
        revision.update { it + 1 }
    }

    /** 불투명 SAF ID는 실제 부모 이름을 읽을 때 저장한 표지를 사용하고 일반 경로는 현재 이름을 따릅니다. */
    fun isProcessingExcluded(path: String): Boolean = MediaRules.isNoFacePath(path) ||
        (path.startsWith("content://") && get(path)?.excludedFromProcessing == true)

    /** 이전 버전의 AI 복제도 앱이 생성한 파일명 표식으로 인식합니다. */
    fun isAiDuplicate(path: String): Boolean = get(path)?.aiDuplicate == true ||
        duplicateName.containsMatchIn(MediaRules.fileName(path))
}

/** DownloadedVideoInfo의 품질 정보 확장 프로퍼티 */
val DownloadedVideoInfo.quality: VideoQuality?
    get() = PluginMediaMetadataStore.get(videoPath)?.quality

/** DownloadedVideoInfo의 원본 품질 정보 확장 프로퍼티 */
val DownloadedVideoInfo.sourceQuality: VideoQuality?
    get() = PluginMediaMetadataStore.get(videoPath)?.sourceQuality

/** DownloadedVideoInfo의 게시글 페이지 번호 확장 프로퍼티 */
val DownloadedVideoInfo.postPageIndex: Int
    get() = PluginMediaMetadataStore.get(videoPath)?.postPageIndex ?: 0

/** DownloadedVideoInfo의 게시글 총 페이지 수 확장 프로퍼티 */
val DownloadedVideoInfo.postPageCount: Int
    get() = PluginMediaMetadataStore.get(videoPath)?.postPageCount ?: 1

/** 메타데이터를 보존하며 DownloadedVideoInfo를 갱신하는 헬퍼 함수 */
fun DownloadedVideoInfo.withMetadata(
    quality: VideoQuality? = this.quality,
    sourceQuality: VideoQuality? = this.sourceQuality,
    postPageIndex: Int = this.postPageIndex,
    postPageCount: Int = this.postPageCount,
): DownloadedVideoInfo {
    PluginMediaMetadataStore.save(
        videoPath = this.videoPath,
        quality = quality,
        sourceQuality = sourceQuality,
        postPageIndex = postPageIndex,
        postPageCount = postPageCount,
    )
    return this
}

// VideoInfo용 런타임 메타데이터 캐시
private val videoInfoMeta = ConcurrentHashMap<String, Triple<Map<String, String>, Int, Int>>()

private fun VideoInfo.metaKey(): String =
    this.id.takeIf { it.isNotBlank() }
        ?: this.webpageUrl?.takeIf { it.isNotBlank() }
        ?: this.title.takeIf { it.isNotBlank() }
        ?: "vi_${System.identityHashCode(this)}"

/** VideoInfo의 HTTP 헤더 확장 프로퍼티 */
var VideoInfo.httpHeaders: Map<String, String>
    get() = videoInfoMeta[metaKey()]?.first ?: emptyMap()
    set(value) {
        val key = metaKey()
        if (key.isNotBlank()) {
            val existing = videoInfoMeta[key]
            videoInfoMeta[key] = Triple(value, existing?.second ?: 0, existing?.third ?: 1)
        }
    }

/** VideoInfo의 게시글 페이지 번호 확장 프로퍼티 */
var VideoInfo.postPageIndex: Int
    get() = videoInfoMeta[metaKey()]?.second ?: 0
    set(value) {
        val key = metaKey()
        if (key.isNotBlank()) {
            val existing = videoInfoMeta[key]
            videoInfoMeta[key] = Triple(existing?.first ?: emptyMap(), value, existing?.third ?: 1)
        }
    }

/** VideoInfo의 게시글 총 페이지 수 확장 프로퍼티 */
var VideoInfo.postPageCount: Int
    get() = videoInfoMeta[metaKey()]?.third ?: 1
    set(value) {
        val key = metaKey()
        if (key.isNotBlank()) {
            val existing = videoInfoMeta[key]
            videoInfoMeta[key] = Triple(existing?.first ?: emptyMap(), existing?.second ?: 0, value)
        }
    }

