package com.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.download.DownloaderV2
import com.plugin.runtime.PluginAppHelper
import com.plugin.runtime.PluginDownloaderV2
import com.plugin.ui.PluginMainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import android.content.Intent
import android.net.Uri
import com.plugin.ui.PluginQuickDownloadActivity
import com.plugin.runtime.PluginDownloadDialogViewModel
import org.koin.androidx.viewmodel.ext.android.getViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState
import com.plugin.compatibility.copyPluginOptions
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import com.junkfood.seal.R

/** 화면 재생성에도 동일한 런타임과 대기열을 유지하는지 실제 앱 환경에서 검사합니다. */
@RunWith(AndroidJUnit4::class)
class PluginRuntimeTest {
    /** 모든 확장이 꺼진 입력창과 대기열 확장 없이 활성화한 입력창을 실제 화면에서 구분합니다. */
    @Test
    fun inputPluginIsVisibleInBothEntryPoints() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        PluginAppHelper.initDownloader(context)
        val previous = PluginId.entries.associateWith(PluginState::isEnabled)
        try {
            instrumentation.runOnMainSync { PluginId.entries.forEach { PluginState.setEnabled(it, false) } }
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                var label = ""
                scenario.onActivity {
                    label = it.getString(R.string.video_url)
                    it.getViewModel<PluginDownloadDialogViewModel>().postAction(Action.ShowSheet())
                }
                awaitText(label)
            }
            instrumentation.runOnMainSync { PluginState.setEnabled(PluginId.INTERACTION, true) }
            for (activityClass in listOf(PluginMainActivity::class.java, PluginQuickDownloadActivity::class.java)) {
                val intent = Intent(context, activityClass).setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://example.invalid/plugin-ui-check"))
                ActivityScenario.launch<androidx.appcompat.app.AppCompatActivity>(intent).use { scenario ->
                    var label = ""
                    scenario.onActivity { label = it.getString(R.string.loc_media_link) }
                    awaitText(label)
                }
            }
        } finally {
            instrumentation.runOnMainSync { previous.forEach { (id, enabled) -> PluginState.setEnabled(id, enabled) } }
        }
    }

    /** 접근성 화면에 지정한 입력 필드가 나타날 때까지 짧게 기다립니다. */
    private fun awaitText(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(40) {
            if (hasText(automation.rootInActiveWindow, text)) return
            SystemClock.sleep(100)
        }
        fail("입력창에 표시되지 않은 문구: $text")
    }

    /** 부모의 병합된 설명과 자식 노드까지 확인합니다. */
    private fun hasText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.contains(text) == true || node.contentDescription?.contains(text) == true) return true
        return (0 until node.childCount).any { hasText(node.getChild(it), text) }
    }

    /** 앱과 빠른 다운로드 모두 같은 모델로 공유 링크를 받고 화면 재생성 후에도 유지합니다. */
    @Test
    fun sharedEntryPointsUsePluginDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://example.invalid/plugin-dialog-check"
        for (activityClass in listOf(PluginMainActivity::class.java, PluginQuickDownloadActivity::class.java)) {
            val intent = Intent(context, activityClass).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
            ActivityScenario.launch<androidx.appcompat.app.AppCompatActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    val model = activity.getViewModel<PluginDownloadDialogViewModel>()
                    assertEquals(SheetState.Configure(listOf(url)), model.sheetStateFlow.value)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(SheetState.Configure(listOf(url)),
                        activity.getViewModel<PluginDownloadDialogViewModel>().sheetStateFlow.value)
                }
            }
        }
    }

    /** 확장 요청이 원본 처리기에 누락되지 않고 네트워크 없이 검증 가능한 오류 상태로 전달됩니다. */
    @Test
    fun extensionActionsReachController() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PluginAppHelper.initDownloader(context)
        val before = PluginState.isEnabled(PluginId.SOCIAL)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                PluginState.setEnabled(PluginId.SOCIAL, true)
                val model = PluginDownloadDialogViewModel(GlobalContext.get().get())
                model.postAction(Action.ShowSheet())
                model.postAction(Action.FetchPlaylist("https://example.invalid/check",
                    DownloadPreferences.EMPTY.copyPluginOptions(mediaSelection = 2)))
                assertTrue(model.sheetStateFlow.value is SheetState.Error)
                model.postAction(Action.HideSheet)
            } finally {
                PluginState.setEnabled(PluginId.SOCIAL, before)
            }
        }
    }

    /** 화면이 처음 그려지고 재생성될 때 상태 스냅샷과 엔진을 유지합니다. */
    @Test
    fun mainActivitySurvivesRecreation() {
        ActivityScenario.launch(PluginMainActivity::class.java).use { activity ->
            val first = GlobalContext.get().get<DownloaderV2>()
            activity.recreate()
            activity.onActivity { assertSame(first, GlobalContext.get().get<DownloaderV2>()) }
        }
    }

    /** 초기화를 반복해도 엔진 교체나 두 번째 작업 루프가 만들어지지 않습니다. */
    @Test
    fun repeatedInitializationKeepsDownloader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PluginAppHelper.initDownloader(context)
        val first = GlobalContext.get().get<DownloaderV2>()
        assertTrue(first is PluginDownloaderV2)
        val queue = first.getTaskStateMap()
        PluginAppHelper.initDownloader(context)
        val second = GlobalContext.get().get<DownloaderV2>()
        assertSame(first, second)
        assertSame(queue, second.getTaskStateMap())
    }
}
