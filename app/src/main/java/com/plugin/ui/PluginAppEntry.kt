package com.plugin.ui

import android.webkit.CookieManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.HapticFeedback.slightHapticFeedback
import com.junkfood.seal.ui.common.LocalWindowWidthState
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.common.animatedComposable
import com.junkfood.seal.ui.common.animatedComposableVariant
import com.junkfood.seal.ui.common.arg
import com.junkfood.seal.ui.common.id
import com.junkfood.seal.ui.common.slideInVerticallyComposable
import com.junkfood.seal.ui.page.AppUpdater
import com.junkfood.seal.ui.page.YtdlpUpdater
import com.junkfood.seal.ui.page.command.TaskListPage
import com.junkfood.seal.ui.page.command.TaskLogPage
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialog
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.DownloadPageV2
import com.junkfood.seal.ui.page.settings.about.AboutPage
import com.junkfood.seal.ui.page.settings.about.CreditsPage
import com.junkfood.seal.ui.page.settings.about.SponsorsPage
import com.junkfood.seal.ui.page.settings.about.UpdatePage
import com.junkfood.seal.ui.page.settings.appearance.AppearancePreferences
import com.junkfood.seal.ui.page.settings.appearance.DarkThemePreferences
import com.junkfood.seal.ui.page.settings.appearance.LanguagePage
import com.junkfood.seal.ui.page.settings.command.TemplateEditPage
import com.junkfood.seal.ui.page.settings.command.TemplateListPage
import com.junkfood.seal.ui.page.settings.directory.DownloadDirectoryPreferences
import com.junkfood.seal.ui.page.settings.format.DownloadFormatPreferences
import com.junkfood.seal.ui.page.settings.format.SubtitlePreference
import com.junkfood.seal.ui.page.settings.general.GeneralDownloadPreferences
import com.junkfood.seal.ui.page.settings.interaction.InteractionPreferencePage
import com.junkfood.seal.ui.page.settings.network.CookieProfilePage
import com.junkfood.seal.ui.page.settings.network.CookiesViewModel
import com.junkfood.seal.ui.page.settings.network.NetworkPreferences
import com.junkfood.seal.ui.page.settings.network.WebViewPage
import com.junkfood.seal.ui.page.settings.troubleshooting.TroubleShootingPage
import com.junkfood.seal.ui.page.videolist.VideoListPage
import com.plugin.PLUGINS_ROUTE
import com.plugin.PluginId
import com.plugin.PluginState
import com.plugin.PluginsPage
import com.plugin.featurePlugins
import com.plugin.library.EnhancedVideoListPage
import com.plugin.queue.EnhancedDownloadPageV2
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private val TopDestinations =
    listOf(Route.HOME, Route.TASK_LIST, Route.SETTINGS_PAGE, Route.DOWNLOADS)

/**
 * 플러그인 확장 진입점 컴포저블입니다.
 * 원본 AppEntry.kt를 100% 원본 유지하도록 분리된 호스트 컨테이너입니다.
 */
