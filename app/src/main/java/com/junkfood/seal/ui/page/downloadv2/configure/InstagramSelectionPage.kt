package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.junkfood.seal.util.MediaRules

/** 계정 또는 혼합 게시물에서 이미지·영상별로 파일을 선택합니다. */
@Composable
fun InstagramSelectionPage(
    state: DownloadDialogViewModel.SheetState.InstagramSelection,
    onAction: (DownloadDialogViewModel.Action) -> Unit,
) {
    var preferences by remember(state) { mutableStateOf(state.preferences) }
    val selected =
        remember(state) { mutableStateListOf<String>().apply { addAll(state.items.map { it.id }) } }
    val visible = state.items.filter { MediaRules.accepts(preferences.mediaSelection, it.image) }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Text(
            "인스타그램 · 영상 ${state.items.count { !it.image }} / 이미지 ${state.items.count { it.image }}",
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        MediaDownloadOptions(preferences, onUpdate = { preferences = it }, instagram = true)
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
                Text("전체 선택/해제")
            }
            Text("${visible.count { it.id in selected }}개 선택", Modifier.padding(top = 12.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
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
                                com.junkfood.seal.util.InstagramUtil.safeMediaUrl(it)
                            },
                        contentDescription = if (media.image) "이미지 미리보기" else "영상 미리보기",
                        modifier = Modifier.size(64.dp).padding(4.dp),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(if (media.image) "이미지 · ${media.author}" else "영상 · ${media.author}")
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
                Text("취소")
            }
            Button(
                enabled = visible.any { it.id in selected },
                onClick = {
                    onAction(
                        DownloadDialogViewModel.Action.DownloadInstagram(
                            visible.filter { it.id in selected },
                            preferences,
                        )
                    )
                },
            ) {
                Text("선택 다운로드")
            }
        }
    }
}
