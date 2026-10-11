package com.plugin.instagram

import com.plugin.compatibility.PluginPreferences

import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.junkfood.seal.ui.page.downloadv2.configure.InspectInstagramAction
import com.plugin.compatibility.CookieOptions
import com.plugin.compatibility.MediaDownloadOptions
import androidx.compose.ui.res.stringResource
import com.junkfood.seal.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junkfood.seal.util.DownloadUtil
import com.plugin.compatibility.MediaRules
import com.junkfood.seal.util.PreferenceUtil

/** 쿠키·해상도를 선택하고 등록한 계정의 게시물 목록을 조회합니다. */
@Composable
fun InstagramAccounts(
    onActionPost: (Any) -> Unit,
    standalone: Boolean = false,
) {
    var opened by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf(PluginPreferences.getInstagramAccounts()) }
    val normalized = MediaRules.instagramAccount(username)
    var preferences by remember { com.plugin.compatibility.pluginPreferencesState(com.plugin.compatibility.createPluginPreferences()) }
    val content: @Composable () -> Unit = {
        Column {
            Text(stringResource(R.string.loc_instagram_account_hint))
            CookieOptions(preferences, onUpdate = { preferences = it })
            MediaDownloadOptions(preferences, onUpdate = { preferences = it }, instagram = true)
            OutlinedTextField(
                username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.loc_instagram_account_input)) },
                singleLine = true,
            )
            TextButton(
                enabled = normalized != null,
                onClick = {
                    accounts = accounts + normalized!!
                    PluginPreferences.updateInstagramAccounts(accounts)
                    onActionPost(InspectInstagramAction(
                        "https://www.instagram.com/$normalized/", preferences))
                    opened = false
                    username = ""
                },
            ) {
                Text(stringResource(R.string.loc_instagram_register_fetch))
            }
            TextButton(enabled = normalized != null, onClick = {
                onActionPost(InspectInstagramAction(
                    "https://www.instagram.com/$normalized/", preferences))
                opened = false
            }) { Text(stringResource(R.string.loc_instagram_fetch_posts)) }
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(accounts.sorted(), key = { it }) { account ->
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                opened = false
                                onActionPost(InspectInstagramAction(
                                    "https://www.instagram.com/$account/", preferences))
                            },
                        ) {
                            Text("@$account")
                        }
                        TextButton(
                            onClick = {
                                accounts = accounts - account
                                PluginPreferences.updateInstagramAccounts(accounts)
                            }
                        ) {
                            Text(stringResource(R.string.remove))
                        }
                    }
                }
            }
        }
    }
    if (standalone) content()
    else {
        TextButton(onClick = { opened = true }, modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.loc_instagram_accounts_count, accounts.size))
        }
        if (opened) AlertDialog(onDismissRequest = { opened = false },
            title = { Text(stringResource(R.string.loc_instagram_accounts)) }, text = content,
            confirmButton = { TextButton(onClick = { opened = false }) { Text(stringResource(R.string.close)) } })
    }
}

