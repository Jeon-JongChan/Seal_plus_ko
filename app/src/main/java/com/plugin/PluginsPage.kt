package com.plugin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.PreferenceSwitch
import com.junkfood.seal.ui.component.SettingItem

const val PLUGINS_ROUTE = "plugins"

/** 플러그인별 설명과 활성화 스위치·하위 설정 진입점을 한곳에 제공합니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsPage(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    var closeTemporary by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (closeTemporary) com.plugin.temporary.TemporaryExitDialog(onDismiss = { closeTemporary = false }, onFinished = {
        PluginState.setEnabled(PluginId.HIDDEN, false)
        closeTemporary = false
    })
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.loc_plugins)) }, navigationIcon = { BackButton(onBack) })
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(stringResource(R.string.plugin_behavior),
                        modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    PreferenceSwitch(title = stringResource(R.string.plugin_compatibility),
                        description = stringResource(R.string.plugin_compatibility_desc),
                        icon = Icons.Outlined.Lock,
                        isChecked = com.plugin.compatibility.CompatibilityPlugin.enabled, enabled = false)
                }
            }
            PluginId.entries.filter { it.owner == it }.groupBy { it.category }.forEach { (category, ids) ->
            item(key = "category:$category") { Text(category, style = MaterialTheme.typography.titleMedium) }
            items(ids, key = { it.name }) { id ->
                val (title, description) = when (id) {
                    PluginId.SOCIAL -> R.string.plugin_social to R.string.plugin_social_desc
                    PluginId.MEDIA -> R.string.loc_media_tools to R.string.plugin_media_desc
                    PluginId.LIBRARY -> R.string.plugin_library to R.string.plugin_library_desc
                    PluginId.QUEUE -> R.string.plugin_queue to R.string.plugin_queue_desc
                    PluginId.INTERACTION -> R.string.plugin_interaction to R.string.plugin_interaction_desc
                    PluginId.IMAGE_AI -> R.string.plugin_image_ai to R.string.plugin_image_ai_desc
                    PluginId.VIDEO_AI -> R.string.plugin_video_ai to R.string.plugin_video_ai_desc
                    PluginId.SHARE_MENU -> R.string.plugin_share_menu to R.string.plugin_share_menu_desc
                    PluginId.HIDDEN -> R.string.plugin_temporary to R.string.plugin_temporary_desc
                    PluginId.ACCOUNTS -> R.string.loc_frequent_accounts to R.string.loc_frequent_accounts_desc
                    PluginId.FOLDERS -> R.string.plugin_download_folders to R.string.plugin_download_folders_desc
                    PluginId.FACE_SORT -> R.string.plugin_face_sort to R.string.plugin_face_sort_desc
                    PluginId.DUPLICATES -> R.string.plugin_duplicates to R.string.plugin_duplicates_desc
                    PluginId.PERSON_AI -> R.string.plugin_person_ai to R.string.plugin_person_ai_desc
                    PluginId.POST_INFO -> R.string.loc_post_info_plugin to R.string.loc_post_info_plugin_desc
                }
                val enabled = PluginState.isEnabled(id)
                val plugin = PluginFactory.create(id)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    PreferenceSwitch(title = stringResource(title), description = stringResource(description),
                        icon = plugin?.icon ?: Icons.Outlined.Checklist,
                        isChecked = enabled, onClick = {
                            if (id == PluginId.INTERACTION && enabled && com.plugin.compatibility.TemporarySession.root != null) closeTemporary = true
                            else PluginState.setEnabled(id, !enabled)
                        })
                    if (enabled && plugin != null) {
                        HorizontalDivider()
                        SettingItem(title = stringResource(R.string.plugin_open), description = "", icon = plugin.icon) {
                            onNavigate(plugin.route)
                        }
                    }
                }
            }
            }
        }
    }
}
