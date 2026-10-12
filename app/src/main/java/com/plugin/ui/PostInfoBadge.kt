package com.plugin.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.plugin.PluginId
import com.plugin.PluginState
import com.plugin.compatibility.SocialPostMetadata
import com.plugin.compatibility.SocialPostMetadataPreferences
import com.plugin.compatibility.SocialPostMetadataStore

/**
 * 대기열 카드 또는 다운로드 목록 아이템에서 게시글 상세 메타데이터를 표시하는 UI 컴포넌트입니다.
 * - POST_INFO 플러그인이 켜져 있을 때만 작동합니다.
 * - 옵션 설정(프로필, 계정명, 본문, 해시태그)에 따라 선택적으로 렌더링합니다.
 * @param postUrl 게시글 웹 주소
 * @param modifier 레이아웃 수식어
 * @param fallbackAuthor 저장된 메타데이터가 없을 때 사용할 대체 작성자명
 */
@Composable
fun PostInfoBadge(
    postUrl: String,
    modifier: Modifier = Modifier,
    fallbackAuthor: String = "",
) {
    if (!PluginState.isEnabled(PluginId.POST_INFO)) return
    // URL이 같아도 비동기 수집 결과가 바뀌면 저장소의 스냅샷을 다시 읽습니다.
    val metadata = (SocialPostMetadataStore.get(postUrl) ?: if (fallbackAuthor.isNotBlank() && fallbackAuthor !in setOf("null", "Instagram", "TikTok")) {
        SocialPostMetadata(postUrl = postUrl, author = fallbackAuthor)
    } else null) ?: return

    val showProfile = SocialPostMetadataPreferences.showProfile && metadata.authorProfilePic.isNotBlank()
    val showAuthor = SocialPostMetadataPreferences.showAuthor && metadata.author.isNotBlank()
    val showText = SocialPostMetadataPreferences.showText && metadata.text.isNotBlank()
    val showHashtags = SocialPostMetadataPreferences.showHashtags && metadata.hashtags.isNotEmpty()
    // 이전 이력에 정보가 없거나 모든 표시 항목이 꺼졌으면 빈 행과 여백도 만들지 않습니다.
    if (!showProfile && !showAuthor && !showText && !showHashtags) return

    var expanded by androidx.compose.runtime.saveable.rememberSaveable(postUrl) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 계정주 프로필 사진 및 계정명
        if (showProfile || showAuthor) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showProfile) {
                    AsyncImage(
                        model = metadata.authorProfilePic,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
                if (showAuthor) {
                    Text(
                        text = "@${metadata.author}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (showText || showHashtags) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(androidx.compose.ui.res.stringResource(if (expanded) com.junkfood.seal.R.string.loc_post_less else com.junkfood.seal.R.string.loc_post_more))
            }
        }
        // 기본 카드에서는 본문·태그를 접고 명시적인 더보기에서만 표시합니다.
        if (expanded && showText) {
            Text(
                text = metadata.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(max = 240.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
            )
        }

        // 해시태그 목록 (클릭 시 전체 보기 / 축약 전환)
        if (expanded && showHashtags) {
            Text(
                text = metadata.hashtags.joinToString(" "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(max = 120.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
            )
        }
    }
}


/** 파일명의 계정 접두사는 유지하되 정보 플러그인 카드에서는 중복 계정명을 제목에서 제외합니다. */
@Composable
fun postCardTitle(title: String, author: String): String =
    if (PluginState.isEnabled(PluginId.POST_INFO) && author.isNotBlank() && title.startsWith("$author [") && title.endsWith("]"))
        androidx.compose.ui.res.stringResource(com.junkfood.seal.R.string.loc_post_identifier, title.removePrefix("$author "))
    else title
