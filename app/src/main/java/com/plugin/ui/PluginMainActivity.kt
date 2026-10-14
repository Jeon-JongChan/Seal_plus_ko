package com.plugin.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.ui.common.LocalDarkTheme
import com.junkfood.seal.ui.common.SettingsProvider
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.theme.SealTheme
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.matchUrlFromSharedText
import com.junkfood.seal.util.setLanguage
import kotlinx.coroutines.runBlocking
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.compose.KoinContext

/**
 * 플러그인 전용 메인 액티비티입니다.
 * AndroidManifest.xml의 메인 진입점으로 등록되어 PluginAppEntry를 호스팅합니다.
 * 원본 MainActivity.kt 및 com.junkfood.seal 소스의 수정을 완전히 방지합니다.
 */
class PluginMainActivity : AppCompatActivity() {
    private val dialogViewModel: com.plugin.runtime.PluginDownloadDialogViewModel by viewModel()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    /** 런타임을 연결하고 새 실행에서 받은 공유 링크를 다운로드 설정 화면에 전달합니다. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < 33) {
            runBlocking { setLanguage(PreferenceUtil.getLocaleFromPreference()) }
        }
        enableEdgeToEdge()
        com.plugin.runtime.PluginAppHelper.initDownloader(this)

        context = this.baseContext
        if (savedInstanceState == null) {
            intent.getSharedURL()?.let { url ->
                dialogViewModel.postAction(DownloadDialogViewModel.Action.ShowSheet(listOf(url)))
            }
        }
        setContent {
            KoinContext {
                val windowSizeClass = calculateWindowSizeClass(this)
                SettingsProvider(windowWidthSizeClass = windowSizeClass.widthSizeClass) {
                    SealTheme(
                        darkTheme = LocalDarkTheme.current.isDarkTheme(),
                        isHighContrastModeEnabled = LocalDarkTheme.current.isHighContrastModeEnabled,
                    ) {
                        androidx.compose.runtime.key(com.plugin.compatibility.TemporarySession.root) {
                            PluginAppEntry(dialogViewModel = dialogViewModel)
                        }
                    }
                }
            }
        }
    }

    /** 실행 중 다시 받은 링크를 현재 설정 화면에 전달합니다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getSharedURL()
        if (url != null) {
            dialogViewModel.postAction(DownloadDialogViewModel.Action.ShowSheet(listOf(url)))
        }
    }

    /** 공유 텍스트 또는 보기 요청에서 URL을 추출합니다. */
    private fun Intent.getSharedURL(): String? {
        val intent = this

        return when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString
            }

            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedContent ->
                    intent.removeExtra(Intent.EXTRA_TEXT)
                    matchUrlFromSharedText(sharedContent).also { matchedUrl ->
                        if (sharedUrlCached != matchedUrl) {
                            sharedUrlCached = matchedUrl
                        }
                    }
                }
            }

            else -> {
                null
            }
        }
    }

    companion object {
        private const val TAG = "PluginMainActivity"
        private var sharedUrlCached = ""
    }
}

