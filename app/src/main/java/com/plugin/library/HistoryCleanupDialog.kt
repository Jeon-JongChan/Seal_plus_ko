package com.plugin.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plugin.compatibility.HistoryCleanup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 파일 부재가 확인된 이력 수를 보여준 뒤 정보만 삭제하도록 승인받습니다. */
@Composable
fun HistoryCleanupDialog(onDismiss: () -> Unit, onPermissionNeeded: () -> Unit, onFinished: (Int) -> Unit) {
    var plan by remember { mutableStateOf<HistoryCleanup.Plan?>(null) }
    var checking by remember { mutableStateOf(true) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        try { plan = HistoryCleanup.prepare() }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message.orEmpty() }
        finally { checking = false }
    }
    AlertDialog(onDismissRequest = { if (!deleting) onDismiss() }, title = { Text("다운로드 이력 정리") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (checking || deleting) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (checking) "파일과 이력을 확인하고 있습니다." else "파일이 없는 이력 ${plan?.missing?.size ?: 0}개")
                Text("이력 정보만 삭제하며 실제 파일은 유지합니다.")
                plan?.let { result ->
                    if (result.unknown > 0) Text("권한·접근 문제로 확인할 수 없는 ${result.unknown}개는 보존합니다.")
                    result.missing.take(3).forEach { Text(it.videoTitle.ifBlank { it.videoPath }, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                if (error.isNotBlank() || (plan?.unknown ?: 0) > 0) TextButton(enabled = !deleting, onClick = onPermissionNeeded) { Text("폴더 권한 확인") }
            }
        }, confirmButton = {
            TextButton(enabled = !checking && !deleting && !plan?.missing.isNullOrEmpty(), onClick = {
                val approved = plan ?: return@TextButton
                deleting = true; error = ""
                scope.launch {
                    try { val count = HistoryCleanup.remove(approved); onFinished(count) }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message.orEmpty() }
                    finally { deleting = false }
                }
            }) { Text("정보 정리") }
        }, dismissButton = { TextButton(enabled = !deleting, onClick = onDismiss) { Text("닫기") } })
}
