package com.plugin.sharemenu

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.PreferenceSwitch
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import com.plugin.compatibility.ShareMenuSettings

/** Android 공유 메뉴에 표시할 앱 기능을 선택하는 파사드입니다. */
object ShareMenuPlugin : FeaturePlugin() {
    override val id = PluginId.SHARE_MENU
    override val route = "share_menu"
    override val titleResource = R.string.plugin_share_menu
    override val icon = Icons.Outlined.Share

    /** 한 개 이상 유지하며 변경 즉시 공유 전용 별칭에 적용합니다. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        val context = LocalContext.current
        var selected by remember { mutableIntStateOf(ShareMenuSettings.selected()) }
        var error by remember { mutableStateOf("") }
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(titleResource)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.plugin_share_menu_desc))
                listOf(ShareMenuSettings.QUICK to R.string.title_activity_share, ShareMenuSettings.MAIN to R.string.app_name).forEach { (bit, title) ->
                    val checked = selected and bit != 0
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        PreferenceSwitch(title = stringResource(title), description = "", icon = icon,
                            isChecked = checked, enabled = !checked || selected != bit, onClick = {
                                val next = selected xor bit
                                runCatching { ShareMenuSettings.save(context, next) }
                                    .onSuccess { selected = next; error = "" }
                                    .onFailure { error = it.message.orEmpty() }
                            })
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
