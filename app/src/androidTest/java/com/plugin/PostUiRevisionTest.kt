package com.plugin

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.R
import com.junkfood.seal.ui.theme.SealTheme
import com.plugin.compatibility.*
import com.plugin.ui.PluginMainActivity
import org.junit.Assert.*
import org.junit.Test

/** 작은 카드에서 정보 펼치기와 이미지 오버레이의 위치를 실제 UI로 확인합니다. */
class PostUiRevisionTest {
    /** 계정은 한 번만 표시하고 더보기를 누른 뒤에만 본문·태그를 표시합니다. */
    @Test fun postDetailsAndOverlayFit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val enabled = PluginState.isEnabled(PluginId.POST_INFO)
        val oldText = SocialPostMetadataPreferences.showText; val oldAuthor = SocialPostMetadataPreferences.showAuthor; val oldTags = SocialPostMetadataPreferences.showHashtags
        val url = "https://instagram.com/p/REVIEW_CARD/"
        val old = SocialPostMetadataStore.get(url)
        try {
            PluginState.setEnabled(PluginId.POST_INFO, true)
            SocialPostMetadataPreferences.showText = true; SocialPostMetadataPreferences.showAuthor = true; SocialPostMetadataPreferences.showHashtags = true
            SocialPostMetadataStore.save(SocialPostMetadata(url, "review_owner", text = "펼쳐야 보이는 본문", hashtags = listOf("#검증")))
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme {
                    com.plugin.queue.VideoListItem(thumbnailModel = R.drawable.sample3, title = "review_owner [123]", uploader = "review_owner", postUrl = url,
                        onWallpaperClick = {}, onInstagramClick = {}, onButtonClick = {})
                } } }
                await(context.getString(R.string.loc_post_more))
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "펼쳐야 보이는 본문"))
                assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, "@review_owner"))
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "review_owner"))
                val tools = Rect(); await(context.getString(R.string.loc_wallpaper_setting)).getBoundsInScreen(tools)
                assertTrue("오버레이가 썸네일 폭을 넘었습니다: $tools", tools.right <= 180 * context.resources.displayMetrics.density)
                var more = await(context.getString(R.string.loc_post_more))
                while (!more.isClickable) more = checkNotNull(more.parent)
                assertTrue(more.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await("펼쳐야 보이는 본문"); await("#검증")
                val image = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(context.cacheDir, "post-ui-revision.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
        } finally {
            SocialPostMetadataStore.save(old ?: SocialPostMetadata(postUrl = url))
            SocialPostMetadataPreferences.showText = oldText; SocialPostMetadataPreferences.showAuthor = oldAuthor; SocialPostMetadataPreferences.showHashtags = oldTags
            PluginState.setEnabled(PluginId.POST_INFO, enabled)
        }
    }

    /** 대기열·다운로드 상세의 번호 폭과 이미지 위 버튼 위치를 확인합니다. */
    @Test fun pageNumberAndQuickActionsFit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val paths = (1..2).map { java.io.File(context.cacheDir, "page-review-$it.png") }
        val bitmap = android.graphics.Bitmap.createBitmap(96, 160, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(60, 140, 170))
        paths.forEach { file -> file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }; bitmap.recycle()
        val aiEnabled = PluginState.isEnabled(PluginId.IMAGE_AI)
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val infoKey = PluginPreferences.MEDIA_INFO_OVERLAY
        val infoExisted = kv.containsKey(infoKey)
        val oldInfo = kv.decodeBool(infoKey, false)
        PluginState.setEnabled(PluginId.IMAGE_AI, true)
        try {
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                for (queue in listOf(false, true)) {
                    kv.encode(infoKey, false)
                    scenario.onActivity { it.setContent { SealTheme {
                        if (queue) com.plugin.queue.PostMediaViewer(paths.map { it.path }, paths.first().path) {}
                        else com.plugin.library.PostMediaViewer(paths.map { it.path }, paths.first().path) {}
                    } } }
                    val number = Rect(); await("1 / 2").getBoundsInScreen(number)
                    assertTrue("페이지 번호 폭이 부족합니다.", number.width() > 80 * context.resources.displayMetrics.density)
                    val quick = Rect(); await(context.getString(R.string.loc_wallpaper_setting)).getBoundsInScreen(quick)
                    assertTrue("퀵 버튼이 미디어 영역에 없습니다.", quick.top >= number.bottom)
                    var info = await(context.getString(R.string.loc_media_info_overlay))
                    while (!info.isClickable) info = checkNotNull(info.parent)
                    assertTrue(info.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    var ai = await("이미지 AI 보정")
                    while (!ai.isClickable) ai = checkNotNull(ai.parent)
                    assertTrue(ai.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    await("균형")
                    var close = await("닫기")
                    while (!close.isClickable) close = checkNotNull(close.parent)
                    assertTrue(close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                }
                val image = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(context.cacheDir, "post-page-revision.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
        } finally {
            PluginState.setEnabled(PluginId.IMAGE_AI, aiEnabled)
            if (infoExisted) kv.encode(infoKey, oldInfo) else kv.removeValueForKey(infoKey)
            paths.forEach { it.delete() }
        }
    }

    /** 실제 H.264 영상의 재생 준비와 시스템 서비스 연결, 취소 시 원본 보존을 확인합니다. */
    @Test fun videoWallpaperPlaysAndCancellationPreservesSource() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val video = java.io.File(context.cacheDir, "wallpaper-review.mp4")
        val packages = java.io.File(context.noBackupFilesDir, "youtubedl-android/packages")
        val process = ProcessBuilder(java.io.File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").path,
            "-y", "-nostdin", "-f", "lavfi", "-i", "testsrc2=size=96x160:rate=10", "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", video.path)
            .redirectErrorStream(true).apply { environment()["LD_LIBRARY_PATH"] = listOf("ffmpeg", "python", "aria2c").joinToString(":") { packages.resolve("$it/usr/lib").path } }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output.takeLast(1000), 0, process.waitFor())
        val original = video.readBytes()
        val active = VideoWallpaperSource.selected(context, false)
        var candidate: String? = null
        try {
            candidate = VideoWallpaperSource.prepare(context, video.path)
            assertArrayEquals(original, checkNotNull(VideoWallpaperSource.selected(context, true)).readBytes())
            assertEquals(active, VideoWallpaperSource.selected(context, false))
            val service = android.content.ComponentName(context, VideoWallpaperService::class.java)
            val serviceInfo = context.packageManager.getServiceInfo(service, 0)
            assertEquals("android.permission.BIND_WALLPAPER", serviceInfo.permission)
            assertTrue(serviceInfo.exported)
            assertEquals(service, VideoWallpaperSource.intent(context).getParcelableExtra<android.content.ComponentName>(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT))
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme {
                    com.plugin.ui.WallpaperPreviewDialog(listOf(com.plugin.ui.WallpaperPageItem(video.path)), onDismissRequest = {})
                } } }
                await(context.getString(R.string.loc_video_wallpaper_next))
                val applyLabel = context.getString(R.string.loc_wallpaper_apply)
                repeat(100) {
                    if (!await(applyLabel).isEnabled) SystemClock.sleep(100)
                }
                assertTrue("영상 디코더가 준비되지 않았습니다.", await(applyLabel).isEnabled)
            }
            VideoWallpaperSource.discard(context, checkNotNull(candidate))
            candidate = null
            assertEquals(active, VideoWallpaperSource.selected(context, false))
            assertArrayEquals(original, video.readBytes())
        } finally { candidate?.let { VideoWallpaperSource.discard(context, it) }; video.delete() }
    }

    /** 재구성 이후의 실제 접근성 노드를 기다립니다. */
    private fun await(label: String): AccessibilityNodeInfo {
        repeat(100) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
            find(automation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        error("화면에 없습니다: $label")
    }

    /** 본문·버튼 설명이 있는 노드를 재귀적으로 찾습니다. */
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}

