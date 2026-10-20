package com.plugin.runtime

import com.junkfood.seal.App
import com.plugin.compatibility.*
import com.plugin.compatibility.enhancement.EnhancementClient
import com.plugin.compatibility.enhancement.EnhancementFiles
import com.plugin.faces.FaceScanService
import com.plugin.library.MediaScanService
import com.plugin.queue.PluginTask
import com.plugin.queue.PluginTaskBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** 기존 엔진을 재사용하며 사용자가 요청한 임시 세션 진입·병합·폐기만 처리합니다. */
object TemporarySessionController {
    private val transition = Mutex()

    /** 전환 중 새 다운로드가 시작되지 않도록 엔진의 공통 진입점을 잠급니다. */
    private suspend fun change(engine: PluginTaskEngine, block: suspend () -> Unit) = transition.withLock {
        synchronized(engine) { requireIdle(engine); TemporarySession.changing = true }
        try { block() } finally { TemporarySession.changing = false }
    }

    /** 작업 중 저장 루트가 바뀌어 파일·이력이 다른 저장소로 넘어가는 것을 막습니다. */
    private fun requireIdle(engine: PluginTaskEngine) {
        check(engine.restored && !EnhancementClient.busy && !EnhancementFiles.busy && !FaceScanService.state.value.running && !MediaScanService.state.value.running &&
            !com.plugin.duplicates.DuplicateScanService.state.value.running && !com.plugin.duplicates.DuplicateScanService.editing.value &&
            engine.getTaskStateMap().values.none { it.downloadState is PluginTask.DownloadState.Cancelable ||
                it.downloadState == PluginTask.DownloadState.Idle || it.downloadState == PluginTask.DownloadState.ReadyWithInfo }) {
            "진행 중인 다운로드·분류·보정 작업을 마친 후 변경하세요."
        }
    }

    /** 일반 백업을 보존한 뒤 내부 저장소의 소유 세션 폴더로 전환합니다. */
    suspend fun enter(engine: PluginTaskEngine) = change(engine) {
        withContext(Dispatchers.IO) {
            requireIdle(engine)
            check(TemporarySession.session == null)
            val preferences = createPluginPreferences()
            check(!preferences.sdcard) { "임시 숨기기는 일반 내부 저장소 폴더만 지원합니다." }
            val base = File(MediaLibrary.rootPath(preferences)).canonicalFile
            val id = UUID.randomUUID().toString()
            val value = TemporarySession.Session(id, base.path, File(base, "tmp/session-$id").path,
                App.videoDownloadDir, App.audioDownloadDir, preferences.privateDirectory)
            val root = TemporarySession.owned(value)
            check(root.mkdirs()) { "임시 폴더를 만들 수 없습니다." }
            TemporarySession.write(File(root, ".session"), id)
            TemporarySession.write(File(root, ".nomedia"), "")
            synchronized(PluginTaskBackup) {
                PluginTaskBackup.saveNormal(engine.getTaskStateMap().toMap())
                engine.switchSession(value, emptyMap())
            }
        }
    }

    /** 복사·이력·백업이 모두 성공한 뒤에만 임시 자료를 제거합니다. 실패하면 세션을 유지합니다. */
    suspend fun exit(engine: PluginTaskEngine, merge: Boolean) = change(engine) {
        withContext(Dispatchers.IO) {
            requireIdle(engine)
            val value = checkNotNull(TemporarySession.session)
            val root = TemporarySession.owned(value)
            check(File(root, ".session").readText() == value.id) { "임시 폴더 소유 기록이 일치하지 않습니다." }
            val contents = root.walkTopDown().toList()
            check(contents.none { Files.isSymbolicLink(it.toPath()) || !it.canonicalPath.startsWith(root.path + File.separator) && it != root }) {
                "임시 폴더 밖으로 연결된 경로가 있습니다."
            }
            val normalTasks = PluginTaskBackup.load(null).toMutableMap()
            if (merge) merge(engine, value, contents, normalTasks)
            App.videoDownloadDir = value.video
            App.audioDownloadDir = value.audio
            synchronized(PluginTaskBackup) {
                PluginTaskBackup.saveNormal(normalTasks)
                engine.switchSession(null, normalTasks)
            }
            // 백업 수집기의 이전 쓰기가 끝난 뒤 열린 저장소와 소유 폴더를 정리합니다.
            PluginTaskBackup.save(normalTasks, null)
            TemporarySession.closeTemporary()
            // 사용자 확인을 받은 소유 세션만 지우며 기본 폴더나 기존 tmp 자료는 건드리지 않습니다.
            check(root.deleteRecursively()) { "임시 자료 정리에 실패했습니다: ${root.path}" }
        }
    }

