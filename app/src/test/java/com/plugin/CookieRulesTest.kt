package com.plugin

import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** 실제 계정 값을 사용하지 않고 만료·HttpOnly·사이트 경계·명시적 선택 보존을 검증합니다. */
class CookieRulesTest {
    /** HttpOnly·패딩 값은 유지하고 중복된 과거 값·다른 경로·호스트 전용 쿠키를 구분합니다. */
    @Test fun parseAndMatch() {
        val content = listOf(
            "# Netscape HTTP Cookie File",
            "#HttpOnly_.example.com\tTRUE\t/\tTRUE\t0\tsession\tone==",
            "#HttpOnly_.example.com\tTRUE\t/\tTRUE\t0\tsession\ttwo==",
            "example.com\tFALSE\t/\tFALSE\t0\thost_only\tprivate",
            ".example.com\tTRUE\t/api\tTRUE\t0\tpath_only\tapi",
            ".example.com\tTRUE\t/\tTRUE\t1\texpired\told",
            ".other.com\tTRUE\t/\tTRUE\t0\tother\tnot_sent",
            ".example.com\tTRUE\t/\tTRUE\t0\tbad\tvalue;injection") .joinToString("\n")
        val normalized = CookieRules.normalized(content)
        assertTrue(normalized.contains("#HttpOnly_"))
        assertTrue(normalized.contains("two=="))
        assertFalse(normalized.contains("one=="))
        val root = CookieRules.header(normalized, "https://www.example.com/".toHttpUrl())
        assertEquals(mapOf("session" to "two=="), root)
        val api = CookieRules.header(normalized, "https://www.example.com/api/v1".toHttpUrl())
        assertEquals("api", api["path_only"])
        assertFalse(CookieRules.header(normalized, "https://www.example.com/apix".toHttpUrl()).containsKey("path_only"))
        assertEquals("private", CookieRules.header(normalized, "https://example.com/".toHttpUrl())["host_only"])
        assertTrue(CookieRules.header(normalized, "http://www.example.com/".toHttpUrl()).isEmpty())
        assertTrue(CookieRules.header(normalized, "https://evilexample.com/".toHttpUrl()).isEmpty())
        assertTrue(runCatching { CookieRules.normalized("# expired or empty") }.isFailure)
    }

    /** WebView 시간 단위를 변환하고 구형 직접 내보낸 값도 같은 만료 기준으로 복구합니다. */
    @Test fun chromiumExpiryAndExplicitProfile() {
        val unix = 2_000_000_000L
        val chromium = (unix + 11_644_473_600L) * 1_000_000
        assertEquals(unix, CookieRules.webViewExpiry(chromium))
        assertEquals(0, CookieRules.webViewExpiry(0))
        val content = ".example.com\tTRUE\t/\tTRUE\t$chromium\tsession\tsynthetic"
        assertEquals(unix * 1000, CookieRules.parse(content).single().expiresAt)
        val explicitlySelected = DownloadPreferences.EMPTY.copy(cookies = true).copyPluginOptions(cookieProfileId = 7, cookieProfileExplicit = true)
        assertEquals(7, PluginPolicy.preferences(explicitlySelected, emptySet()).cookieProfileId)
        assertEquals(-1, PluginPolicy.preferences(explicitlySelected.copyPluginOptions(cookieProfileExplicit = false), emptySet()).cookieProfileId)
    }
}