@Composable
fun PluginAppEntry(dialogViewModel: com.plugin.runtime.PluginDownloadDialogViewModel) {
    val navController = rememberNavController()
    val view = LocalView.current
    val windowWidth = LocalWindowWidthState.current
    val sheetState by dialogViewModel.sheetStateFlow.collectAsStateWithLifecycle()
    val sheetValue by dialogViewModel.sheetValueFlow.collectAsStateWithLifecycle()
    val cookiesViewModel: CookiesViewModel = koinViewModel()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val versionReport = App.packageInfo.versionName.toString()
    val appName = stringResource(R.string.app_name)
    val scope = rememberCoroutineScope()

    val onNavigateBack: () -> Unit = {
        with(navController) {
            if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) {
                popBackStack()
            }
        }
    }

    LaunchedEffect(sheetState, sheetValue) {
        // 구성 변경 중 직접 내비게이션을 실행하지 않고 설정 화면이 열릴 때 한 번 홈으로 복귀합니다.
        if (sheetState is DownloadDialogViewModel.SheetState.Configure &&
            sheetValue == DownloadDialogViewModel.SheetValue.Expanded &&
            navController.currentDestination?.route != Route.HOME) {
            navController.popBackStack(route = Route.HOME, inclusive = false, saveState = true)
        }
    }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    var currentTopDestination by rememberSaveable { mutableStateOf(currentRoute) }

    LaunchedEffect(currentRoute) {
        if (currentRoute in TopDestinations) {
            currentTopDestination = currentRoute
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PluginNavigationDrawer(
            windowWidth = windowWidth,
            drawerState = drawerState,
            currentRoute = currentRoute,
            currentTopDestination = currentTopDestination,
            showQuickSettings = true,
            gesturesEnabled = currentRoute == Route.HOME,
            onDismissRequest = { scope.launch { drawerState.close() } },
            onNavigateToRoute = {
                if (currentRoute != it) {
                    navController.navigate(it) {
                        launchSingleTop = true
                        popUpTo(route = Route.HOME)
                    }
                }
            },
            footer = {
                Text(
                    "$appName · $versionReport",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            },
        ) {
            NavHost(
                modifier = Modifier.align(Alignment.Center),
                navController = navController,
                startDestination = Route.HOME,
            ) {
                animatedComposable(Route.HOME) {
                    if (PluginState.isEnabled(PluginId.QUEUE)) {
                        EnhancedDownloadPageV2(
                            onMenuOpen = {
                                view.slightHapticFeedback()
                                scope.launch { drawerState.open() }
                            },
                            dialogViewModel = dialogViewModel,
                        )
                    } else {
                        BaselineDownloadPage(
                            dialogViewModel = dialogViewModel,
                            onMenuOpen = {
                                view.slightHapticFeedback()
                                scope.launch { drawerState.open() }
                            },
                        )
                    }
                }
                animatedComposable(PLUGINS_ROUTE) {
                    PluginsPage(onNavigateBack) { navController.navigate(it) { launchSingleTop = true } }
                }
                featurePlugins.forEach { plugin ->
                    animatedComposable(plugin.route) {
                        if (!PluginState.isEnabled(plugin.id)) {
                            PluginsPage(onNavigateBack) { navController.navigate(it) { launchSingleTop = true } }
                        } else {
                            plugin.Content(onNavigateBack) { action ->
                                navController.navigate(Route.HOME) { popUpTo(Route.HOME); launchSingleTop = true }
                                dialogViewModel.postAction(DownloadDialogViewModel.Action.ShowSheet())
                                dialogViewModel.postAction(action)
                            }
                        }
                    }
                }
                animatedComposable(Route.DOWNLOADS) {
                    if (PluginState.isEnabled(PluginId.LIBRARY)) {
                        EnhancedVideoListPage { onNavigateBack() }
                    } else {
                        VideoListPage { onNavigateBack() }
                    }
                }
                animatedComposableVariant(Route.TASK_LIST) {
                    TaskListPage(
                        onNavigateBack = onNavigateBack,
                        onNavigateToDetail = { navController.navigate(Route.TASK_LOG id it) },
                    )
                }
                slideInVerticallyComposable(
                    Route.TASK_LOG arg Route.TASK_HASHCODE,
                    arguments = listOf(navArgument(Route.TASK_HASHCODE) { type = NavType.IntType }),
                ) {
                    TaskLogPage(
                        onNavigateBack = onNavigateBack,
                        taskHashCode = it.arguments?.getInt(Route.TASK_HASHCODE) ?: -1,
                    )
                }
                pluginSettingsGraph(
                    onNavigateBack = onNavigateBack,
                    onNavigateTo = { navController.navigate(it) },
                    cookiesViewModel = cookiesViewModel,
                )
            }
        }

        PluginDownloadDialogHost(dialogViewModel)

        AppUpdater()
        YtdlpUpdater()
    }
}

/** 플러그인 설정 네비게이션 서브그래프입니다. */
fun NavGraphBuilder.pluginSettingsGraph(
    onNavigateBack: () -> Unit,
    onNavigateTo: (route: String) -> Unit,
    cookiesViewModel: CookiesViewModel,
) {
    navigation(startDestination = Route.SETTINGS_PAGE, route = Route.SETTINGS) {
        animatedComposable(Route.SETTINGS_PAGE) {
            PluginSettingsPage(onNavigateBack = onNavigateBack, onNavigateTo = onNavigateTo)
        }
        animatedComposable(Route.GENERAL_DOWNLOAD_PREFERENCES) {
            GeneralDownloadPreferences(onNavigateBack = { onNavigateBack() }) {
                onNavigateTo(Route.TEMPLATE)
            }
        }
        animatedComposable(Route.DOWNLOAD_FORMAT) {
            DownloadFormatPreferences(onNavigateBack = onNavigateBack) {
                onNavigateTo(Route.SUBTITLE_PREFERENCES)
            }
        }
        animatedComposable(Route.SUBTITLE_PREFERENCES) { SubtitlePreference { onNavigateBack() } }
        animatedComposable(Route.ABOUT) {
            AboutPage(
                onNavigateBack = onNavigateBack,
                onNavigateToCreditsPage = { onNavigateTo(Route.CREDITS) },
                onNavigateToUpdatePage = { onNavigateTo(Route.AUTO_UPDATE) },
                onNavigateToDonatePage = { onNavigateTo(Route.DONATE) },
            )
        }
        animatedComposable(Route.DONATE) { SponsorsPage(onNavigateBack) }
        animatedComposable(Route.CREDITS) { CreditsPage(onNavigateBack) }
        animatedComposable(Route.AUTO_UPDATE) { UpdatePage(onNavigateBack) }
        animatedComposable(Route.APPEARANCE) {
            AppearancePreferences(onNavigateBack = onNavigateBack, onNavigateTo = onNavigateTo)
        }
        animatedComposable(Route.INTERACTION) { InteractionPreferencePage(onBack = onNavigateBack) }
        animatedComposable(Route.LANGUAGES) { LanguagePage { onNavigateBack() } }
        animatedComposable(Route.DOWNLOAD_DIRECTORY) {
            DownloadDirectoryPreferences { onNavigateBack() }
        }
        animatedComposable(Route.TEMPLATE) {
            TemplateListPage(onNavigateBack = onNavigateBack) {
                onNavigateTo(Route.TEMPLATE_EDIT id it)
            }
        }
        animatedComposable(
            Route.TEMPLATE_EDIT arg Route.TEMPLATE_ID,
            arguments = listOf(navArgument(Route.TEMPLATE_ID) { type = NavType.IntType }),
        ) {
            TemplateEditPage(onNavigateBack, it.arguments?.getInt(Route.TEMPLATE_ID) ?: -1)
        }
        animatedComposable(Route.DARK_THEME) { DarkThemePreferences { onNavigateBack() } }
        animatedComposable(Route.NETWORK_PREFERENCES) {
            NetworkPreferences(
                navigateToCookieProfilePage = { onNavigateTo(Route.COOKIE_PROFILE) }
            ) {
                onNavigateBack()
            }
        }
        animatedComposable(Route.COOKIE_PROFILE) {
            CookieProfilePage(
                cookiesViewModel = cookiesViewModel,
                navigateToCookieGeneratorPage = { onNavigateTo(Route.COOKIE_GENERATOR_WEBVIEW) },
            ) {
                onNavigateBack()
            }
        }
        animatedComposable(Route.COOKIE_GENERATOR_WEBVIEW) {
            WebViewPage(cookiesViewModel = cookiesViewModel) {
                onNavigateBack()
                CookieManager.getInstance().flush()
            }
        }
        animatedComposable(Route.TROUBLESHOOTING) {
            TroubleShootingPage(onNavigateTo = onNavigateTo, onBack = onNavigateBack)
        }
    }
}
