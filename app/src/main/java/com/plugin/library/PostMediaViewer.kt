package com.plugin.library

import androidx.compose.runtime.Composable

/** 저장·완료 화면이 공통 뷰어의 확대·재생·페이지 경계를 그대로 사용합니다. */
@Composable
fun PostMediaViewer(paths: List<String>, initialPath: String?, onDismiss: () -> Unit) =
    com.plugin.compatibility.PostMediaViewer(paths, initialPath, onDismiss)