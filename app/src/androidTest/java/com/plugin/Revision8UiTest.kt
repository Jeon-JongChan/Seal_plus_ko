package com.plugin

import androidx.compose.ui.layout.onSizeChanged
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.R
import com.junkfood.seal.ui.theme.SealTheme
import com.plugin.compatibility.WallpaperHelper
import com.plugin.ui.PluginMainActivity
import com.plugin.ui.WallpaperPageItem
import com.plugin.ui.WallpaperPreviewDialog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 실제 배경화면을 변경하지 않고 크롭·페이지 선택·화면 대상 UI를 검증합니다. */
class Revision8UiTest {
    /** 실제 저장소의 정렬·음량과 정보 없는 카드의 여백을 한 번에 확인하고 설정을 복원합니다. */
    @Test fun persistentViewerSettingsAndEmptyPostInfo() {
        val prefs = com.plugin.compatibility.PluginPreferences
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val keys = listOf(prefs.HISTORY_SORT_BY_NAME, prefs.QUEUE_SORT_BY_NAME, prefs.MEDIA_VOLUME)
        val existingKeys = keys.filter { kv.containsKey(it) }.toSet()
        val history = kv.decodeBool(prefs.HISTORY_SORT_BY_NAME, false)
        val queue = kv.decodeBool(prefs.QUEUE_SORT_BY_NAME, false)
        val volume = kv.decodeFloat(prefs.MEDIA_VOLUME, 1f)
        val enabled = PluginState.isEnabled(PluginId.POST_INFO)
        val store = com.plugin.compatibility.SocialPostMetadataStore
        val url = "https://instagram.com/p/REVIEW_EMPTY_METADATA/"
        val metadata = store.get(url)
        try {
            prefs.historySortByName = true; prefs.queueSortByName = false; prefs.mediaVolume = 0.35f
            assertTrue(kv.decodeBool(prefs.HISTORY_SORT_BY_NAME))
            assertFalse(kv.decodeBool(prefs.QUEUE_SORT_BY_NAME))
            assertEquals(0.35f, kv.decodeFloat(prefs.MEDIA_VOLUME), 0.0001f)
            assertTrue(prefs.historySortByName); assertFalse(prefs.queueSortByName)
            assertEquals(0.35f, prefs.mediaVolume, 0.0001f)
            prefs.queueSortByName = true; assertTrue(prefs.historySortByName)
            prefs.mediaVolume = 0f; assertEquals(0f, kv.decodeFloat(prefs.MEDIA_VOLUME), 0f)
            kv.encode(prefs.MEDIA_VOLUME, Float.NaN); assertEquals(1f, prefs.mediaVolume, 0f)
            kv.encode(prefs.MEDIA_VOLUME, 2f); assertEquals(1f, prefs.mediaVolume, 0f)
            prefs.mediaVolume = -1f; assertEquals(0f, prefs.mediaVolume, 0f)
            PluginState.setEnabled(PluginId.POST_INFO, true)
            store.save(com.plugin.compatibility.SocialPostMetadata(postUrl = url))
            val height = java.util.concurrent.atomic.AtomicInteger(-1)
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.onSizeChanged { height.set(it.height) }) {
                            com.plugin.ui.PostInfoBadge(url)
                        }
                        androidx.compose.material3.Text("빈 정보 확인")
                    }
                } } }
                awaitNode("빈 정보 확인")
                assertEquals(0, height.get())
            }
        } finally {
            kv.encode(prefs.HISTORY_SORT_BY_NAME, history); kv.encode(prefs.QUEUE_SORT_BY_NAME, queue); kv.encode(prefs.MEDIA_VOLUME, volume)
            keys.filter { it !in existingKeys }.forEach { kv.removeValueForKey(it) }
            store.save(metadata ?: com.plugin.compatibility.SocialPostMetadata(postUrl = url))
            PluginState.setEnabled(PluginId.POST_INFO, enabled)
        }
    }

    /** URL·계정명이 같아도 늦게 수집된 설명문을 열린 카드에 바로 표시합니다. */
    @Test fun postInfoUpdatesWithoutChangingItsUrl() {
        val store = com.plugin.compatibility.SocialPostMetadataStore
        val prefs = com.plugin.compatibility.SocialPostMetadataPreferences
        val url = "https://instagram.com/p/REVIEW_LIVE_METADATA/"
        val before = store.get(url)
        val enabled = PluginState.isEnabled(PluginId.POST_INFO)
        val showText = prefs.showText
        try {
            PluginState.setEnabled(PluginId.POST_INFO, true); prefs.showText = true
            store.save(com.plugin.compatibility.SocialPostMetadata(postUrl = url, author = "review", text = "수집 전 설명"))
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme { com.plugin.ui.PostInfoBadge(url, fallbackAuthor = "review") } } }
                tap(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.loc_post_more))
                awaitNode("수집 전 설명")
                store.save(com.plugin.compatibility.SocialPostMetadata(postUrl = url, author = "review", text = "새로 수집한 설명"))
                awaitNode("새로 수집한 설명")
            }
        } finally {
            store.save(before ?: com.plugin.compatibility.SocialPostMetadata(postUrl = url))
            prefs.showText = showText; PluginState.setEnabled(PluginId.POST_INFO, enabled)
        }
    }

    /** 중앙 채우기 사본은 원본을 변경하지 않으며 로더가 화면 크기를 넘지 않습니다. */
    @Test fun wallpaperLoadingBoundsAndCrop(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        android.graphics.Canvas(source).apply {
            drawRect(0f, 0f, 200f, 400f, android.graphics.Paint().apply { color = Color.RED })
            drawRect(600f, 0f, 800f, 400f, android.graphics.Paint().apply { color = Color.BLUE })
        }
        val file = File(context.cacheDir, "wallpaper-check-${java.util.UUID.randomUUID()}.png")
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val cropped = WallpaperHelper.centerCrop(source, 200, 400)
            try { assertEquals(200, cropped.width); assertEquals(Color.GREEN, cropped.getPixel(100, 200)); assertEquals(Color.GREEN, cropped.getPixel(1, 200)); assertEquals(Color.RED, source.getPixel(1, 200)); assertFalse(source.isRecycled) }
            finally { cropped.recycle() }
            val loaded = checkNotNull(WallpaperHelper.loadBitmap(context, file.path))
            try {
                assertTrue(loaded.width in 1..2048); assertTrue(loaded.height in 1..2048)
                assertEquals(Color.GREEN, loaded.getPixel(loaded.width / 2, loaded.height / 2))
            } finally { loaded.recycle() }
        } finally { source.recycle(); file.delete() }
    }

    /** 전체 화면의 페이지 카드와 홈·잠금·둘 다 선택이 접근 가능한지 확인합니다. */
    @Test fun wallpaperPagesAndTargetsAreVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "wallpaper-ui-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val pages = listOf(Color.RED, Color.BLUE).mapIndexed { index, color ->
                val file = File(root, "$index.png")
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                WallpaperPageItem(file.path, index)
            }
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme { WallpaperPreviewDialog(pages, onDismissRequest = {}) } } }
                awaitNode("1 / 2 페이지"); tap("2P"); awaitNode("2 / 2 페이지")
                tap(context.getString(R.string.loc_wallpaper_lock))
                awaitNode(context.getString(R.string.loc_wallpaper_home)); awaitNode(context.getString(R.string.loc_wallpaper_both))
                awaitNode(context.getString(R.string.loc_wallpaper_apply))
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(context.cacheDir, "revision8-wallpaper-ui.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        } finally { root.deleteRecursively() }
    }

    /** 큰 슬라이드 카드에서 한 페이지를 제외해 실제 선택 다운로드에 반영합니다. */
    @Test fun quickSelectionUsesLargeCardsAndSelectedPages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "selection-ui-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val media = (1..2).map { index ->
                val file = File(root, "$index.png")
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                com.plugin.compatibility.InstagramMedia("check$index", "https://instagram.com/p/REVIEW/", file.path, file.path, true, "검사", "review")
            }
            var downloaded: List<com.plugin.compatibility.InstagramMedia>? = null
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme {
                    com.plugin.instagram.InstagramSelectionPage(com.junkfood.seal.ui.page.downloadv2.configure.InstagramSelectionState(media, com.junkfood.seal.ui.page.downloadv2.configure.PreferencesMock)) { action ->
                        if (action is com.junkfood.seal.ui.page.downloadv2.configure.DownloadInstagramAction) downloaded = action.items
                    }
                } } }
                tap(context.getString(R.string.loc_media_page, 1, context.getString(R.string.loc_media_images)))
                tap(context.getString(R.string.loc_download_selected))
                assertEquals(listOf("check2"), downloaded?.map { it.id })
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(context.cacheDir, "revision8-selection-ui.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        } finally { root.deleteRecursively() }
    }

    /** 클릭 가능한 부모에 선택 동작을 전달합니다. */
    private fun tap(label: String) {
        var node = awaitNode(label)
        while (!node.isClickable) node = node.parent ?: error("클릭할 수 없습니다: $label")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /** 비동기 화면이 갱신될 때까지 버튼·페이지 정보를 기다립니다. */
    private fun awaitNode(label: String): AccessibilityNodeInfo {
        repeat(100) {
            findNode(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        error("화면에 없습니다: $label")
    }

    /** 접근성 텍스트와 설명으로 지정된 컨트롤을 찾습니다. */
    private fun findNode(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) findNode(node.getChild(i), label)?.let { return it }
        return null
    }
}
