package com.plugin.temporary

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.download.DownloaderV2
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import com.plugin.compatibility.TemporarySession
import com.plugin.runtime.PluginTaskEngine
import com.plugin.runtime.TemporarySessionController
import com.plugin.runtime.pluginQueue
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** 임시 저장소 진입과 명시적 통합·폐기 확인을 제공하는 플러그인입니다. */
object TemporaryPlugin : FeaturePlugin() {
    override val id = PluginId.HIDDEN
    override val route = "temporary_session"
    override val titleResource = R.string.plugin_temporary
    override val icon = Icons.Outlined.VisibilityOff

    /** 기존 저장소 화면을 복제하지 않고 전환 동작만 제공합니다. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        val engine = koinInject<DownloaderV2>().pluginQueue() as PluginTaskEngine
        val scope = rememberCoroutineScope()
        var confirm by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        Scaffold(topBar = { TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(titleResource)) }, navigationIcon = { BackButton(onBack) }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(androidx.compose.ui.res.stringResource(R.string.plugin_temporary_desc))
                TemporarySession.root?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(enabled = !busy, onClick = {
                    if (TemporarySession.root != null) confirm = true
                    else scope.launch {
                        busy = true
                        runCatching { TemporarySessionController.enter(engine) }.onFailure { error = it.message.orEmpty() }
                        busy = false
                    }
                }) { Text(androidx.compose.ui.res.stringResource(if (TemporarySession.root == null) R.string.temporary_enter else R.string.temporary_exit)) }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
        if (confirm) TemporaryExitDialog(onDismiss = { confirm = false }, onFinished = onBack)
    }
}

/** 플러그인 해제와 세션 종료에서 같은 통합·삭제 확인을 사용합니다. */
@Composable
fun TemporaryExitDialog(onDismiss: () -> Unit, onFinished: () -> Unit) {
    val engine = koinInject<DownloaderV2>().pluginQueue() as PluginTaskEngine
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    /** 확인한 선택만 실행하며 실패 시 대화상자와 세션을 유지합니다. */
    fun finish(merge: Boolean) {
        scope.launch {
            busy = true
            runCatching { TemporarySessionController.exit(engine, merge) }.onSuccess { onFinished() }
                .onFailure { error = it.message.orEmpty() }
            busy = false
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(androidx.compose.ui.res.stringResource(R.string.temporary_exit)) },
        text = { Column { Text(androidx.compose.ui.res.stringResource(R.string.temporary_warning)); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } },
        confirmButton = { TextButton(enabled = !busy, onClick = { finish(true) }) { Text(androidx.compose.ui.res.stringResource(R.string.temporary_merge)) } },
        dismissButton = { Row {
            TextButton(enabled = !busy, onClick = { finish(false) }) { Text(androidx.compose.ui.res.stringResource(R.string.temporary_discard)) }
            TextButton(enabled = !busy, onClick = onDismiss) { Text(androidx.compose.ui.res.stringResource(android.R.string.cancel)) }
        } })
}
