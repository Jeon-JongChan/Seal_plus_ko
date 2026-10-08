package com.junkfood.seal.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** 공유·직접 입력·클립보드에서 사용하는 공통 링크 추출기의 회귀를 확인합니다. */
class UrlUtilTest {
    /** 여러 링크, 중복, 문장 부호, URL 구성 요소 및 잘못된 입력을 확인합니다. */
    @Test
    fun extractsValidLinksInOrder() {
        val youtube = "https://youtu.be/video?si=abc&list=playlist#fragment"
        val instagram = "https://www.instagram.com/reel/abc/"
        assertEquals(
            listOf(youtube, instagram),
            findURLsFromString("영상 ($youtube).\n$instagram\n$youtube"),
        )
        assertEquals(listOf(youtube), findURLsFromString("$youtube $instagram", true))
        assertEquals(
            listOf("HTTPS://example.com:8443/영상_(원본)?q=%ED%95%9C"),
            findURLsFromString("<HTTPS://example.com:8443/영상_(원본)?q=%ED%95%9C>"),
        )
        assertEquals(
            listOf("https://example.com/a(b)"),
            findURLsFromString("[https://example.com/a(b)]."),
        )
        assertEquals(
            listOf(instagram),
            findURLsFromString("https:// https://example.com:99999/x $instagram", true),
        )
        assertEquals(emptyList<String>(), findURLsFromString(""))
        assertEquals(emptyList<String>(), findURLsFromString("영상 설명 ftp://example.com"))
        assertEquals(emptyList<String>(), findURLsFromString("https://example.com/%ZZ"))
    }
}
