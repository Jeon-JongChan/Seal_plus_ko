package com.junkfood.seal.ui.page.downloadv2.configure

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junkfood.seal.util.PreferenceUtil

/** 계정을 기기에 저장하고 선택한 계정의 다운로드 설정 화면을 엽니다. */
@Composable
fun InstagramAccounts(onActionPost: (DownloadDialogViewModel.Action) -> Unit) {
    var opened by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf(PreferenceUtil.getInstagramAccounts()) }
    val normalized = username.trim().removePrefix("@").lowercase()
    TextButton(onClick = { opened = true }, modifier = Modifier.padding(horizontal = 24.dp)) {
        Text("인스타그램 계정 (${accounts.size})")
    }
    if (opened)
        AlertDialog(
            onDismissRequest = { opened = false },
            title = { Text("인스타그램 계정") },
            text = {
                Column {
                    Text("계정을 선택한 뒤 쿠키 사용 여부와 영상·이미지 설정을 지정하세요.")
                    OutlinedTextField(
                        username,
                        onValueChange = { username = it },
                        label = { Text("계정 이름") },
                        singleLine = true,
                    )
                    TextButton(
                        enabled = normalized.matches(Regex("[a-z0-9._]{1,30}")),
                        onClick = {
                            accounts = accounts + normalized
                            PreferenceUtil.updateInstagramAccounts(accounts)
                            username = ""
                        },
                    ) {
                        Text("계정 추가")
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(accounts.sorted(), key = { it }) { account ->
                            Row(Modifier.fillMaxWidth()) {
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        opened = false
                                        onActionPost(
                                            DownloadDialogViewModel.Action.ProceedWithURLs(
                                                listOf("https://www.instagram.com/$account/")
                                            )
                                        )
                                    },
                                ) {
                                    Text("@$account")
                                }
                                TextButton(
                                    onClick = {
                                        accounts = accounts - account
                                        PreferenceUtil.updateInstagramAccounts(accounts)
                                    }
                                ) {
                                    Text("제거")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { opened = false }) { Text("닫기") } },
        )
}
