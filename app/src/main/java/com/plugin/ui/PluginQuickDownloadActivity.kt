package com.plugin.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.SettingsProvider
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.matchUrlFromSharedText
import com.junkfood.seal.util.setLanguage
import com.plugin.runtime.PluginAppHelper
import com.plugin.runtime.PluginDownloadDialogViewModel
import kotlinx.coroutines.runBlocking
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.KoinContext

/** 앱을 먼저 열지 않은 공유 요청도 동일한 플러그인 런타임과 창에 연결합니다. */
class PluginQuickDownloadActivity : AppCompatActivity() {
    private val dialogViewModel: PluginDownloadDialogViewModel by viewModel()

    /** 공유 링크를 설정 화면에 표시하며 실제 다운로드는 사용자 선택 후 시작합니다. */
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PluginAppHelper.initDownloader(applicationContext)
        if (Build.VERSION.SDK_INT < 33) {
            runBlocking { setLanguage(PreferenceUtil.getLocaleFromPreference()) }
        }
        enableEdgeToEdge()
        if (savedInstanceState == null && !showSharedLink(intent)) {
            finish()
            return
        }
        setContent {
            KoinContext {
                SettingsProvider(calculateWindowSizeClass(this).widthSizeClass) {
                    SealTheme(darkTheme = LocalDarkTheme.current.isDarkTheme(),
                        isHighContrastModeEnabled = LocalDarkTheme.current.isHighContrastModeEnabled) {
                        PluginDownloadDialogHost(dialogViewModel, onFinished = ::finish)
                    }
                }
            }
        }
    }

    /** 빠른 다운로드 창이 열려 있을 때 받은 다음 공유 요청을 반영합니다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showSharedLink(intent)
    }

    /** 텍스트 또는 보기 요청의 URL만 읽고 원본 Intent는 재생성을 위해 보존합니다. */
    private fun showSharedLink(intent: Intent): Boolean {
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(::matchUrlFromSharedText)
            else -> null
        }
        if (url.isNullOrBlank()) return false
        dialogViewModel.postAction(Action.ShowSheet(listOf(url)))
        return true
    }
}
