package com.plugin.compatibility

import com.junkfood.seal.util.VideoInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** 단일 게시물 또는 계정 목록에서 선택 가능한 이미지·영상 정보입니다. */
@Serializable
@SerialName("com.plugin.instagram.InstagramMedia")
data class InstagramMedia(
    val id: String,
    val postUrl: String,
    val url: String,
    val thumbnail: String,
    val image: Boolean,
    val title: String,
    val author: String,
    val duration: Double = 0.0,
    val carousel: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val platform: String = "instagram",
    val anonymousVideoCookie: String = "",
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val reel: Boolean = false,
) {
    /** 기존 대기열·이력에서 사용할 메타데이터로 변환합니다. */
    fun videoInfo(): VideoInfo =
        VideoInfo(
            id = id,
            title = "$author [$id]",
            uploader = author,
            uploaderId = author,
            description = SocialPostMetadataStore.get(postUrl)?.text?.takeIf { it.isNotBlank() }
                ?: title.takeUnless { it == "$author · ${postUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')}" },
            duration = duration,
            originalUrl = url,
            webpageUrl = if (carousel) "$postUrl#seal-media=$id" else postUrl,
            thumbnail = thumbnail,
            extractor = platform,
            extractorKey = if (platform == "tiktok") "TikTok" else "Instagram",
            ext = if (image) "jpg" else "mp4",
            width = width.toDouble(),
            height = height.toDouble(),
        ).apply {
            if (platform == "tiktok" && !image && anonymousVideoCookie.isNotEmpty()) {
                this.httpHeaders = mapOf("Cookie" to "tt_chain_token=$anonymousVideoCookie")
            }
            this.postPageIndex = pageIndex
            this.postPageCount = pageCount
        }
}

