package com.plugin.instagram

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.junkfood.seal.R
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import com.plugin.PluginRoutes
import com.plugin.compatibility.*

/** 다운로드 DB의 게시글 작성자를 자동 집계하며 수동 계정 목록을 표시하지 않습니다. */
object FrequentAccountsPlugin : FeaturePlugin() {
    override val id = PluginId.ACCOUNTS
    override val route = PluginRoutes.FREQUENT_ACCOUNTS
    override val titleResource = R.string.loc_frequent_accounts
    override val icon = Icons.Outlined.AccountCircle

    /** 새 다운로드·백업 이력과 저장된 프로필 정보를 읽어 계정 목록을 갱신합니다. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        Page(onBack, onAction)
    }

    /** 게시글 상세 설정 다음에 기존 계정 목록과 프로필 동작을 표시합니다. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Page(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        val history by com.junkfood.seal.util.DatabaseUtil.getDownloadHistoryFlow().collectAsStateWithLifecycle(emptyList())
        val accounts by remember { derivedStateOf { FrequentAccountStore.fromHistory(history) } }
        val context = LocalContext.current
        val preferences = createPluginPreferences()
        val imageLoader = remember(context, preferences.proxy, preferences.proxyUrl, preferences.forceIpv4) {
            // CDN 사진도 프로필 조회와 같은 프록시·IPv4 설정을 사용하며 쿠키는 전달하지 않습니다.
            coil.ImageLoader.Builder(context).okHttpClient { SocialProfilePictures.clientFor(preferences)
                .newBuilder().followRedirects(true).followSslRedirects(true).build() }.build()
        }
        DisposableEffect(imageLoader) { onDispose { imageLoader.shutdown() } }
        var picture by remember { mutableStateOf<String?>(null) }
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.loc_post_info_plugin)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { PostInfoPlugin.Settings() }
                item { Text(stringResource(R.string.loc_frequent_accounts), style = MaterialTheme.typography.titleMedium) }
                if (accounts.isEmpty()) item { Text("다운로드 이력에서 확인할 수 있는 계정이 없습니다.") }
                items(accounts, key = { it.platform + ":" + it.username.lowercase() }) { account ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                AccountPicture(account, imageLoader) { picture = it }
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(account.username, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    Text("${account.platform} · ${stringResource(R.string.loc_collected_posts, account.visitCount)}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                if (account.profileUrl.isNotBlank()) TextButton(onClick = {
                                    if (WallpaperHelper.isInstagramUrl(account.profileUrl)) WallpaperHelper.openInstagramUrl(context, account.profileUrl)
                                    else runCatching {
                                        val uri = Uri.parse(account.profileUrl)
                                        if (uri.scheme in setOf("https", "http")) context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                }) { Text(stringResource(R.string.loc_account_open)) }
                                if (account.platform == "instagram" && account.profileUrl.isNotBlank()) FilledTonalButton(onClick = {
                                    onAction(DownloadDialogViewModel.Action.FetchPlaylist(account.profileUrl, createPluginPreferences()))
                                }) { Text(stringResource(R.string.loc_instagram_fetch_posts)) }
                            }
                        }
                    }
                }
            }
        }
        picture?.let { url -> AlertDialog(onDismissRequest = { picture = null },
            title = { Text(stringResource(R.string.loc_profile_picture)) },
            text = { AsyncImage(profilePictureRequest(context, url), null, imageLoader, Modifier.size(240.dp), contentScale = ContentScale.Fit) },
            confirmButton = { TextButton(onClick = { picture = null }) { Text(stringResource(R.string.close)) } }) }
    }
}

/** 누락·만료된 사진을 화면의 계정만 다시 조회하며 실패 시 기본 아이콘에서 재시도합니다. */
@Composable
private fun AccountPicture(account: FrequentAccount, imageLoader: coil.ImageLoader, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var failed by remember(account.platform, account.username, account.profilePicUrl) { mutableStateOf(false) }
    var attempted by remember(account.username) { mutableStateOf(false) }
    var refresh by remember(account.username) { mutableIntStateOf(0) }
    var version by remember(account.username) { mutableIntStateOf(0) }
    var loading by remember(account.username) { mutableStateOf(false) }
    LaunchedEffect(account.username, failed, refresh) {
        if (account.platform != "instagram" || attempted || account.profilePicUrl.isNotBlank() && !failed) return@LaunchedEffect
        attempted = true; loading = true
        try {
            val url = SocialProfilePictures.profilePicture(account.username, createPluginPreferences())
            if (url != null) { FrequentAccountStore.updateProfilePicture(account.username, url); failed = false; version++ }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !loading) {
        if (account.profilePicUrl.isNotBlank() && !failed) onOpen(account.profilePicUrl)
        else { attempted = false; refresh++ }
    }, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Icon(Icons.Outlined.AccountCircle, stringResource(R.string.loc_profile_picture), Modifier.fillMaxSize())
        if (account.profilePicUrl.isNotBlank() && !failed) AsyncImage(
            profilePictureRequest(context, account.profilePicUrl, version),
            stringResource(R.string.loc_profile_picture), imageLoader, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onError = { failed = true })
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** 목록과 확대 화면에서 동일한 CDN 헤더·갱신 캐시 키를 사용합니다. */
private fun profilePictureRequest(context: android.content.Context, url: String, version: Int = 0): coil.request.ImageRequest =
    coil.request.ImageRequest.Builder(context).data(url)
        .addHeader("User-Agent", "Mozilla/5.0")
        .addHeader("Referer", "https://www.instagram.com/")
        .memoryCacheKey("$url#$version").diskCacheKey("$url#$version").build()
