package com.plugin.runtime

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import com.plugin.PLUGINS_ROUTE
import kotlinx.coroutines.launch

/** 원본 탐색 메뉴에 관리 진입점과 활성 기능의 메뉴만 연결합니다. */
@Composable
fun PluginNavigationItems(currentRoute: String?, onNavigate: (String) -> Unit,
    onDismiss: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    NavigationDrawerItem(label = { Text(stringResource(R.string.plugin_manage)) },
        icon = { Icon(Icons.Outlined.Settings, null) }, selected = currentRoute == PLUGINS_ROUTE,
        onClick = { scope.launch { onDismiss(); onNavigate(PLUGINS_ROUTE) } })
    com.plugin.PluginFactory.enabled().forEach { plugin ->
        NavigationDrawerItem(label = { Text(stringResource(plugin.titleResource)) },
            icon = { Icon(plugin.icon, null) }, selected = currentRoute == plugin.route,
            onClick = { scope.launch { onDismiss(); onNavigate(plugin.route) } })
    }
}
