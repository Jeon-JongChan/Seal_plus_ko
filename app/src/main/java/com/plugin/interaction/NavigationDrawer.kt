package com.plugin.interaction

import com.plugin.compatibility.PluginPreferences

import com.junkfood.seal.ui.page.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cookie
import com.plugin.featurePlugins
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.junkfood.seal.util.PreferenceUtil
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.LocalWindowWidthState
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.page.downloadv2.DownloadPageImplV2
import kotlinx.coroutines.launch

@Composable
fun NavigationDrawer(
    modifier: Modifier = Modifier,
    drawerState: DrawerState,
    windowWidth: WindowWidthSizeClass = LocalWindowWidthState.current,
    currentRoute: String? = null,
    currentTopDestination: String? = null,
    showQuickSettings: Boolean = true,
    onNavigateToRoute: (String) -> Unit,
    onDismissRequest: suspend () -> Unit,
    gesturesEnabled: Boolean = true,
    footer: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()

    when (windowWidth) {
        WindowWidthSizeClass.Compact,
        WindowWidthSizeClass.Medium -> {
            ModalNavigationDrawer(
                gesturesEnabled = gesturesEnabled,
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(drawerState = drawerState, modifier = modifier.width(360.dp)) {
                        InteractionNavigationDrawerContent(
                            modifier = Modifier,
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
        WindowWidthSizeClass.Expanded -> {
            ModalNavigationDrawer(
                gesturesEnabled = drawerState.isOpen,
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(drawerState = drawerState, modifier = modifier.width(360.dp)) {
                        InteractionNavigationDrawerContent(
                            modifier = Modifier,
                            currentRoute = currentRoute,
                            showQuickSettings = showQuickSettings,
                            onNavigateToRoute = onNavigateToRoute,
                            onDismissRequest = onDismissRequest,
                            footer = footer,
                        )
                    }
                },
            ) {
                Row {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.zIndex(1f),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxHeight().systemBarsPadding().width(92.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(8.dp))
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.align(Alignment.CenterHorizontally),
                            ) {
                                Icon(Icons.Outlined.Menu, null)
                            }
                            Spacer(Modifier.weight(1f))
                            NavigationRailContent(
                                modifier = Modifier,
                                currentTopDestination = currentTopDestination,
                                onNavigateToRoute = onNavigateToRoute,
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    content()
                }
            }
        }
    }
}


@Composable
fun InteractionNavigationDrawerContent(
    modifier: Modifier = Modifier,
    currentRoute: String? = null,
    showQuickSettings: Boolean = true,
    onNavigateToRoute: (String) -> Unit,
    onDismissRequest: suspend () -> Unit,
    footer: @Composable (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val favorites = PluginPreferences.getMenuFavorites()
    val shortcuts = listOf(
        Route.GENERAL_DOWNLOAD_PREFERENCES to R.string.general_settings,
        Route.DOWNLOAD_DIRECTORY to R.string.download_directory,
        Route.DOWNLOAD_FORMAT to R.string.format,
        Route.NETWORK_PREFERENCES to R.string.network,
        Route.COOKIE_PROFILE to R.string.cookies,
        Route.TEMPLATE to R.string.custom_command,
        Route.APPEARANCE to R.string.look_and_feel,
        Route.INTERACTION to R.string.interface_and_interaction,
        Route.TROUBLESHOOTING to R.string.trouble_shooting,
        Route.ABOUT to R.string.about,
    )
    Column(
        modifier =
            modifier
                .padding(horizontal = 12.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .systemBarsPadding()
    ) {
        Spacer(Modifier.height(24.dp))
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.download_queue)) },
                icon = { Icon(Icons.Filled.Download, null) },
                onClick = {
                    scope
                        .launch { onDismissRequest() }
                        .invokeOnCompletion { onNavigateToRoute(Route.HOME) }
                },
                selected = currentRoute == Route.HOME,
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.downloads_history)) },
                icon = { Icon(Icons.Outlined.Subscriptions, null) },
                onClick = {
                    scope
                        .launch { onDismissRequest() }
                        .invokeOnCompletion { onNavigateToRoute(Route.DOWNLOADS) }
                },
                selected = currentRoute == Route.DOWNLOADS,
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.custom_command)) },
                icon = { Icon(Icons.Outlined.Terminal, null) },
                onClick = {
                    scope
                        .launch { onDismissRequest() }
                        .invokeOnCompletion { onNavigateToRoute(Route.TASK_LIST) }
                },
                selected = currentRoute == Route.TASK_LIST,
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.settings)) },
                icon = { Icon(Icons.Outlined.Settings, null) },
                onClick = {
                    scope
                        .launch { onDismissRequest() }
                        .invokeOnCompletion { onNavigateToRoute(Route.SETTINGS) }
                },
                selected = currentRoute == Route.SETTINGS_PAGE,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(stringResource(R.string.loc_plugins), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.primary)
            NavigationDrawerItem(label = { Text(stringResource(R.string.plugin_manage)) },
                icon = { Icon(Icons.Outlined.Settings, null) }, selected = currentRoute == com.plugin.PLUGINS_ROUTE,
                onClick = { scope.launch { onDismissRequest() }.invokeOnCompletion { onNavigateToRoute(com.plugin.PLUGINS_ROUTE) } })
            com.plugin.PluginFactory.enabled().forEach { plugin ->
                NavigationDrawerItem(label = { Text(stringResource(plugin.titleResource)) },
                    icon = { Icon(plugin.icon, null) },
                    selected = currentRoute == plugin.route,
                    onClick = { scope.launch { onDismissRequest() }.invokeOnCompletion { onNavigateToRoute(plugin.route) } })
            }
            if (showQuickSettings && com.plugin.PluginState.isEnabled(com.plugin.PluginId.INTERACTION)) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                TextButton(onClick = { scope.launch { onDismissRequest() }.invokeOnCompletion { onNavigateToRoute("plugin_interaction") } }) { Text(stringResource(R.string.loc_edit_favorites)) }
                if (favorites.isEmpty()) Text(stringResource(R.string.loc_favorites_hint), Modifier.padding(16.dp))
                shortcuts.filter { it.first in favorites }.forEach { (route, title) ->
                    NavigationDrawerItem(label = { Text(stringResource(title)) },
                        selected = currentRoute == route,
                        onClick = { scope.launch { onDismissRequest() }.invokeOnCompletion { onNavigateToRoute(route) } })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        footer?.invoke()
    }
}

