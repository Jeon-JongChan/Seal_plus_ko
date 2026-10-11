package com.junkfood.seal.util

import com.plugin.instagram.InstagramUtil
import com.plugin.compatibility.MediaRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test

/** 혼합 게시물 파싱과 외부 호스트 차단을 검증합니다. */
class InstagramUtilTest {
    /** 공개 격자에서 소유 계정의 게시물만 추출하고 추가 페이지 여부를 유지합니다. */
    @Test
    fun publicProfileGridWithoutLogin() {
        val html = """<script type="application/json" data-sjs>{"data":{"xig_user_by_username":{
            "polaris_ordered_timeline_connection":{"edges":[
                {"node":{"code":"BA","product_type":"clips","user":{"username":"test"}}},
                {"node":{"code":"BB","user":{"username":"test"}}},
                {"node":{"code":"BC","user":{"username":"another"}}},
                {"node":{"code":"../outside","user":{"username":"test"}}}
            ],"page_info":{"has_next_page":true,"end_cursor":"next"}}
        }}}</script>"""
        val (urls, more) = InstagramUtil.parsePublicProfilePage(html, "test")
        assertEquals(listOf("https://www.instagram.com/reel/BA/", "https://www.instagram.com/p/BB/"), urls)
        assertTrue(more)
        assertEquals(emptyList<String>() to false, InstagramUtil.parsePublicProfilePage("<html>Login</html>", "test"))
    }
    /** 후보의 크기가 생략된 공개 원본만 게시물 해상도를 사용합니다. */
    @Test
    fun publicOriginalDimensionsWithoutCandidateSizes() {
        val html = """<script type="application/json">{"data":{"xig_polaris_media":{"if_not_gated_logged_out":{
            "pk":"10","code":"BA","user":{"username":"test"},"original_width":3072,"original_height":4096,
            "image_versions2":{"candidates":[{"url":"https://s.cdninstagram.com/original.jpg"}]}
        }}}}</script>"""
        val image = InstagramUtil.parsePublicPage(html, "BA", 0, 0).single()
        assertEquals(3072, image.width)
        assertEquals(4096, image.height)
        assertTrue(image.image)
    }

    /** 로그인 없는 공개 JSON을 기존 파서로 읽고 혼합 파일과 이미지 해상도를 유지합니다. */
    @Test
    fun publicPageIncludesImagesAndVideos() {
        val html = """<script type="application/json" data-sjs>{"data":{"shortcode_media":{
            "id":"10","owner":{"username":"test"},"edge_sidecar_to_children":{"edges":[
            {"node":{"id":"1","display_url":"https://s.cdninstagram.com/image.jpg","dimensions":{"width":1080,"height":720}}},
            {"node":{"id":"2","display_url":"https://s.cdninstagram.com/thumb.jpg","video_url":"https://v.fbcdn.net/video.mp4","dimensions":{"width":1280,"height":720}}}
        ]}}}}</script>"""
        val media = InstagramUtil.parsePublicPage(html, "BA", 0, 0)
        assertEquals(2, media.size)
        assertTrue(media[0].image)
        assertEquals(1080, media[0].width)
        assertFalse(media[1].image)
        assertTrue(media.all { it.carousel })
        assertTrue(InstagramUtil.parsePublicPage("<html>Login</html>", "BA", 0, 0).isEmpty())
    }
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
