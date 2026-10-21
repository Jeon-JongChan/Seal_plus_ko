package com.plugin.compatibility

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plugin.ui.DownloadFolderPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 게시글 또는 현재 페이지의 이동을 확인하고 부분 성공도 즉시 뷰어에 반영합니다. */
@Composable
fun MediaMoveDialog(paths: List<String>, onMoved: (String, String) -> Unit, onDismiss: () -> Unit) {
    val preferences = remember { createPluginPreferences() }
    val sources = remember(paths) { paths.distinct() }
    var target by remember { mutableStateOf<DownloadFolder?>(null) }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var failures by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    if (target == null) {
        DownloadFolderPicker(preferences, onDismiss, onSelect = { target = it })
        return
    }
    AlertDialog(onDismissRequest = { if (!running) onDismiss() }, title = { Text("폴더로 이동") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${sources.size}개 파일 → ${target!!.rootLabel}/${target!!.relative}")
            Text("같은 이름의 기존 파일은 보존하고 새 번호를 붙입니다. 같은 폴더의 파일은 그대로 둡니다.")
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (running || finished) Text("처리 $done / ${sources.size} · 실패 ${failures.size}개")
            if (failures.isNotEmpty()) Text(failures.take(3).joinToString("\n"), color = MaterialTheme.colorScheme.error)
        } }, confirmButton = {
            TextButton(enabled = !running, onClick = {
                if (finished) onDismiss() else {
                    running = true
                    scope.launch {
                        try {
                            val folder = target!!
                            // SAF의 불투명 문서 ID는 경로로 추측하지 않고 기존 트리 탐색을 한 번 재사용합니다.
                            val documents = withContext(Dispatchers.IO) {
                                if (preferences.sdcard) MediaLibrary.listVideos(preferences) { it.path in sources }.associateBy { it.path }
                                else emptyMap()
                            }
                            for (source in sources) {
                                // 파일 이동이 시작되면 화면 종료로 이력·대기열 갱신이 끊기지 않게 합니다.
                                withContext(NonCancellable) {
                                    runCatching {
                                        val moved = withContext(Dispatchers.IO) {
                                            DownloadFolders.validate(folder, createPluginPreferences())
                                            check(!TemporarySession.changing && !com.plugin.duplicates.DuplicateScanService.state.value.running &&
                                                !com.plugin.duplicates.DuplicateScanService.editing.value &&
                                                !com.plugin.compatibility.enhancement.EnhancementClient.busy &&
                                                AiMediaFilter.pending.value.isEmpty()) { "진행 중인 분석·보정 또는 결과 확인을 마친 뒤 이동하세요." }
                                            val media = if (preferences.sdcard) documents[source] ?: error("기본 폴더 안에서 파일을 찾을 수 없습니다.")
                                            else {
                                                val file = File(source)
                                                val root = DownloadFolders.roots(preferences).map(::File).firstOrNull {
                                                    file.canonicalFile.toPath().startsWith(it.canonicalFile.toPath())
                                                } ?: error("기본 폴더 밖의 파일은 이동할 수 없습니다.")
                                                var current: File? = file
                                                while (current != null && current != root.parentFile) {
                                                    check(!java.nio.file.Files.isSymbolicLink(current.toPath())) { "연결된 파일·폴더는 이동할 수 없습니다." }
                                                    current = current.parentFile
                                                }
                                                check(file.isFile && file.canRead() && file.parentFile?.canWrite() == true) { "원본 파일의 접근 권한이 필요합니다." }
                                                MediaLibrary.MediaFile(source, file.name, file.length(), 0.0)
                                            }
                                            val document = if (preferences.sdcard) DownloadFolders.document(folder) else null
                                            MediaLibrary.moveToFolder(media, document?.uri?.toString() ?: DownloadFolders.local(folder).path, document).also {
                                                // NOFACE 밖의 일반 폴더로 직접 이동한 SAF 파일은 이전 부모의 제외 표지를 해제합니다.
                                                if (media.excludedFromProcessing) PluginMediaMetadataStore.save(it, excludedFromProcessing = false)
                                            }
                                        }
                                        onMoved(source, moved)
                                    }.onFailure { failures = failures + (MediaRules.fileName(source) + ": " + it.message.orEmpty()) }
                                    done++
                                }
                            }
                        } catch (failure: Exception) {
                            failures = failures + failure.message.orEmpty()
                        } finally { running = false; finished = true }
                    }
                }
            }) { Text(if (finished) "닫기" else "이동") }
        }, dismissButton = { if (!finished) TextButton(enabled = !running, onClick = onDismiss) { Text("취소") } })
}
