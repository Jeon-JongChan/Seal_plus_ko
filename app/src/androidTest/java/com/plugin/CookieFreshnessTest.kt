package com.plugin

import android.webkit.CookieManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.CookieProfile
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.plugin.compatibility.*
import com.plugin.runtime.PluginDownloadUtil
import com.plugin.ui.PluginMainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 실제 계정의 쿠키를 표시·수정하지 않고 전용 가짜 도메인과 프로필로 재선택 문제를 검사합니다. */
class CookieFreshnessTest {
    /** 전체 쿠키는 로그인 값이 바뀐 다음 요청에 자동 반영하며 테스트 쿠키만 만료시킵니다. */
    @Test fun webViewUpdatesWithoutReselecting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = CookieManager.getInstance()
        val accepts = manager.acceptCookie()
        val url = "https://review-cookie-${java.util.UUID.randomUUID()}.example/"
        val cache = CompatibilityFiles.cookiesFile(context, -1)
        val before = cache.takeIf { it.isFile }?.readBytes()
        try {
            manager.setAcceptCookie(true)
            setCookie(url, "review_session=one==; Path=/; Secure; Max-Age=3600")
            assertEquals("one==", CookieRules.header(PluginDownloadUtil.prepareCookies().readText(), url.toHttpUrl())["review_session"])
            setCookie(url, "review_session=two==; Path=/; Secure; Max-Age=3600")
            assertEquals("two==", CookieRules.header(PluginDownloadUtil.prepareCookies().readText(), url.toHttpUrl())["review_session"])
            assertFalse(CookieRules.header(PluginDownloadUtil.cookieContent(), url.replace("https://", "https://sub.").toHttpUrl()).containsKey("review_session"))
        } finally {
            setCookie(url, "review_session=; Path=/; Secure; Max-Age=0")
            manager.setAcceptCookie(accepts)
            if (before != null) cache.writeBytes(before) else cache.delete()
        }
    }

    /** 프로필 갱신·병렬 파일 분리·직접 선택 저장과 실패 요청의 사본 정리를 확인합니다. */
    @Test fun profileUpdatesAndExplicitSelection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val keys = listOf(PluginPreferences.EXPLICIT_COOKIE_PROFILE, com.junkfood.seal.util.COOKIES)
        val existing = keys.filter(kv::containsKey).toSet()
        val choice = PluginPreferences.EXPLICIT_COOKIE_PROFILE.getInt(Int.MIN_VALUE)
        val cookiesEnabled = com.junkfood.seal.util.COOKIES.getBoolean()
        val interaction = PluginState.isEnabled(PluginId.INTERACTION)
        val marker = "review-${java.util.UUID.randomUUID()}.example"
        val profiles = mutableListOf<CookieProfile>()
        /** 선택한 한 프로필에만 속하는 테스트 쿠키 파일을 생성합니다. */
        fun content(domain: String, value: String) = "#HttpOnly_.$domain\tTRUE\t/\tTRUE\t0\treview_session\t$value\n"
        try {
            for ((index, value) in listOf("alpha", "beta").withIndex()) {
                val url = "https://$index.$marker/"
                DatabaseUtil.insertCookieProfile(CookieProfile(0, url, content("$index.$marker", value)))
                profiles += DatabaseUtil.getCookiesFlow().first().single { it.url == url }
            }
            val first = profiles[0]
            assertEquals("alpha", CookieRules.header(PluginDownloadUtil.prepareCookies(first.id).readText(), first.url.toHttpUrl())["review_session"])
            DatabaseUtil.updateCookieProfile(first.copy(content = content("0.$marker", "updated==")))
            assertEquals("updated==", CookieRules.header(PluginDownloadUtil.prepareCookies(first.id).readText(), first.url.toHttpUrl())["review_session"])
            val outputs = profiles.map { profile -> async(Dispatchers.IO) {
                val file = PluginDownloadUtil.prepareCookies(profile.id)
                file.path to CookieRules.header(file.readText(), profile.url.toHttpUrl())["review_session"]
            } }.awaitAll()
            assertNotEquals(outputs[0].first, outputs[1].first)
            assertEquals(listOf("updated==", "beta"), outputs.map { it.second })
            PluginState.setEnabled(PluginId.INTERACTION, false)
            val updated = AtomicReference<DownloadPreferences?>()
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme {
                    var prefs by remember { pluginPreferencesState(DownloadPreferences.EMPTY.copyPluginOptions()) }
                    CookieOptions(prefs) { prefs = it; updated.set(it) }
                } } }
                tap(context.getString(R.string.cookies)); tap(first.url)
                repeat(100) { if (updated.get() == null) android.os.SystemClock.sleep(100) }
                assertEquals(first.id, updated.get()?.cookieProfileId)
                assertEquals(true, updated.get()?.cookieProfileExplicit)
                val reopened = createPluginPreferences()
                assertEquals(first.id, reopened.cookieProfileId)
                assertTrue(reopened.cookieProfileExplicit)
                tap(context.getString(R.string.cookies) + " · " + first.url.toHttpUrl().host)
                tap(context.getString(androidx.appcompat.R.string.abc_action_mode_done))
            }
            // 다른 프로필로 재시도해도 일반 다운로드의 프로필과 쿠키 활성 기본값은 유지합니다.
            com.junkfood.seal.util.COOKIES.updateBoolean(false)
            updated.set(null)
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme {
                    var prefs by remember { pluginPreferencesState(DownloadPreferences.EMPTY.copyPluginOptions()) }
                    CookieOptions(prefs, persistDefaults = false) { prefs = it; updated.set(it) }
                } } }
                tap(context.getString(R.string.cookies)); tap(profiles[1].url)
                repeat(100) { if (updated.get() == null) android.os.SystemClock.sleep(100) }
                assertEquals(profiles[1].id, updated.get()?.cookieProfileId)
                assertTrue(updated.get()?.cookies == true)
                assertEquals(first.id, createPluginPreferences().cookieProfileId)
                assertFalse(com.junkfood.seal.util.COOKIES.getBoolean())
            }
            val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("request-cookies-") }.map { it.name }.toSet()
            val failed = withContext(Dispatchers.IO) { PluginDownloadUtil.fetchVideoInfoFromUrl("http://127.0.0.1:1/review", preferences =
                DownloadPreferences.EMPTY.copy(cookies = true).copyPluginOptions(cookieProfileId = first.id, cookieProfileExplicit = true)) }
            assertTrue(failed.isFailure)
            val after = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("request-cookies-") }.map { it.name }.toSet()
            assertEquals(before, after)
        } finally {
            for (profile in profiles) { DatabaseUtil.deleteCookieProfile(profile); CompatibilityFiles.cookiesFile(context, profile.id).delete() }
            PluginPreferences.EXPLICIT_COOKIE_PROFILE.updateInt(choice); com.junkfood.seal.util.COOKIES.updateBoolean(cookiesEnabled)
            keys.filterNot { it in existing }.forEach(kv::removeValueForKey)
            PluginState.setEnabled(PluginId.INTERACTION, interaction)
        }
    }

    /** 플랫폼 쿠키 저장 완료를 기다리며 값에 포함된 등호를 그대로 전달합니다. */
    private fun setCookie(url: String, value: String) {
        val done = CountDownLatch(1)
        val manager = CookieManager.getInstance()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.setCookie(url, value) { done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS)); manager.flush()
    }

    /** 화면에 실제로 나타난 쿠키 버튼과 가짜 프로필 행만 누릅니다. */
    private fun tap(label: String) {
        var node: android.view.accessibility.AccessibilityNodeInfo? = null
        repeat(100) {
            if (node == null) {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
                node = find(automation.rootInActiveWindow, label)
                if (node == null) android.os.SystemClock.sleep(100)
            }
        }
        var clickable = checkNotNull(node) { "화면에 없습니다: $label" }
        while (!clickable.isClickable) clickable = checkNotNull(clickable.parent)
        assertTrue(clickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        android.os.SystemClock.sleep(150)
    }

    /** 쿠키 값은 읽거나 출력하지 않고 화면의 버튼 텍스트만 찾습니다. */
    private fun find(node: android.view.accessibility.AccessibilityNodeInfo?, label: String): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}
