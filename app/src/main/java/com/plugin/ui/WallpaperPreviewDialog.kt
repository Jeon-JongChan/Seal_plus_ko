package com.plugin.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.plugin.compatibility.VideoWallpaperSource
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.junkfood.seal.R
import com.junkfood.seal.util.ToastUtil
import com.plugin.compatibility.WallpaperHelper
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/**
 * 배경화면 설정 대화상자에 전달하는 각 페이지 미디어 항목 정보입니다.
 * @param pathOrUrl 로컬 파일 경로 또는 썸네일/미디어 URL
 * @param pageIndex 게시글 내 페이지 번호 (0부터 시작)
 * @param title 보조 제목 또는 설명
 */
data class WallpaperPageItem(
    val pathOrUrl: String,
    val pageIndex: Int = 0,
    val title: String = "",
)

/**
 * 게시글 페이지별 실시간 전체 배경 미리보기 및 배경화면 적용 대화상자입니다.
 * - 선택한 페이지 이미지가 전체 화면을 채워 실제 적용 시 모습을 즉시 확인할 수 있습니다.
 * - 중간 슬라이드 카드 형태로 다른 페이지를 선택할 수 있습니다.
 * - 홈 화면 / 잠금 화면 / 둘 다 설정 옵션과 적용 버튼을 제공합니다.
 */
