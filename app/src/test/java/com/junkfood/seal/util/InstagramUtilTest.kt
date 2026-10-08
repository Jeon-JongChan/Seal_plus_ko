package com.junkfood.seal.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test

/** 혼합 게시물 파싱과 외부 호스트 차단을 검증합니다. */
class InstagramUtilTest {
    /** 영상·이미지를 각각 펼치고 선택 해상도와 CDN 경계를 적용하는지 확인합니다. */
    @Test
    fun mixedCarouselAndCdnBoundary() {
        val posts =
            Json.parseToJsonElement(
                    """[
            {"code":"BA","user":{"username":"sample"},"carousel_media":[
                {"pk":"1","image_versions2":{"candidates":[
                    {"width":1080,"height":1080,"url":"https://s.cdninstagram.com/full.jpg"},
                    {"width":720,"height":720,"url":"https://s.cdninstagram.com/small.jpg"}]}},
                {"pk":"2","video_versions":[{"width":1280,"height":720,"url":"https://v.fbcdn.net/reel.mp4"}]},
                {"pk":"3","image_versions2":{"candidates":[{"url":"https://cdninstagram.com.evil.example/steal"}]}}
            ]}
        ]"""
                )
                .jsonArray
        val items = InstagramUtil.parsePosts(posts, 720, 1080)
        assertEquals(2, items.size)
        assertTrue(items[0].image)
        assertTrue(items[0].url.endsWith("small.jpg"))
        assertFalse(items[1].image)
        assertTrue(items.all { it.carousel })
        assertNotEquals(
            MediaRules.identity(items[0].videoInfo().webpageUrl!!),
            MediaRules.identity(items[1].videoInfo().webpageUrl!!),
        )
        assertEquals(
            MediaRules.identity(items[0].postUrl),
            MediaRules.identity(items[0].copy(carousel = false).videoInfo().webpageUrl!!),
        )
        assertFalse(InstagramUtil.safeMediaUrl("http://s.cdninstagram.com/a"))
        assertFalse(InstagramUtil.safeMediaUrl("https://password@s.cdninstagram.com/a"))
        assertFalse(InstagramUtil.accepts("https://instagram.com.evil.example/p/BA/"))
    }
}
