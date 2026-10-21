package com.plugin.instagram

import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.compatibility.copyPluginOptions
import com.plugin.compatibility.mediaSelection
import com.junkfood.seal.ui.page.downloadv2.configure.InstagramSelectionState
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadInstagramAction
import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.plugin.compatibility.MediaRules

/** 계정 또는 혼합 게시물에서 이미지·영상별로 파일을 선택합니다. */
@Composable
fun InstagramSelectionPage(
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
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
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
        Box(Modifier.padding(horizontal = 20.dp)) { com.plugin.ui.DownloadFolderSelector(preferences) { preferences = it } }
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
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(vertical = 8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(visible, key = { it.id }) { media ->
                val isSelected = media.id in selected
                val pageNum = state.items.indexOf(media) + 1
                OutlinedCard(
                    modifier = Modifier
                        .fillParentMaxWidth(0.85f)
                        .height(280.dp)
                        .clickable {
                            if (isSelected) selected.remove(media.id) else selected.add(media.id)
                        },
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = SolidColor(
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        width = if (isSelected) 2.5.dp else 1.dp
                    )
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = media.thumbnail.takeIf { com.plugin.instagram.InstagramUtil.safeMediaUrl(it) } ?: media.url,
                            contentDescription = if (media.image) stringResource(R.string.loc_image_preview) else stringResource(R.string.loc_video_preview),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                        // 상단 체크박스 (클릭 시 선택 토글)
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (it) selected.add(media.id) else selected.remove(media.id)
                                },
                            )
                        }
                        // 하단 오버레이: 몇페이지 및 미디어 종류 표시 (불필요한 반복 텍스트 배제)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                                    )
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.loc_media_page,
                                    pageNum,
                                    stringResource(if (media.image) R.string.loc_media_images else R.string.loc_media_videos)
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
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
