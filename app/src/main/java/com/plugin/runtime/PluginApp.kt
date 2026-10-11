package com.plugin.runtime

import com.junkfood.seal.App
import com.junkfood.seal.download.DownloaderV2
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.koin.androidx.viewmodel.dsl.viewModel

/**
 * 플러그인 런타임 초기화 헬퍼입니다.
 */
object PluginAppHelper {
    private var initialized = false

    /** 플러그인 다운로더를 Koin 모듈에 주입합니다. */
    @Synchronized
    fun initDownloader(context: android.content.Context) {
        // 액티비티 재생성 시 실행 중인 대기열을 가진 기존 엔진을 유지합니다.
        if (initialized) return
        // Compose 탐색 화면이 서로 다른 스냅샷에서 처음 생성된 상태를 읽지 않도록 준비합니다.
        com.plugin.PluginState.initialize()
        val appContext = context.applicationContext
        val downloader = PluginDownloaderV2(appContext)
        loadKoinModules(
            module {
                single<DownloaderV2> { downloader }
                viewModel { PluginDownloadDialogViewModel(get()) }
            }
        )
        initialized = true
    }
}