@Composable
fun NavigationRailItemVariant(
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit),
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.large)
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else Color.Transparent
                )
                .selectable(selected = selected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides
                if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            icon()
        }
    }
}

@Composable
fun NavigationRailContent(
    modifier: Modifier = Modifier,
    currentTopDestination: String? = null,
    onNavigateToRoute: (String) -> Unit,
) {
    Column(
        modifier = modifier.selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val scope = rememberCoroutineScope()
        NavigationRailItemVariant(
            icon = {
                Icon(
                    if (currentTopDestination == Route.HOME) Icons.Filled.Download
                    else Icons.Outlined.Download,
                    stringResource(R.string.download_queue),
                )
            },
            modifier = Modifier,
            selected = currentTopDestination == Route.HOME,
            onClick = { onNavigateToRoute(Route.HOME) },
        )

        NavigationRailItemVariant(
            icon = {
                Icon(
                    if (currentTopDestination == Route.DOWNLOADS) Icons.Filled.Subscriptions
                    else Icons.Outlined.Subscriptions,
                    stringResource(R.string.downloads_history),
                )
            },
            modifier = Modifier,
            selected = currentTopDestination == Route.DOWNLOADS,
            onClick = { onNavigateToRoute(Route.DOWNLOADS) },
        )

        NavigationRailItemVariant(
            icon = {
                Icon(
                    if (currentTopDestination == Route.TASK_LIST) Icons.Filled.Terminal
                    else Icons.Outlined.Terminal,
                    stringResource(R.string.custom_command),
                )
            },
            modifier = Modifier,
            selected = currentTopDestination == Route.TASK_LIST,
            onClick = { onNavigateToRoute(Route.TASK_LIST) },
        )

        NavigationRailItemVariant(
            icon = {
                Icon(
                    if (currentTopDestination == Route.SETTINGS_PAGE) Icons.Filled.Settings
                    else Icons.Outlined.Settings,
                    stringResource(R.string.settings),
                )
            },
            modifier = Modifier,
            selected = currentTopDestination == Route.SETTINGS_PAGE,
            onClick = { onNavigateToRoute(Route.SETTINGS_PAGE) },
        )
    }
}

@Preview(device = "spec:width=673dp,height=841dp")
@Preview(device = "spec:width=1280dp,height=800dp,dpi=240")
@Composable
private fun ExpandedPreview() {
    val widthDp = LocalConfiguration.current.screenWidthDp
    var currentRoute = remember { mutableStateOf(Route.HOME) }

    CompositionLocalProvider(
        LocalWindowWidthState provides
            if (widthDp > 480) WindowWidthSizeClass.Expanded
            else if (widthDp > 360) WindowWidthSizeClass.Medium else WindowWidthSizeClass.Compact
    ) {
        Row {
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            NavigationDrawer(
                currentRoute = currentRoute.value,
                currentTopDestination = currentRoute.value,
                drawerState = drawerState,
                onNavigateToRoute = { currentRoute.value = it },
                onDismissRequest = {},
            ) {
                DownloadPageImplV2(taskDownloadStateMap = remember { mutableStateMapOf() }) { _, _
                    ->
                }
            }
        }
    }
}
