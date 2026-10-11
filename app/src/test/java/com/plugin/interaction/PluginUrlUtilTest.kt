package com.plugin.interaction

import org.junit.Assert.assertEquals
import org.junit.Test

/** 플러그인 URL 추출 결과를 검증합니다. */
class PluginUrlUtilTest {
    /** 여러 URL, 중복, 괄호, 잘못된 포트 입력을 확인합니다. */
    @Test
    fun extractsValidLinksInOrder() {
        val youtube = "https://youtu.be/video?si=abc&list=playlist#fragment"
        val instagram = "https://www.instagram.com/reel/abc/"
        assertEquals(
            listOf(youtube, instagram),
            findPluginURLsFromString("video ($youtube).\n$instagram\n$youtube"),
        )
        assertEquals(listOf(youtube), findPluginURLsFromString("$youtube $instagram", true))
        assertEquals(
            listOf("HTTPS://example.com:8443/path_(original)?q=value"),
            findPluginURLsFromString("<HTTPS://example.com:8443/path_(original)?q=value>"),
        )
        assertEquals(listOf("https://example.com/a(b)"), findPluginURLsFromString("[https://example.com/a(b)]."))
        assertEquals(
            listOf(instagram),
            findPluginURLsFromString("https:// https://example.com:99999/x $instagram", true),
        )
        assertEquals(emptyList<String>(), findPluginURLsFromString(""))
        assertEquals(emptyList<String>(), findPluginURLsFromString("video description ftp://example.com"))
        assertEquals(emptyList<String>(), findPluginURLsFromString("https://example.com/%ZZ"))
    }
}