@Composable
fun WallpaperPreviewDialog(
    pages: List<WallpaperPageItem>,
    initialIndex: Int = 0,
    onDismissRequest: () -> Unit,
) {
    if (pages.isEmpty()) return
    var selectedIndex by remember(pages) { mutableIntStateOf(initialIndex.coerceIn(0, pages.size - 1)) }
    var targetScreen by remember { mutableIntStateOf(WallpaperHelper.TARGET_BOTH) }
    var isApplying by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentItem = pages[selectedIndex]
    val video by produceState<Boolean?>(null, currentItem.pathOrUrl) {
        value = withContext(Dispatchers.IO) {
            if (currentItem.pathOrUrl.startsWith("content://")) runCatching { context.contentResolver.getType(android.net.Uri.parse(currentItem.pathOrUrl))?.startsWith("video/") == true }.getOrDefault(false)
            else !currentItem.pathOrUrl.startsWith("http") && com.plugin.compatibility.MediaRules.mediaKind(currentItem.pathOrUrl) == 1
        }
    }
    var videoReady by remember(currentItem.pathOrUrl) { mutableStateOf(false) }
    var videoFailed by remember(currentItem.pathOrUrl) { mutableStateOf(false) }
    var candidate by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val name = candidate
        scope.launch {
            try {
                if (name != null) {
                    if (result.resultCode == android.app.Activity.RESULT_OK) { VideoWallpaperSource.commit(context, name); onDismissRequest() }
                    else VideoWallpaperSource.discard(context, name)
                }
            } catch (_: Exception) { ToastUtil.makeToastSuspend(context.getString(R.string.loc_wallpaper_failed)) }
            finally { candidate = null; isApplying = false }
        }
    }
    var previewLoading by remember(currentItem.pathOrUrl) { mutableStateOf(true) }
    val preview by produceState<android.graphics.Bitmap?>(null, currentItem.pathOrUrl) {
        value = null
        try { value = WallpaperHelper.loadBitmap(context, currentItem.pathOrUrl) }
        finally { previewLoading = false }
    }
    val thumbnailWidth = with(LocalDensity.current) { 80.dp.roundToPx() }
    val thumbnailHeight = with(LocalDensity.current) { 120.dp.roundToPx() }

    Dialog(
        onDismissRequest = { if (!isApplying) onDismissRequest() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. 실제 적용 시 화면을 볼 수 있도록 전체 화면을 꽉 채우는 배경 이미지 미리보기
            preview?.takeIf { !previewLoading }?.let { bitmap ->
                Image(bitmap.asImageBitmap(), stringResource(R.string.loc_wallpaper_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (video == true) VideoWallpaperPreview(currentItem.pathOrUrl, Modifier.fillMaxSize(), onReady = { videoReady = it }, onError = { videoFailed = true })
            if (videoFailed) Text("이 기기에서 재생할 수 없는 영상입니다.", Modifier.align(Alignment.Center), color = Color.White)
            if (video != true && previewLoading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            else if (video != true && preview == null) Text(stringResource(R.string.loc_wallpaper_failed), Modifier.align(Alignment.Center), color = Color.White)

            // 상단/하단 오버레이 가독성을 위한 은은한 세로 그라데이션
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.6f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            )

            // 상단 타이틀 및 닫기 버튼
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.loc_wallpaper_preview),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    if (pages.size > 1) {
                        Text(
                            text = "${selectedIndex + 1} / ${pages.size} 페이지",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                IconButton(enabled = !isApplying, onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White
                    )
                }
            }

            // 하단 컨트롤: 중간 슬라이드 카드 리스트 + 화면 선택 칩 + 적용 버튼
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 2. 중간 슬라이드 카드: 페이지들을 가로 카드로 넘기면서 선택 시 실제 전체 화면 갱신
                if (pages.size > 1) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(pages) { index, item ->
                            val isSelected = index == selectedIndex
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable(enabled = !isApplying) { selectedIndex = index }
                            ) {
                                val thumbnail by produceState<android.graphics.Bitmap?>(null, item.pathOrUrl) {
                                    value = WallpaperHelper.loadBitmap(context, item.pathOrUrl, thumbnailWidth, thumbnailHeight)
                                }
                                thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter)
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(2.dp)
                                ) {
                                    Text(
                                        text = "${index + 1}P",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. 홈 화면 / 잠금 화면 / 둘 다 선택 필터 칩
                if (video == true) Text(stringResource(R.string.loc_video_wallpaper_next), Modifier.fillMaxWidth().padding(16.dp), color = Color.White)
                else Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        WallpaperHelper.TARGET_HOME to R.string.loc_wallpaper_home,
                        WallpaperHelper.TARGET_LOCK to R.string.loc_wallpaper_lock,
                        WallpaperHelper.TARGET_BOTH to R.string.loc_wallpaper_both,
                    ).forEach { (target, labelRes) ->
                        FilterChip(
                            modifier = Modifier.weight(1f),
                            enabled = !isApplying,
                            selected = targetScreen == target,
                            onClick = { targetScreen = target },
                            label = { Text(stringResource(labelRes), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }

                // 4. 배경화면 적용 버튼
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .height(50.dp),
                    enabled = !isApplying && video != null && (if (video == true) videoReady && !videoFailed else !previewLoading && preview != null),
                    onClick = {
                        if (isApplying) return@Button
                        isApplying = true
                        scope.launch {
                            var bitmap: android.graphics.Bitmap? = null
                            try {
                                if (video == true) {
                                    val name = VideoWallpaperSource.prepare(context, currentItem.pathOrUrl)
                                    candidate = name
                                    try { videoPicker.launch(VideoWallpaperSource.intent(context)) }
                                    catch (failure: Exception) { VideoWallpaperSource.discard(context, name); candidate = null; throw failure }
                                    return@launch
                                }
                                bitmap = withContext(Dispatchers.Default) { preview?.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }
                                val success = bitmap?.let { WallpaperHelper.setWallpaper(context, it, targetScreen) } == true
                                ToastUtil.makeToastSuspend(context.getString(if (success) R.string.loc_wallpaper_applied else R.string.loc_wallpaper_failed))
                                if (success) onDismissRequest()
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                ToastUtil.makeToastSuspend(context.getString(R.string.loc_wallpaper_failed))
                            } catch (_: OutOfMemoryError) {
                                ToastUtil.makeToastSuspend(context.getString(R.string.loc_wallpaper_failed))
                            } finally {
                                bitmap?.recycle()
                                if (candidate == null) isApplying = false
                            }
                        }
                    }
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.loc_wallpaper_apply))
                    }
                }
            }
        }
    }
}

