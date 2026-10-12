package com.plugin.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R

/** 이미지 위에서 읽을 수 있는 같은 퀵 동작과 48dp 터치 영역을 두 화면이 공유합니다. */
@Composable
fun MediaQuickActions(modifier: Modifier = Modifier, onWallpaper: (() -> Unit)? = null,
    onInstagram: (() -> Unit)? = null, onMore: (() -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(Triple(onWallpaper, Icons.Outlined.Wallpaper, R.string.loc_wallpaper_setting),
            Triple(onInstagram, Icons.AutoMirrored.Outlined.OpenInNew, R.string.loc_open_instagram),
            Triple(onMore, Icons.Outlined.MoreVert, R.string.show_more_actions)).forEach { (action, icon, label) ->
            if (action != null) Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.65f), contentColor = Color.White) {
                IconButton(action, Modifier.size(48.dp)) { Icon(icon, stringResource(label), Modifier.size(20.dp)) }
            }
        }
    }
}
