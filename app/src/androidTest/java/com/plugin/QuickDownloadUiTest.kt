package com.plugin

import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.plugin.runtime.PluginDownloadDialogViewModel
import com.plugin.ui.PluginQuickDownloadActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 실제 공유 창의 종료 요청이 두 번째 닫기 애니메이션을 기다리지 않는지 검사합니다. */
class QuickDownloadUiTest {
    /** 조회·다운로드 없이 검사 Activity의 닫기 요청부터 종료 시작까지 측정합니다. */
    @Test fun closeStartsWithoutWaitingForSheetAnimation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, PluginQuickDownloadActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.invalid/ui-check")
        repeat(5) {
        ActivityScenario.launch<PluginQuickDownloadActivity>(intent).use { scenario ->
            instrumentation.waitForIdleSync()
            val activity = AtomicReference<PluginQuickDownloadActivity>()
            val started = AtomicLong()
            scenario.onActivity {
                activity.set(it)
                started.set(SystemClock.uptimeMillis())
                ViewModelProvider(it)[PluginDownloadDialogViewModel::class.java].postAction(Action.HideSheet)
            }
            val finishing = AtomicBoolean(false)
            while (!finishing.get() && SystemClock.uptimeMillis() - started.get() < 2_000) {
                instrumentation.runOnMainSync { finishing.set(activity.get().isFinishing) }
                if (!finishing.get()) SystemClock.sleep(10)
            }
            val elapsed = SystemClock.uptimeMillis() - started.get()
            android.util.Log.i("QuickDownloadClose", "종료 시작까지 ${elapsed}ms")
            assertTrue("빠른 창 종료가 ${elapsed}ms 지연됐습니다.", finishing.get() && elapsed < 250)
        }
        }
    }
}
