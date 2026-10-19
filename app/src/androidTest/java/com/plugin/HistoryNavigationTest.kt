package com.plugin

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.mutableStateMapOf
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.ui.page.downloadv2.configure.PreferencesMock
import com.junkfood.seal.ui.theme.SealTheme
import com.plugin.compatibility.*
import com.plugin.queue.ActionButtons
import com.plugin.queue.PluginTask
import com.plugin.ui.PluginMainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 폴더 탐색·두 화면 정렬·게시글 상세 동작을 실제 접근성 UI로 검증합니다. */
class HistoryNavigationTest {
    /** 기본 화면과 확장 화면이 30개씩 추가하며 이력 원본을 모두 보존합니다. */
    @Test fun historyLoadsThirtyAtATime(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".devicecheck"))
        val token = java.util.UUID.randomUUID().toString()
        val paths = (1..65).map { java.io.File(context.cacheDir, "paging-$token/$it.mp4").path }
        val originalCount = PluginDatabaseUtil.getDownloadHistory().size
        try {
            paths.forEachIndexed { index, path -> PluginDatabaseUtil.insertInfoImmediately(
                DownloadedVideoInfo(0, "페이징 $token ${index + 1}", "", "https://example.invalid/$token/$index", "", path, "External")) }
            for (enhanced in listOf(false, true)) {
                ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                    scenario.onActivity { it.setContent { SealTheme {
                        com.plugin.library.EnhancedVideoListPage(enhanced = enhanced, onNavigateBack = {})
                    } } }
                    val total = originalCount + 65
                    awaitNode("표시 30 / $total")
                    for (attempt in 0 until 12) {
                        val automation = instrumentation.uiAutomation
                        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
                        val root = automation.rootInActiveWindow
                        // 푸터에 닿으면 자동 로딩하고, 짧은 화면에서도 더 보기 버튼으로 진행할 수 있습니다.
                        if (findNode(root, "표시 60 / $total") != null) break
                        findVerticalScroll(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(200)
                    }
                    awaitNode("표시 60 / $total")
                    assertEquals(total, PluginDatabaseUtil.getDownloadHistory().size)
                }
            }
        } finally { paths.forEach { PluginDatabaseUtil.deleteInfoByPath(it) } }
    }
    /** 같은 720p 하위 폴더도 사이트를 거쳐 탐색하고 전체보기로 돌아옵니다. */
    @Test fun navigatesFoldersAndRestoresAllView(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = createPluginPreferences()
        val base = if (preferences.sdcard) {
            val tree = android.net.Uri.parse(preferences.sdcardUri)
            val document = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            MediaRules.parentFolder(document.toString() + "/_")
        } else MediaLibrary.rootPath(preferences)
        val token = java.util.UUID.randomUUID().toString()
        val paths = listOf("$base/검사-A-$token/720p/a.mp4", "$base/검사-B-$token/720p/b.mp4")
        try {
            paths.forEachIndexed { index, path -> PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, "폴더 검사 ${index + 1}", "", "https://example.invalid/$token/$index", "", path, "External")) }
            val folders = PluginDatabaseUtil.getDownloadHistory().map { MediaRules.parentFolder(it.videoPath) }
            val root = MediaRules.historyFolderRoot(base, folders)
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme { com.plugin.library.EnhancedVideoListPage(onNavigateBack = {}) } } }
                awaitNode("폴더 검사 1"); tap("이름순"); tap("시간순"); tap("폴더별")
                var current = root
                while (current != base) {
                    val next = MediaRules.historyFolderChildren(current, listOf(base + "/_")) .single()
                    tap(next); current = next
                }
                tap("$base/검사-A-$token"); tap("$base/검사-A-$token/720p")
                awaitNode("폴더 검사 1")
                assertNull(findNode(instrumentation.uiAutomation.rootInActiveWindow, "폴더 검사 2"))
                tap("상위"); tap("상위")
                tap("$base/검사-B-$token"); tap("$base/검사-B-$token/720p")
                awaitNode("폴더 검사 2")
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                java.io.File(instrumentation.targetContext.cacheDir, "revision8-folder-ui.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
                bitmap.recycle()
                assertNull(findNode(instrumentation.uiAutomation.rootInActiveWindow, "폴더 검사 1"))
                tap("전체보기"); awaitNode("폴더 검사 1")
                val scroll = findVerticalScroll(instrumentation.uiAutomation.rootInActiveWindow)
                scroll?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                awaitNode("폴더 검사 2")
            }
        } finally { paths.forEach { PluginDatabaseUtil.deleteInfoByPath(it) } }
    }

    /** 카드 목록의 세로 스크롤만 찾아 가로 필터 동작과 구분합니다. */
    private fun findVerticalScroll(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (node.isScrollable && bounds.height() > 150 &&
            node.actionList.any { it.id in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }) return node
        repeat(node.childCount) { findVerticalScroll(node.getChild(it))?.let { found -> return found } }
        return null
    }

    /** 대기열 표시 정렬이 바뀌어도 입력 맵과 작업 생성 시간은 바뀌지 않습니다. */
    @Test fun queueSortChangesDisplayOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val old = PluginTask("https://example.invalid/a", preferences = PreferencesMock, timeCreated = 1)
        val recent = PluginTask("https://example.invalid/z", preferences = PreferencesMock, timeCreated = 2)
        val states = mutableStateMapOf(old to PluginTask.State(PluginTask.DownloadState.ReadyWithInfo, null, PluginTask.ViewState(title = "가 정렬 검사")),
            recent to PluginTask.State(PluginTask.DownloadState.ReadyWithInfo, null, PluginTask.ViewState(title = "하 정렬 검사")))
        val previousSort = com.plugin.compatibility.PluginPreferences.queueSortByName
        com.plugin.compatibility.PluginPreferences.queueSortByName = false
        try {
        ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { SealTheme { com.plugin.queue.DownloadPageImplV2(taskDownloadStateMap = states, onActionPost = { _, _ -> }) } } }
            tap("목록으로 보기"); awaitNode("하 정렬 검사")
            assertBefore("하 정렬 검사", "가 정렬 검사")
            tap("이름순"); assertBefore("가 정렬 검사", "하 정렬 검사")
            tap("시간순"); assertBefore("하 정렬 검사", "가 정렬 검사")
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(instrumentation.targetContext.cacheDir, "revision8-queue-sort-ui.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            assertEquals(setOf(old, recent), states.keys.toSet())
            assertEquals(1L, old.timeCreated); assertEquals(2L, recent.timeCreated)
        }
        } finally { com.plugin.compatibility.PluginPreferences.queueSortByName = previousSort }
    }

    /** 여러 이미지 게시글은 열기·공유만 표시하고 번호별 버튼을 만들지 않습니다. */
    @Test fun postDetailsHaveNoRepeatedImageButtons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val task = PluginTask("https://example.invalid/post", preferences = PreferencesMock)
        var opened: String? = null
        ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { SealTheme { LazyRow {
                ActionButtons(task, PluginTask.DownloadState.Completed("/first.png", listOf("/first.png", "/second.png")),
                    PluginTask.ViewState(isImage = true, mediaCount = 2), {}, { _, action ->
                        if (action is com.plugin.queue.UiAction.OpenFile) opened = action.filePath
                    })
            } } } }
            tap(context.getString(R.string.open_file))
            assertEquals("/first.png", opened)
            val root = instrumentation.uiAutomation.rootInActiveWindow
            assertNull(findNode(root, context.getString(R.string.loc_open_image, 1)))
            assertNull(findNode(root, context.getString(R.string.loc_open_image, 2)))
            awaitNode(context.getString(R.string.share))
        }
    }

    /** 비동기 정렬 반영 후 두 제목이 화면에 나타나는 순서를 확인합니다. */
    private fun assertBefore(first: String, second: String) {
        repeat(50) {
            // 재정렬 뒤 이전 노드 위치가 남는 접근성 캐시를 비워 현재 화면 배치를 검사합니다.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
            val root = automation.rootInActiveWindow
            val a = findNode(root, first); val b = findNode(root, second)
            if (a != null) {
                val ar = android.graphics.Rect(); val br = android.graphics.Rect()
                a.getBoundsInScreen(ar); b?.getBoundsInScreen(br)
                if (!ar.isEmpty && (b == null || br.isEmpty || ar.top < br.top || ar.top == br.top && ar.left < br.left)) return
            }
            SystemClock.sleep(100)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.cacheDir, "revision8-sort-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val root = instrumentation.uiAutomation.rootInActiveWindow
        val a = android.graphics.Rect(); val b = android.graphics.Rect()
        findNode(root, first)?.getBoundsInScreen(a); findNode(root, second)?.getBoundsInScreen(b)
        error("정렬 순서가 다릅니다: $first $a, $second $b")
    }
    /** 접근성 자식에서 클릭 가능한 부모 버튼을 찾습니다. */
    private fun tap(label: String) {
        var node = awaitNode(label)
        while (!node.isClickable) node = node.parent ?: error("클릭할 수 없습니다: $label")
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(250)
    }
    /** 비동기 목록 갱신이 끝날 때까지 텍스트를 기다립니다. */
    private fun awaitNode(label: String): AccessibilityNodeInfo {
        var forward = true
        repeat(100) { attempt ->
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
            findNode(automation.rootInActiveWindow, label)?.let { return it }
            if (attempt > 0 && attempt % 10 == 0) findVerticalScroll(automation.rootInActiveWindow)?.let { scroll ->
                var action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (scroll.actionList.none { it.id == action }) {
                    forward = !forward
                    action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                scroll.performAction(action)
            }
            SystemClock.sleep(100)
        }
        error("화면에 없습니다: $label")
    }
    /** 텍스트와 설명으로 현재 화면의 접근성 노드를 탐색합니다. */
    private fun findNode(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) findNode(node.getChild(i), label)?.let { return it }
        return null
    }
}
