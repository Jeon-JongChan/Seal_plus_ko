package com.plugin

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateMapOf
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.ui.page.downloadv2.configure.PreferencesMock
import com.junkfood.seal.ui.theme.SealTheme
import com.plugin.compatibility.PluginPreferences
import com.plugin.queue.PluginTask
import com.plugin.ui.PluginMainActivity
import org.junit.Assert.*
import org.junit.Test

/** 실제 다운로드 없이 천 개 대기열의 점진 표시·설정 저장·전체 삭제 범위를 확인합니다. */
class QueuePaginationTest {
    /** 최신 다섯 개부터 스크롤로 추가하고 필터 변경 시 초기화하며 전체 삭제는 숨은 작업도 포함합니다. */
    @Test fun largeQueueLoadsInBatchesWithoutDroppingTasks() {
        val prefs = PluginPreferences
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val keys = listOf(prefs.QUEUE_INCREMENTAL_LOADING, prefs.QUEUE_SORT_BY_NAME)
        val existed = keys.filter { kv.containsKey(it) }.toSet()
        val previousLoading = prefs.queueIncrementalLoading
        val previousSort = prefs.queueSortByName
        val states = mutableStateMapOf<PluginTask, PluginTask.State>()
        (1..1000).forEach { index ->
            states[PluginTask("https://example.invalid/queue/$index", preferences = PreferencesMock,
                timeCreated = index.toLong())] = PluginTask.State(PluginTask.DownloadState.ReadyWithInfo,
                null, PluginTask.ViewState(title = "대기열 검사 $index"))
        }
        val removed = java.util.concurrent.atomic.AtomicInteger()
        try {
            prefs.queueIncrementalLoading = true; prefs.queueSortByName = false
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { SealTheme {
                    com.plugin.queue.DownloadPageImplV2(taskDownloadStateMap = states,
                        onActionPost = { _, _ -> removed.incrementAndGet() })
                } } }
                await("표시 5 / 1000")
                tap("목록으로 보기"); await("대기열 검사 1000")
                val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
                val scroll = findScrollable(root)
                assertNotNull("대기열 스크롤 영역", scroll)
                assertTrue(scroll!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                await("표시 10 / 1000")
                tap("시간순")
                tap("이름순"); await("표시 5 / 1000")
                tap("5개씩 불러오기"); await("표시 1000 / 1000")
                assertFalse(kv.decodeBool(prefs.QUEUE_INCREMENTAL_LOADING, true))
                tap("5개씩 불러오기"); await("표시 5 / 1000")
                assertTrue(prefs.queueIncrementalLoading)
                assertEquals(1000, states.size)
                println("삭제 전 접근성: " + labels(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow))
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                tap(context.getString(com.junkfood.seal.R.string.loc_queue_delete_all))
                tap(context.getString(com.junkfood.seal.R.string.loc_queue_delete_selected))
                assertEquals(1000, removed.get())
                assertEquals(1000, states.size)
            }
        } finally {
            prefs.queueIncrementalLoading = previousLoading; prefs.queueSortByName = previousSort
            keys.filter { it !in existed }.forEach { kv.removeValueForKey(it) }
        }
    }

    /** 접근성 트리에 표시된 정확한 문구를 제한 시간 안에 찾습니다. */
    private fun await(label: String, clickable: Boolean = false): AccessibilityNodeInfo {
        repeat(100) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
            find(automation.rootInActiveWindow, label, clickable)?.let { return it }
            SystemClock.sleep(100)
        }
        error("표시되지 않음: $label")
    }

    /** 글자를 감싼 실제 클릭 가능한 버튼을 누릅니다. */
    private fun tap(label: String) {
        var node: AccessibilityNodeInfo? = await(label, clickable = true)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull(label, node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(200)
    }

    /** 문구가 일치하는 접근성 노드를 재귀적으로 찾습니다. */
    private fun find(node: AccessibilityNodeInfo?, label: String, clickable: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if ((node.text?.toString() == label || node.contentDescription?.toString() == label) &&
            (!clickable || generateSequence(node) { it.parent }.any { it.isClickable })) return node
        repeat(node.childCount) { find(node.getChild(it), label, clickable)?.let { found -> return found } }
        return null
    }

    /** 검사 실패 시 실제 화면 문구를 로그에서 확인할 수 있도록 모읍니다. */
    private fun labels(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        return listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
            (0 until node.childCount).flatMap { labels(node.getChild(it)) }
    }

    /** 대기열의 스크롤 가능한 접근성 노드를 찾습니다. */
    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        if (node.isScrollable && bounds.height() > 150 &&
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) return node
        repeat(node.childCount) { findScrollable(node.getChild(it))?.let { found -> return found } }
        return null
    }
}
