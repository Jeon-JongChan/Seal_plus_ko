package com.junkfood.seal.util

import com.plugin.tiktok.TikTokUtil
import com.plugin.compatibility.*
import org.junit.Assert.*
import org.junit.Test
import okio.ByteString.Companion.decodeBase64

/** 틱톡 사진·영상 판별과 기존 저장 함수로 전달할 안전한 주소를 검사합니다. */
class TikTokUtilTest {
    /** 고정된 확인 응답의 정답을 찾고 잘못된 쿠키 이름이나 로그인 HTML은 거부합니다. */
    @Test
    fun anonymousBrowserChallenge() = kotlinx.coroutines.runBlocking {
        val challenge = "eyJ2Ijp7ImEiOiJjSFZpYkdsakxYQnZjM1E9IiwiYyI6InFDMjN5aGwzMDk0WWh5ZjZvbWVDV2ZxTm9NTnpRYUx4MW0xajFaN2JZdzQ9In19"
        val html = """<p id="cs" class="$challenge"></p><p id="wci" class="_wafchallengeid"></p><p id="rci" class="waforiginalreid"></p><p id="rs" class="request-id"></p>"""
        val cookies = TikTokUtil.solvePublicChallenge(html)
        val solved = cookies.getValue("_wafchallengeid")
        assertEquals("request-id", cookies["waforiginalreid"])
        val data = kotlinx.serialization.json.Json.parseToJsonElement(solved.decodeBase64()!!.utf8())
        assertTrue(data.toString().contains("\"d\":\"MTI=\""))
        assertTrue(TikTokUtil.solvePublicChallenge(html.replace("_wafchallengeid", "invalid;cookie")).isEmpty())
        assertTrue(TikTokUtil.solvePublicChallenge("<html>Login</html>").isEmpty())
    }

    /** 음악과 영상 커버가 있어도 사진 게시물은 사진으로 유지하고 영상은 기존 엔진 주소를 사용합니다. */
    @Test
    fun photoSlidesAndVideoUseExistingMediaFlow() {
        val url = "https://www.tiktok.com/@test/video/1234"
        val html = """<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{
            "id":"1234","author":{"uniqueId":"test"},"desc":"사진 게시물","music":{"playUrl":"music.mp3"},
            "video":{"cover":"https://s.tiktokcdn.com/cover.jpg"},"imagePost":{"images":[
            {"imageWidth":1080,"imageHeight":1440,"imageURL":{"urlList":["https://s.tiktokcdn.com/1.webp"]}},
            {"imageWidth":720,"imageHeight":960,"imageURL":{"urlList":["https://s.ibytedtos.com/2.jpg"]}}
            ]}}}}}}</script>"""
        val images = TikTokUtil.parsePublicPage(html, url)
        assertEquals(2, images.size)
        assertTrue(images.all { it.image && it.carousel && it.platform == "tiktok" })
        assertEquals(2, images.map { it.id }.distinct().size)
        assertEquals(1440, images.first().height)
        assertEquals("TikTok", images.first().videoInfo().extractorKey)
        val videoHtml = """<script id="__UNIVERSAL_DATA_FOR_REHYDRATION__">
            {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{
            "id":"1234","author":{"uniqueId":"test"},"video":{"playAddr":"https://s.tiktokcdn.com/1.mp4","cover":"https://s.tiktokcdn.com/1.jpg","duration":12}
            }}}}}</script>"""
        val video = TikTokUtil.parsePublicPage(videoHtml, url).single()
        assertFalse(video.image)
        assertEquals("https://s.tiktokcdn.com/1.mp4", video.videoInfo().originalUrl)
        assertEquals(url, video.videoInfo().webpageUrl)
        assertEquals(mapOf("Cookie" to "tt_chain_token=public-token"),
            video.copy(anonymousVideoCookie = "public-token").videoInfo().httpHeaders)
        assertTrue(images.first().copy(anonymousVideoCookie = "public-token").videoInfo().httpHeaders.isEmpty())
        assertTrue(TikTokUtil.accepts("https://vt.tiktok.com/example/"))
        assertFalse(TikTokUtil.accepts("https://tiktok.com.evil.example/@test/photo/1234"))
        assertFalse(TikTokUtil.accepts("https://password@www.tiktok.com/@test/photo/1234"))
        assertFalse(TikTokUtil.safeMediaUrl("https://s.tiktokcdn.com.evil.example/a.jpg"))
        assertFalse(TikTokUtil.safeMediaUrl("http://s.tiktokcdn.com/a.jpg"))
        assertFalse(TikTokUtil.safeMediaUrl("https://password@s.tiktokcdn.com/a.jpg"))
        assertTrue(TikTokUtil.parsePublicPage("<html>Login</html>", url).isEmpty())
    }
}
