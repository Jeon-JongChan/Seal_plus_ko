package com.plugin

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.VideoQuality
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.download.Task
import com.junkfood.seal.download.TaskFactory
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.*
import com.plugin.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** 실제 폴더 탐색·출력 이동·원본 선택 화면 연결·일반 오류의 쿠키 버튼을 확인합니다. */
class DownloadFoldersUiTest {
    /** 화질·링크 폴더는 숨기고 선택한 실제 폴더 안에서만 분류하며 기본 선택을 복원합니다. */
    @Test fun folderPickerAndRouting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = DownloadFolders.registered()
        val beforeQuick = DownloadFolders.quick()
        val enabled = PluginState.isEnabled(PluginId.FOLDERS)
        val root = File(App.privateDownloadDir)
        val fixture = root.resolve("000-folder-review-" + java.util.UUID.randomUUID())
        val instagram = fixture.resolve("Instagram").apply { mkdirs() }
        val hot = instagram.resolve("Hot").apply { mkdirs() }
        instagram.resolve("720p").mkdirs(); instagram.resolve("480p").mkdirs()
        val linkRoot = File(context.cacheDir, fixture.name).apply { mkdirs() }
        val link = linkRoot.resolve("outside-link")
        val folder = DownloadFolder(root.path, hot.relativeTo(root).invariantSeparatorsPath)
        val prefs = DownloadPreferences.EMPTY.copy(privateDirectory = true, subdirectoryExtractor = true).copyPluginOptions(mediaProcessing = true)
        val picked = AtomicReference<DownloadFolder?>()
        val selected = AtomicReference<DownloadPreferences>()
        try {
            android.system.Os.symlink(context.filesDir.path, link.path)
            PluginState.setEnabled(PluginId.FOLDERS, true)
            assertEquals(listOf(folder), DownloadFolders.children(folder.copy(relative = instagram.relativeTo(root).invariantSeparatorsPath), prefs))
            assertTrue(runCatching { DownloadFolders.local(DownloadFolder(linkRoot.path, "outside-link")) }.isFailure)
            assertTrue(runCatching { DownloadFolders.validate(folder.copy(root = context.cacheDir.path), prefs) }.isFailure)
            DownloadFolders.save(listOf(folder.copy(relative = instagram.relativeTo(root).invariantSeparatorsPath), folder))
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme { DownloadFolderPicker(prefs, {}, { picked.set(it) }) } } }
                tap(root.path); tap(fixture.name); tap("Instagram")
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "720p"))
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "480p"))
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, "outside-link"))
                tap("Hot"); tap(context.getString(R.string.download_folder_select_current))
                instrumentation.waitForIdleSync(); assertEquals(folder, picked.get())
                scenario.onActivity { it.setContent { SealTheme {
                    var value by remember { pluginPreferencesState(prefs) }
                    DownloadFolderSelector(value) { value = it; selected.set(it) }
                } } }
                tap(context.getString(R.string.plugin_download_folders)); tap(folder.label)
                instrumentation.waitForIdleSync(); assertEquals(folder, selected.get().selectedFolder)
                assertFalse(selected.get().useWebsiteFolder(image = true))
                tap(folder.label); tap(context.getString(R.string.download_folder_default))
                instrumentation.waitForIdleSync(); assertNull(selected.get().selectedFolder)
                assertTrue(selected.get().useWebsiteFolder(image = true))
                DownloadFolders.setQuick(folder)
                assertEquals(folder, DownloadFolders.quick())
                scenario.onActivity { it.setContent { SealTheme {
                    var value by remember { pluginPreferencesState(prefs) }
                    DownloadFolderSelector(value) { value = it; selected.set(it) }
                } } }
                val quickLabel = context.getString(R.string.download_folder_quick_label, folder.label)
                tap(quickLabel)
                instrumentation.waitForIdleSync(); assertEquals(folder, selected.get().selectedFolder)
                assertNull(find(instrumentation.uiAutomation.rootInActiveWindow, context.getString(R.string.download_folder_default)))
                tap(quickLabel)
                instrumentation.waitForIdleSync(); assertNull(selected.get().selectedFolder)
                assertTrue(selected.get().useWebsiteFolder(image = true))
                // 실제 포인터를 2초 이상 유지해 목록만 열리고 퀵 선택이 함께 바뀌지 않는지 확인합니다.
                val bounds = android.graphics.Rect().also { await(quickLabel).getBoundsInScreen(it) }
                val down = SystemClock.uptimeMillis()
                val automation = instrumentation.uiAutomation
                val press = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                assertTrue(automation.injectInputEvent(press, true)); press.recycle()
                SystemClock.sleep(2_200)
                val release = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP,
                    bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                assertTrue(automation.injectInputEvent(release, true)); release.recycle()
                await(context.getString(R.string.download_folder_default))
                assertNull(selected.get().selectedFolder)
                tap(folder.label); instrumentation.waitForIdleSync(); assertEquals(folder, selected.get().selectedFolder)
                val other = DownloadFolders.registered().first { it != folder }
                DownloadFolders.setQuick(other); assertEquals(other, DownloadFolders.quick())
                DownloadFolders.save(listOf(folder)); assertNull(DownloadFolders.quick())
                assertTrue(runCatching { DownloadFolders.setQuick(other) }.isFailure)
            }
            val source = hot.resolve("folder-review.jpg")
            val bitmap = android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(50, 100, 160))
            source.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) }; bitmap.recycle()
            val original = source.readBytes()
            val request = prefs.copyPluginOptions(selectedFolder = folder, classifyMedia = true, classificationUpperFirst = true,
                imageTiers = MediaRules.withDisabledTiers(MediaRules.DEFAULT_IMAGE_TIERS, "1080x1920"))
            val destination = MediaLibrary.finish(listOf(source.path), request, "folder-review", classificationQuality =
                VideoQuality(1920, 1080, 0.0, 0.0, source.length(), "image/jpeg"), mediaRoot = DownloadFolders.outputRoot(request, root)).single()
            assertEquals(hot.resolve("1440x2560/folder-review.jpg").path, destination)
            assertArrayEquals(original, File(destination).readBytes())
            assertFalse(hot.resolve("Images").exists())
            assertFalse(hot.resolve("Instagram").exists())
        } finally {
            DownloadFolders.save(before); DownloadFolders.setQuick(beforeQuick); PluginState.setEnabled(PluginId.FOLDERS, enabled)
            // 테스트가 만든 링크를 먼저 제거해 폴더 정리에서 앱 캐시로 넘어가지 않습니다.
            link.delete(); linkRoot.delete(); fixture.walkBottomUp().forEach { it.delete() }
        }
    }

    /** 일반 영상·재생목록·대기열 등록 오류도 쿠키 대화상자를 열고 같은 요청으로 재시도합니다. */
    @Test fun cookiesForGeneralFailures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val interaction = PluginState.isEnabled(PluginId.INTERACTION)
        val prefs = DownloadPreferences.EMPTY.copyPluginOptions(cookieProfileId = -1, cookieProfileExplicit = false)
        val actions = listOf<Action>(Action.FetchFormats("https://example.invalid/review", false, prefs),
            Action.FetchPlaylist("https://example.invalid/review", prefs), Action.DownloadWithPreset(listOf("https://example.invalid/review"), prefs))
        val retry = AtomicReference<Any?>()
        try {
            PluginState.setEnabled(PluginId.INTERACTION, false)
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                for (action in actions) {
                    scenario.onActivity { it.setContent { SealTheme { com.plugin.runtime.ErrorPage(state = SheetState.Error(action, IllegalStateException("검증용 로그인 실패")), onActionPost = { retry.set(it) }) } } }
                    tap(context.getString(R.string.cookies)); await(context.getString(R.string.use_cookies))
                    await(context.getString(R.string.refresh_cookies_desc))
                    tap(context.getString(androidx.appcompat.R.string.abc_action_mode_done))
                    tap(context.getString(R.string.loc_retry_action))
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertEquals(action, retry.get())
                }
            }
        } finally { PluginState.setEnabled(PluginId.INTERACTION, interaction) }
    }

    /** 원본 화면의 세 가지 등록 API에서 형식 값은 유지하고 선택한 폴더만 연결합니다. */
    @Test fun originalSelectionPreservesFolder() {
        val requests = mutableListOf<Task>()
        val native = object : DownloaderV2 {
            /** 검증에는 실행 대기열을 만들지 않습니다. */
            override fun getTaskStateMap() = androidx.compose.runtime.mutableStateMapOf<Task, Task.State>()
            /** 검증에서는 실행을 취소할 작업이 없습니다. */
            override fun cancel(task: Task) = false
            /** 검증은 네트워크 작업을 재시작하지 않습니다. */
            override fun restart(task: Task) = Unit
            /** 등록한 설정만 수집합니다. */
            override fun enqueue(task: Task) { requests += task }
            /** 상태가 있는 등록도 동일하게 설정만 수집합니다. */
            override fun enqueue(task: Task, state: Task.State) { requests += task }
            /** 검증 대기열은 삭제할 필요가 없습니다. */
            override fun remove(task: Task) = false
        }
        val folder = DownloadFolder(App.privateDownloadDir, "Instagram/Hot")
        val wrapper = folderAwareDownloader(native, folder)
        val prefs = DownloadPreferences.EMPTY.copy(formatIdString = "137+140").copyPluginOptions()
        val task = Task("https://example.invalid/review", preferences = prefs)
        val state = Task.State(Task.DownloadState.Idle, null, Task.ViewState())
        wrapper.enqueue(task); wrapper.enqueue(task, state); wrapper.enqueue(TaskFactory.TaskWithState(task, state))
        assertEquals(3, requests.size)
        for (request in requests) { assertEquals(folder, request.preferences.selectedFolder); assertEquals("137+140", request.preferences.formatIdString) }
        assertSame(native, folderAwareDownloader(native, null))
    }

    /** 접근성 노드를 새로 읽으며 비동기 폴더 조회·대화상자 표시를 기다립니다. */
    private fun await(label: String): AccessibilityNodeInfo {
        repeat(100) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
            find(automation.rootInActiveWindow, label)?.let { return it }
            SystemClock.sleep(100)
        }
        error("화면에 없습니다: $label")
    }

    /** 텍스트의 클릭 가능한 부모를 눌러 실제 UI 콜백을 실행합니다. */
    private fun tap(label: String) {
        var node = await(label)
        while (!node.isClickable) node = checkNotNull(node.parent)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(150)
    }

    /** 텍스트·설명을 기준으로 실제 노드를 찾습니다. */
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
}