    /** 같은 크기의 다른 파일도 구분해 재시도 시 이미 복사한 자료만 재사용합니다. */
    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest()
    }

    /** 충돌 이름은 바꾸고 경로 대응표를 먼저 보존해 실패 후 재시도에서도 복제를 중복하지 않습니다. */
    private suspend fun merge(engine: PluginTaskEngine, value: TemporarySession.Session, contents: List<File>,
        tasks: MutableMap<PluginTask, PluginTask.State>) {
        val root = TemporarySession.owned(value)
        val journal = File(root, ".data/merge.json")
        val paths: MutableMap<String, String> = if (journal.isFile) Json.decodeFromString<Map<String, String>>(journal.readText()).toMutableMap() else mutableMapOf()
        contents.filter { it.isFile && !it.relativeTo(root).path.startsWith(".data" + File.separator) && it.name !in setOf(".session", ".nomedia") }.forEach { source ->
            val target = paths[source.path]?.let(::File) ?: run {
                val original = File(value.base, source.relativeTo(root).path)
                var candidate = original
                var index = 1
                while (candidate.exists() || candidate.path in paths.values)
                    candidate = File(original.parentFile, "${original.nameWithoutExtension} (${index++}).${original.extension}")
                paths[source.path] = candidate.path
                TemporarySession.write(journal, Json.encodeToString(paths))
                candidate
            }
            check(target.canonicalPath.startsWith(File(value.base).canonicalPath + File.separator) &&
                !target.canonicalPath.startsWith(root.path + File.separator)) { "통합 대상 경로가 올바르지 않습니다." }
            if (!target.exists()) {
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                val atomic = android.util.AtomicFile(target)
                val output = atomic.startWrite()
                try { source.inputStream().use { it.copyTo(output) }; atomic.finishWrite(output) }
                catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            }
            check(digest(source).contentEquals(digest(target))) { "통합 파일이 변경되었습니다: ${target.name}" }
        }
        val normal = checkNotNull(TemporarySession.normalDao)
        TemporarySession.dao().getDownloadHistory().forEach { info ->
            paths[info.videoPath]?.let { path -> normal.insertInfoDistinctByPath(info.copy(id = 0, videoPath = path,
                thumbnailUrl = paths[info.thumbnailUrl] ?: info.thumbnailUrl, videoUrl = paths[info.videoUrl] ?: info.videoUrl), path) }
        }
        listOf("plugin_media_metadata", "social_post_metadata").forEach { name ->
            val source = TemporarySession.storage(name)
            val destination = com.tencent.mmkv.MMKV.mmkvWithID(name)
            source.allKeys().orEmpty().forEach { key ->
                source.decodeString(key)?.let { check(destination.encode(paths[key] ?: key, it)) }
            }
        }
        engine.getTaskStateMap().forEach { (task, state) ->
            val done = state.downloadState as? PluginTask.DownloadState.Completed
            val moved = done?.filePaths?.mapNotNull(paths::get).orEmpty()
            val preferences = task.preferences.copyPreservingPluginOptions { copy(privateDirectory = value.privateDirectory, sdcard = false) }
                .copyPluginOptions(selectedFolder = null)
            tasks[task.copy(id = "temporary:${value.id}:${task.id}", preferences = preferences)] =
                if (done == null) state else state.copy(downloadState = done.copy(filePath = moved.firstOrNull(), filePaths = moved, missing = moved.isEmpty()))
        }
    }
}
