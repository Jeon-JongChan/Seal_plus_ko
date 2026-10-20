package com.plugin.compatibility.enhancement

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.database.objects.VideoQuality
import com.plugin.compatibility.*
import com.plugin.queue.PluginTask
import com.plugin.runtime.pluginQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 확정 버튼을 누른 경우에만 파일을 저장하고 원본의 변경 여부를 다시 확인합니다. */
object EnhancementFiles {
    private val saves = java.util.concurrent.atomic.AtomicInteger()
    /** 파일 확정이 끝나기 전 저장소 전환을 막습니다. */
    val busy: Boolean get() = saves.get() > 0
    /** 원래 인코딩을 유지할 수 없는 형식은 PNG 복제만 제공합니다. */
    fun canReplace(preview: EnhancementClient.Preview): Boolean = if (preview.video) preview.format == "mp4"
        else preview.format in listOf("jpg", "jpeg", "png", "webp")

    /** 저장 중 실패하면 원본을 보존하고, 이력과 완료 대기열에 새 경로를 반영합니다. */
    suspend fun save(context: Context, preview: EnhancementClient.Preview, replace: Boolean): String = withContext(Dispatchers.IO) {
        preview.retain()
        saves.incrementAndGet()
        try {
        check(!TemporarySession.changing && (TemporarySession.root == null ||
            File(preview.source).canonicalPath.startsWith(TemporarySession.root + File.separator))) {
            "저장소가 변경되었습니다. 현재 라이브러리에서 다시 보정하세요."
        }
        check(!replace || canReplace(preview)) { "이 형식은 ${if (preview.video) "MP4" else "PNG"} 복제로 저장하세요." }
        check(EnhancementModels.hash(context, preview.source, if (preview.video) VideoEnhancer.MAX_SOURCE_BYTES else 64L * 1024 * 1024) == preview.hash) {
            "원본이 변경되었습니다. 다시 보정한 뒤 저장하세요."
        }
        val input = if (replace && !preview.video) File(preview.directory, "replacement") else preview.image
        check(input.isFile && input.length() > 0) { "보정 결과가 없습니다." }
        // 파일 확정부터 이력·완료 대기열 갱신까지는 함께 끝내 경로 불일치를 방지합니다.
        withContext(kotlinx.coroutines.NonCancellable) {
        // 파일을 쓰기 전에 원본 이력을 찾아, 저장 직후 폴더 검사가 복제를 외부 파일로 먼저 등록하지 않게 합니다.
        // 이력이 file URI·정규화 전 경로로 저장된 경우도 같은 파일로 인정해야 복제가 원본 게시글에 묶입니다.
        val saved = PluginDatabaseUtil.getDownloadHistory().let { history ->
            history.firstOrNull { it.videoPath == preview.source } ?: history.firstOrNull { MediaRules.sameFilePath(it.videoPath, preview.source) }
        }
        val extension = if (preview.video) "mp4" else "png"
        val result = if (preview.source.startsWith("content://")) saveDocument(context, preview.source, input, replace, extension)
            else {
                val original = if (preview.source.startsWith("file://")) File(Uri.parse(preview.source).path!!) else File(preview.source)
                val output = if (replace) original else original.resolveSibling(EnhancementRules.duplicateName(original.name, java.util.UUID.randomUUID().toString().take(8), extension))
                check(replace || !output.exists()) { "복제 대상에 이미 파일이 있습니다." }
                copyAtomic(input, output)
                android.media.MediaScannerConnection.scanFile(context, arrayOf(output.absolutePath), null, null)
                output.absolutePath
            }
        val codec = if (preview.video) preview.codec.ifBlank { VideoEnhancer.inspect(preview.image).format.getString(android.media.MediaFormat.KEY_MIME).orEmpty() }
            else if (!replace) "image/png" else when (preview.format) { "jpg", "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> "image/png" }
        val quality = VideoQuality(preview.width, preview.height, preview.frameRate, preview.duration, input.length(), codec)
        val originalMetadata = saved?.let { PluginMediaMetadataStore.get(it.videoPath) } ?: PluginMediaMetadataStore.get(preview.source)
        val duplicate = !replace || originalMetadata?.aiDuplicate == true || PluginMediaMetadataStore.isAiDuplicate(preview.source)
        // SAF 교체의 경로 이전이 이전 메타데이터로 새 결과를 덮어쓰지 않도록 먼저 이전합니다.
        if (replace && result != preview.source) PluginDatabaseUtil.updateMediaPath(saved?.videoPath ?: preview.source, result)
        PluginMediaMetadataStore.save(result, quality = quality, sourceQuality = originalMetadata?.sourceQuality ?: originalMetadata?.quality,
            postPageIndex = originalMetadata?.postPageIndex ?: 0, postPageCount = originalMetadata?.postPageCount ?: 1,
            aiDuplicate = duplicate, aiReplaced = replace)
        // 저장과 동시에 폴더 검사가 같은 복제를 먼저 등록했다면 이력을 두 번 넣지 않습니다.
        if (!replace && saved != null && PluginDatabaseUtil.getDownloadHistory().none { it.videoPath == result })
            PluginDatabaseUtil.insertInfoImmediately(saved.copy(id = 0, videoPath = result))
        val downloader = org.koin.core.context.GlobalContext.get().get<com.junkfood.seal.download.DownloaderV2>().pluginQueue()
        downloader.getTaskStateMap().toMap().forEach { (task, _) ->
            downloader.getTaskStateMap().updateTaskState(task) { state ->
                val completed = state.downloadState as? PluginTask.DownloadState.Completed
                if (completed == null || completed.filePaths.none { MediaRules.sameFilePath(it, preview.source) }) state else {
                    val paths = if (replace) completed.filePaths.map { if (MediaRules.sameFilePath(it, preview.source)) result else it }
                        else (completed.filePaths + result).distinct()
                    state.copy(downloadState = PluginTask.DownloadState.Completed(paths.firstOrNull(), paths, completed.missing))
                }
            }
        }
        AiMediaFilter.end(listOf(preview.source))
        result
        }
        } finally { saves.decrementAndGet(); preview.release() }
    }

    /** 로컬 파일은 Android의 원자적 파일 교체를 사용합니다. */
    private fun copyAtomic(input: File, output: File) {
        val atomic = AtomicFile(output)
        val stream = atomic.startWrite()
        try { input.inputStream().use { check(it.copyTo(stream) == input.length()) }; atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    /** SAF는 완성 사본을 만든 뒤 이름을 교환하며 실패하면 원본 이름을 복구합니다. */
    private fun saveDocument(context: Context, source: String, input: File, replace: Boolean, extension: String): String {
        val resolver = context.contentResolver
        val uri = Uri.parse(source)
        require(DocumentsContract.isDocumentUri(context, uri)) { "이 저장소는 직접 저장을 지원하지 않습니다. 파일을 다운로드 폴더로 옮겨 보정하세요." }
        val id = DocumentsContract.getDocumentId(uri)
        require('/' in id) { "상위 폴더 접근 권한이 필요합니다." }
        val parent = DocumentsContract.buildDocumentUriUsingTree(uri, id.substringBeforeLast('/'))
        val original = DocumentFile.fromSingleUri(context, uri) ?: error("원본 파일을 열 수 없습니다.")
        val name = original.name ?: error("원본 이름을 확인할 수 없습니다.")
        val token = java.util.UUID.randomUUID().toString().take(8)
        val mime = if (replace) original.type ?: error("원본 파일 형식을 확인할 수 없습니다.") else if (extension == "mp4") "video/mp4" else "image/png"
        val staged = DocumentsContract.createDocument(resolver, parent, mime, ".seal-ai-$token.part") ?: error("폴더에 파일을 만들 권한이 필요합니다.")
        var keep = false
        try {
            resolver.openFileDescriptor(staged, "w")!!.use { descriptor ->
                java.io.FileOutputStream(descriptor.fileDescriptor).use { stream ->
                    input.inputStream().use { check(it.copyTo(stream) == input.length()) }; stream.fd.sync()
                }
            }
            check(DocumentFile.fromSingleUri(context, staged)?.length() == input.length()) { "결과 파일 크기 검증에 실패했습니다." }
            if (!replace) return (DocumentsContract.renameDocument(resolver, staged, EnhancementRules.duplicateName(name, token, extension))
                ?: error("복제 이름을 저장할 수 없습니다.")).toString().also { keep = true }
            // 원본은 삭제하지 않고 복구용 이름으로 보관한 뒤 완성 사본을 교체합니다.
            val backup = DocumentsContract.renameDocument(resolver, uri, ".seal-ai-original-$token.backup")
                ?: error("이 저장소는 교체를 지원하지 않습니다. 복제를 사용하세요.")
            try {
                val output = DocumentsContract.renameDocument(resolver, staged, name) ?: error("교체 이름을 저장할 수 없습니다.")
                keep = true
                // 새 파일 검증·이름 교체 후에만 원본 임시 백업을 정리합니다.
                runCatching { DocumentsContract.deleteDocument(resolver, backup) }
                return output.toString()
            } catch (failure: Throwable) {
                check(DocumentsContract.renameDocument(resolver, backup, name) != null) {
                    "원본 이름 복구가 실패했습니다. 폴더의 .seal-ai-original-$token.backup 파일을 $name 이름으로 복구하세요."
                }
                throw failure
            }
        } finally { if (!keep) runCatching { DocumentsContract.deleteDocument(resolver, staged) } }
    }
}
