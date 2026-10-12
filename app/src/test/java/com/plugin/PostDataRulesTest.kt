package com.plugin

import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.VideoInfo
import com.plugin.compatibility.*
import org.junit.Assert.*
import org.junit.Test

/** 수동 목록을 사용하지 않는 집계와 일반 분석 엔진의 본문 수집을 검증합니다. */
class PostDataRulesTest {
    /** 여러 페이지와 플랫폼별 동명 계정은 실제 게시글·사이트 기준으로 구분합니다. */
    @Test fun metadataAndDatabaseAccounts() {
        val url = "https://www.instagram.com/p/AB/"
        val meta = SocialPostMetadataStore.videoMetadata(VideoInfo(uploaderId = "owner", description = "본문 #사진", tags = listOf("사진", "video")), url)
        assertEquals("본문 #사진", meta.text)
        assertEquals(listOf("#사진", "#video"), meta.hashtags)
        assertEquals("https://www.instagram.com/owner/", meta.authorUrl)
        val rows = listOf(
            DownloadedVideoInfo(1, "사진", "owner", url + "#seal-media=1", "owner", "/1.jpg", "Instagram"),
            DownloadedVideoInfo(2, "사진", "owner", url + "#seal-media=2", "owner", "/2.jpg", "Instagram"),
            DownloadedVideoInfo(3, "영상", "owner", "https://instagram.com/p/CD/", "owner", "/3.mp4", "Instagram"),
            DownloadedVideoInfo(4, "영상", "owner", "https://www.tiktok.com/@owner/video/123", "owner", "/4.mp4", "TikTok"),
            DownloadedVideoInfo(5, "외부", "", "", "", "/5.mp4", "External"))
        val accounts = FrequentAccountStore.fromHistory(rows) { if (MediaRules.identity(it.substringBefore('#')) == MediaRules.identity(url)) meta else null }
        assertEquals(2, accounts.size)
        assertEquals(2, accounts.single { it.platform == "instagram" }.visitCount)
        assertEquals(1, accounts.single { it.platform == "tiktok" }.visitCount)
        assertFalse(accounts.any { it.username == "manual" })
    }
}
