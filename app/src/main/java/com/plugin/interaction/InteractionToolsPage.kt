package com.plugin.interaction

import com.plugin.compatibility.PluginPreferences

import com.plugin.compatibility.PluginPreferences.QUICK_COOKIE_PROFILE
import com.plugin.compatibility.PluginPreferences.getMenuFavorites
import com.plugin.compatibility.PluginPreferences.updateMenuFavorites

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.Route
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.plugin.FeaturePlugin
import com.plugin.PluginId

/** 메뉴 즐겨찾기와 빠른 쿠키 선택을 플러그인 하위 설정으로 모읍니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InteractionToolsPage(onBack: () -> Unit) {
    var favorites by remember { mutableStateOf(PluginPreferences.getMenuFavorites()) }
    var quick by remember { mutableIntStateOf(QUICK_COOKIE_PROFILE.getInt(-1)) }
    val profiles by DatabaseUtil.getCookiesFlow().collectAsState(emptyList())
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
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.plugin_interaction)) }, navigationIcon = { BackButton(onBack) })
    }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.loc_menu_favorites))
            shortcuts.forEach { (route, title) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(route in favorites, onCheckedChange = { checked ->
                        favorites = if (checked) favorites + route else favorites - route
                        PluginPreferences.updateMenuFavorites(favorites)
                    })
                    Text(stringResource(title))
                }
            }
            Text(stringResource(R.string.plugin_cookie_profile))
            Text(stringResource(R.string.plugin_cookie_profile_hint))
            profiles.forEach { profile ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(quick == profile.id, onCheckedChange = { checked ->
                        quick = if (checked) profile.id else -1
                        QUICK_COOKIE_PROFILE.updateInt(quick)
                        PluginPreferences.EXPLICIT_COOKIE_PROFILE.updateInt(Int.MIN_VALUE)
                    })
                    Text(profile.url)
                }
            }
        }
    }
}

/** 편의 기능의 세부 설정을 내장 플러그인 메뉴에 등록합니다. */
object InteractionPlugin : FeaturePlugin {
    override val id = PluginId.INTERACTION
    override val route = "plugin_interaction"
    override val titleResource = R.string.plugin_interaction
    override val icon get() = Icons.Rounded.SettingsApplications

    /** 저장된 메뉴·쿠키 설정을 표시합니다. */
    @Composable
    override fun Content(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        InteractionToolsPage(onBack)
    }
}
