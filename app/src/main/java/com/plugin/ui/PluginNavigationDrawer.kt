package com.plugin.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.Route
import com.plugin.PluginId
import com.plugin.PluginState
import com.plugin.interaction.InteractionNavigationDrawerContent
import com.plugin.runtime.PluginNavigationItems
import kotlinx.coroutines.launch

/**
 * 플러그인 확장 네비게이션 드로어입니다.
 * 원본 NavigationDrawer.kt를 변경하지 않고 플러그인 전용 메뉴 및 INTERACTION 플러그인을 지원합니다.
 */
@Composable
fun PluginNavigationDrawer(
    modifier: Modifier = Modifier,
    windowWidth: WindowWidthSizeClass,
    drawerState: DrawerState,
    gesturesEnabled: Boolean = true,
    showQuickSettings: Boolean = true,
    currentRoute: String?,
    currentTopDestination: String?,
    onNavigateToRoute: (String) -> Unit,
    onDismissRequest: () -> Unit,
    footer: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val isExpanded = windowWidth == WindowWidthSizeClass.Expanded

    if (isExpanded) {
        PermanentNavigationDrawer(
            modifier = modifier,
            drawerContent = {
                PermanentDrawerSheet(modifier = Modifier.width(360.dp)) {
                    PluginNavigationDrawerSheetContent(
                        currentRoute = currentRoute,
                        showQuickSettings = showQuickSettings,
                        onNavigateToRoute = onNavigateToRoute,
                        onDismissRequest = onDismissRequest,
                        footer = footer,
                    )
                }
            },
            content = content,
        )
    } else {
        ModalNavigationDrawer(
            modifier = modifier,
            drawerState = drawerState,
            gesturesEnabled = gesturesEnabled && drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet {
                    PluginNavigationDrawerSheetContent(
                        currentRoute = currentRoute,
                        showQuickSettings = showQuickSettings,
                        onNavigateToRoute = onNavigateToRoute,
                        onDismissRequest = onDismissRequest,
                        footer = footer,
                    )
                }
            },
            content = content,
        )
    }
}

/** 드로어 내부 시트 콘텐츠입니다. */
@Composable
fun PluginNavigationDrawerSheetContent(
    modifier: Modifier = Modifier,
    currentRoute: String?,
    showQuickSettings: Boolean = false,
    onNavigateToRoute: (String) -> Unit,
    onDismissRequest: () -> Unit,
    footer: @Composable (() -> Unit)? = null,
) {
    if (PluginState.isEnabled(PluginId.INTERACTION)) {
        InteractionNavigationDrawerContent(
            modifier = modifier,
            currentRoute = currentRoute,
            showQuickSettings = showQuickSettings,
            onNavigateToRoute = onNavigateToRoute,
            onDismissRequest = { onDismissRequest() },
            footer = footer,
        )
        return
    }

    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState())
            .systemBarsPadding().padding(horizontal = 12.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            PluginNavigationItems(currentRoute, onNavigateToRoute) { onDismissRequest() }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.download_queue)) },
                icon = { Icon(Icons.Filled.Download, null) },
                onClick = {
                    scope.launch { onDismissRequest() }
                    onNavigateToRoute(Route.HOME)
                },
                selected = currentRoute == Route.HOME,
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.download_history)) },
                icon = { Icon(Icons.Outlined.Folder, null) },
                onClick = {
                    scope.launch { onDismissRequest() }
                    onNavigateToRoute(Route.DOWNLOADS)
                },
                selected = currentRoute == Route.DOWNLOADS,
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.custom_command)) },
                icon = { Icon(Icons.Outlined.Terminal, null) },
                onClick = {
                    scope.launch { onDismissRequest() }
                    onNavigateToRoute(Route.TASK_LIST)
                },
                selected = currentRoute == Route.TASK_LIST,
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.settings)) },
                icon = { Icon(Icons.Outlined.Settings, null) },
                onClick = {
                    scope.launch { onDismissRequest() }
                    onNavigateToRoute(Route.SETTINGS)
                },
                selected = currentRoute == Route.SETTINGS || currentRoute == Route.SETTINGS_PAGE,
            )

        }
        Spacer(Modifier.height(24.dp))
        footer?.let {
            HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
            it()
        }
        Spacer(Modifier.height(12.dp))
    }
}

