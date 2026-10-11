package com.plugin.listdownload

import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.compatibility.CookieOptions
import com.plugin.compatibility.MediaDownloadOptions
import com.plugin.compatibility.copyPluginOptions
import com.plugin.compatibility.mediaSelection
import com.junkfood.seal.ui.page.downloadv2.configure.InstagramSelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadInstagramAction
import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.plugin.compatibility.MediaRules

/** 계정 또는 혼합 게시물에서 이미지·영상별로 파일을 선택합니다. */
@Composable
fun ListSelectionPage(
    state: InstagramSelectionState,
    onAction: (Any) -> Unit,
) {
    var preferences by remember(state) { com.plugin.compatibility.pluginPreferencesState(if (state.items.all { it.image }) state.preferences.copyPluginOptions(mediaSelection = 2) else state.preferences) }
    val selected =
        remember(state) { mutableStateListOf<String>().apply { addAll(state.items.map { it.id }) } }
    var postTab by remember(state) { mutableIntStateOf(0) }
    val visible = state.items.filter { MediaRules.accepts(preferences.mediaSelection, it.image) &&
        (!preferences.extractAudio || !it.image) &&
        (postTab == 0 || (postTab == 1 && it.reel) || (postTab == 2 && !it.reel)) }
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.loc_instagram_media_count, state.items.count { !it.image }, state.items.count { it.image }),
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.accountList) {
                listOf(0 to R.string.all, 1 to R.string.loc_reels, 2 to R.string.loc_posts).forEach { (tab, label) ->
                    FilterChip(postTab == tab, onClick = { postTab = tab }, label = { Text(stringResource(label)) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to R.string.all, 1 to R.string.loc_media_videos, 2 to R.string.loc_media_images).forEach { (value, label) ->
                FilterChip(preferences.mediaSelection == value,
                    onClick = { preferences = preferences.copyPluginOptions(mediaSelection = value) },
                    label = { Text(stringResource(label)) })
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = {
                    if (visible.all { it.id in selected })
                        selected.removeAll(visible.map { it.id }.toSet())
                    else visible.forEach { if (it.id !in selected) selected.add(it.id) }
                }
            ) {
                Text(stringResource(R.string.loc_select_all_toggle))
            }
            Text(stringResource(R.string.loc_selected_count, visible.count { it.id in selected }), Modifier.padding(top = 12.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(visible, key = { it.id }) { media ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Checkbox(
                        media.id in selected,
                        onCheckedChange = {
                            if (it) selected.add(media.id) else selected.remove(media.id)
                        },
                    )
                    AsyncImage(
                        model =
                            media.thumbnail.takeIf {
                                ListMediaUtil.safeMediaUrl(it)
                            },
                        contentDescription = if (media.image) stringResource(R.string.loc_image_preview) else stringResource(R.string.loc_video_preview),
                        modifier = Modifier.size(64.dp).padding(4.dp),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(stringResource(R.string.loc_media_page, state.items.indexOf(media) + 1,
                            stringResource(if (media.image) R.string.loc_media_images else R.string.loc_media_videos)))
                        Text(if (media.image) stringResource(R.string.loc_image_author, media.author) else stringResource(R.string.loc_video_author, media.author))
                        Text(media.title, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { onAction(DownloadDialogViewModel.Action.HideSheet) }) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                enabled = visible.any { it.id in selected },
                onClick = {
                    onAction(
                        DownloadInstagramAction(
                            visible.filter { it.id in selected },
                            preferences,
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.loc_download_selected))
            }
        }
    }
}
