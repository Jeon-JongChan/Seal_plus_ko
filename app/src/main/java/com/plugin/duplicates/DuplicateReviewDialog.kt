package com.plugin.duplicates

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.plugin.compatibility.TemporarySession
import java.io.File

/** 설정과 분리된 비교창에서 보이는 행만 구성하고 보존·제외 선택을 부모 화면에 유지합니다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DuplicateReviewDialog(
    groups: List<DuplicateGroup>, keepers: Map<String, String>, recommended: Map<String, String>,
    omitted: Set<String>, busy: Boolean, candidates: Int, status: String,
    onKeep: (String, String) -> Unit, onSelect: (String, Boolean) -> Unit,
    onOpen: (List<String>) -> Unit, onExclude: (DuplicateMedia) -> Unit,
    onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    var horizontal by rememberSaveable { mutableStateOf(false) }
    val verticalState = rememberLazyListState()
    val horizontalState = rememberLazyListState()
    // 세로 비교도 그룹 안에 일반 Column을 중첩하지 않아 수백 장의 동시 디코딩을 피합니다.
    val rows = remember(groups) { buildList<Pair<DuplicateGroup, DuplicateMedia?>> {
        groups.forEach { group -> add(group to null); group.members.forEach { add(group to it) } }
    } }
    val dateFormat = remember { java.text.DateFormat.getDateTimeInstance() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(Modifier.systemBarsPadding(), topBar = {
                TopAppBar(title = { Text("중복 비교 · ${groups.size}그룹", maxLines = 1) }, actions = {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "비교창 닫기") }
                })
            }, floatingActionButton = {
                if (candidates > 0 && !busy) ExtendedFloatingActionButton(onClick = onDelete,
                    icon = { Icon(Icons.Outlined.Delete, "선택한 중복 삭제") }, text = { Text("삭제 · ${candidates}개") })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!horizontal, onClick = { horizontal = false }, label = { Text("세로 비교") })
                        FilterChip(horizontal, onClick = { horizontal = true }, label = { Text("가로 비교") })
                    }
                    Text("그룹마다 보존할 파일 1개를 선택하세요. 가로 비교는 좌우로 스크롤합니다.",
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (status.isNotBlank()) Text(status, Modifier.padding(horizontal = 16.dp), maxLines = 3)
                    LazyColumn(Modifier.fillMaxSize(), state = if (horizontal) horizontalState else verticalState,
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (horizontal) items(groups, key = { it.id }) { group ->
                            DuplicateGroupHeader(group, group.id !in omitted, busy) { onSelect(group.id, it) }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(group.members, key = { it.identity }) { media ->
                                    DuplicateReviewMedia(media, keepers[group.id] ?: recommended.getValue(group.id), busy,
                                        dateFormat, Modifier.width(280.dp), { onKeep(group.id, media.path) },
                                        { onOpen(group.members.map { it.path }) }, { onExclude(media) })
                                }
                            }
                        } else items(rows, key = { (group, media) -> group.id + ":" + (media?.identity ?: "header") },
                            contentType = { if (it.second == null) "header" else "media" }) { (group, media) ->
                            if (media == null) DuplicateGroupHeader(group, group.id !in omitted, busy) { onSelect(group.id, it) }
                            else DuplicateReviewMedia(media, keepers[group.id] ?: recommended.getValue(group.id), busy,
                                dateFormat, Modifier.fillMaxWidth(), { onKeep(group.id, media.path) },
                                { onOpen(group.members.map { it.path }) }, { onExclude(media) })
                        }
                        if (groups.isEmpty()) item { Text("비교할 중복 후보가 없습니다.") }
                    }
                }
            }
        }
    }
}

/** 그룹 선택과 유사도는 파일별로 반복하지 않고 그룹 머리글에 표시합니다. */
@Composable
private fun DuplicateGroupHeader(group: DuplicateGroup, selected: Boolean, busy: Boolean, onSelect: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Checkbox(selected, onCheckedChange = onSelect, enabled = !busy)
        Text("${group.members.size}개 · " + if (group.exact) "파일 내용 동일" else "AI ${"%.1f".format(group.similarity * 100)}%",
            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
    }
}

/** 썸네일 크기를 제한하고 실제 파일 열기는 공통 미디어 뷰어에 맡깁니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DuplicateReviewMedia(media: DuplicateMedia, keeper: String, busy: Boolean, dateFormat: java.text.DateFormat,
    modifier: Modifier, onKeep: () -> Unit, onOpen: () -> Unit, onExclude: () -> Unit) {
    val context = LocalContext.current
    OutlinedCard(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                RadioButton(keeper == media.path, onClick = onKeep, enabled = !busy)
                AsyncImage(model = if (media.video) File(TemporarySession.root?.let { File(it, ".data") } ?: context.cacheDir,
                    "duplicate-thumbnails/${media.sha256}.jpg")
                    else if (media.path.startsWith("content://")) Uri.parse(media.path) else File(media.path),
                    contentDescription = media.name, modifier = Modifier.size(180.dp))
            }
            Text((if (keeper == media.path) "보존 · " else "삭제 후보 · ") + media.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${media.width}×${media.height} · ${media.bytes / 1024} KB · " + dateFormat.format(java.util.Date(media.modified)),
                style = MaterialTheme.typography.bodySmall)
            Text(media.path, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            FlowRow {
                TextButton(onClick = onOpen) { Text("열기") }
                TextButton(enabled = !busy, onClick = onExclude) { Text("중복 아님 · 제외") }
            }
        }
    }
}
