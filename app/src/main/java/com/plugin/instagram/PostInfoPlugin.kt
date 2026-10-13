package com.plugin.instagram

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.PreferenceSwitch
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import com.plugin.PluginRoutes
import com.plugin.compatibility.SocialPostMetadataPreferences

/**
 * 계정주 프로필, 계정명, 해당 게시글의 텍스트와 해시태그를 수집하여 대기열이나 목록에서 보여주는 플러그인입니다.
 * - 옵션 설정에서 표시할 항목(프로필, 계정명, 텍스트, 해시태그)을 개별 선택할 수 있습니다. (기본값: 전체 활성화)
 */
object PostInfoPlugin : FeaturePlugin() {
    override val id = PluginId.POST_INFO
    override val route = PluginRoutes.POST_INFO
    override val titleResource = R.string.loc_post_info_plugin
    override val icon = Icons.Outlined.Article

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    /** 프로필·계정명·본문·해시태그 표시 여부를 개별 설정합니다. */
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        var showProfile by remember { mutableStateOf(SocialPostMetadataPreferences.showProfile) }
        var showAuthor by remember { mutableStateOf(SocialPostMetadataPreferences.showAuthor) }
        var showText by remember { mutableStateOf(SocialPostMetadataPreferences.showText) }
        var showHashtags by remember { mutableStateOf(SocialPostMetadataPreferences.showHashtags) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.loc_post_info_plugin)) },
                    navigationIcon = { BackButton(onBack) }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = stringResource(R.string.loc_post_info_plugin_desc),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        PreferenceSwitch(
                            title = stringResource(R.string.loc_post_info_show_profile),
                            description = "",
                            icon = Icons.Outlined.AccountCircle,
                            isChecked = showProfile,
                            onClick = {
                                val next = !showProfile
                                showProfile = next
                                SocialPostMetadataPreferences.showProfile = next
                            }
                        )
                        HorizontalDivider()
                        PreferenceSwitch(
                            title = stringResource(R.string.loc_post_info_show_author),
                            description = "",
                            icon = Icons.Outlined.Badge,
                            isChecked = showAuthor,
                            onClick = {
                                val next = !showAuthor
                                showAuthor = next
                                SocialPostMetadataPreferences.showAuthor = next
                            }
                        )
                        HorizontalDivider()
                        PreferenceSwitch(
                            title = stringResource(R.string.loc_post_info_show_text),
                            description = "",
                            icon = Icons.Outlined.Article,
                            isChecked = showText,
                            onClick = {
                                val next = !showText
                                showText = next
                                SocialPostMetadataPreferences.showText = next
                            }
                        )
                        HorizontalDivider()
                        PreferenceSwitch(
                            title = stringResource(R.string.loc_post_info_show_hashtags),
                            description = "",
                            icon = Icons.Outlined.Tag,
                            isChecked = showHashtags,
                            onClick = {
                                val next = !showHashtags
                                showHashtags = next
                                SocialPostMetadataPreferences.showHashtags = next
                            }
                        )
                    }
                }
            }
        }
    }
}

