package com.plugin.compatibility

import com.junkfood.seal.util.*
import com.junkfood.seal.R
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.videoDownloadDir
import com.junkfood.seal.database.objects.VideoQuality
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 다운로드 중복 확인, 비트레이트 폴더 분류와 AV1 후처리를 담당합니다. */
object MediaLibrary {
    private val downloadLocks = ConcurrentHashMap<String, ReentrantLock>()
    /** 준비·재시도 사이의 취소도 같은 작업에 묶고 다음 작업의 프로세스를 종료하지 않습니다. */
    private class Operation {
        @Volatile var canceled = false
        @Volatile var process: Process? = null
    }
    private val operations = ConcurrentHashMap<String, Operation>()
    private val registrationLock = Mutex()
    // ponytail: 독립 FFmpeg의 전역 MediaCodec 초기화 실패는 앱 재시작까지 기억합니다. 런타임 교체 후 재시작하면 재검사합니다.
    @Volatile private var ffmpegHardwareAvailable = true

    /** 파일 관리자에 표시할 실제 영상과 부모 폴더 정보입니다. */
    data class MediaFile(
        val path: String,
        val name: String,
        val bytes: Long,
        val duration: Double,
        val parentUri: String? = null,
        val quality: VideoQuality? = null,
        val sourceQuality: VideoQuality? = null,
        val external: Boolean = false,
        val excludedFromProcessing: Boolean = false,
    )

    /** Seal과 지정한 다운로드 폴더의 모든 하위 폴더에서 영상을 읽습니다. */
    fun listVideos(preferences: DownloadUtil.DownloadPreferences, includeClassified: Boolean = true): List<MediaFile> {
        val extensions = setOf("mp4", "mkv", "webm", "mov", "m4v", "jpg", "jpeg", "png", "webp", "avif", "gif",
            "mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "alac")
        val roots = if (preferences.sdcard) emptyList() else listOf(File(
            if (preferences.privateDirectory) com.junkfood.seal.App.privateDownloadDir else videoDownloadDir))
        val files =
            roots
                .flatMap { root ->
                    if (!root.canRead()) throw SecurityException(
                        context.getString(R.string.revision6_folder_access_error, root.path))
                    root
                        .walkTopDown()
                        .onFail { directory, cause -> throw SecurityException(
                            context.getString(R.string.revision6_folder_access_error, directory.path), cause) }
                        .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.name != "tmp" &&
                            (includeClassified || !it.name.matches(Regex("[0-9]+(p|x[0-9]+)"))) }
                        .filter {
                            it.isFile && !it.isHidden && !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
                                it.extension.lowercase() in extensions
                        }
                        .map {
                            MediaFile(
                                it.absolutePath,
                                it.name,
                                it.length(),
                                0.0,
                            )
                        }
                        .toList()
                }
                .distinctBy { it.path }
                .toMutableList()
        if (preferences.sdcard)
            (DocumentFile.fromTreeUri(context, Uri.parse(preferences.sdcardUri))
                ?: throw SecurityException(context.getString(R.string.revision6_folder_access_error, preferences.sdcardUri))).let { tree ->
                files += listTreeVideos(tree, extensions, includeClassified)
            }
        val history = runBlocking { PluginDatabaseUtil.getDownloadHistory() }.associateBy { it.videoPath }
        return files.map { file ->
            if (file.path.startsWith("content://") && (PluginMediaMetadataStore.get(file.path)?.excludedFromProcessing ?: false) != file.excludedFromProcessing)
                PluginMediaMetadataStore.save(file.path, excludedFromProcessing = file.excludedFromProcessing)
            val saved = history[file.path]
            val quality = saved?.quality ?: readMediaQuality(file.path)
            // 이전 버전 이력은 파일 관리자에서 처음 읽을 때만 화질 정보를 보충합니다.
            if (saved != null && saved.quality == null && quality != null)
                PluginMediaMetadataStore.save(saved.videoPath, quality = quality)
            file.copy(duration = quality?.duration ?: file.duration,
                quality = quality, sourceQuality = saved?.sourceQuality, external = saved == null || saved.extractor == "External")
        }.sortedBy { it.name.lowercase() }
    }

    /** ID가 확인된 이전 이력을 연결한 뒤 나머지 외부 파일만 등록합니다. */
    suspend fun registerExternalFiles(files: List<MediaFile>) = registrationLock.withLock {
        val history = PluginDatabaseUtil.getDownloadHistory()
        val byName = history.filter { it.extractor != "External" }.groupBy {
            if (it.videoPath.startsWith("content://")) Uri.parse(it.videoPath).lastPathSegment?.substringAfterLast('/')
            else File(it.videoPath).name
        }
        val fileCounts = files.groupingBy { it.name }.eachCount()
        val recorded = history.map { it.videoPath }.toMutableSet()
        val origins = MediaRules.duplicateOrigins(history.filter { it.extractor != "External" }) { it.videoPath }
        for (file in files) {
            val candidates = byName[file.name].orEmpty()
            val oldPath = MediaRules.reconnectionSource(file.name, candidates)
            // 저장된 파일명이 정확히 일치하고 실제 파일이 유일해야 이전 경로를 교체합니다.
            if (oldPath != null && oldPath != file.path && !exists(oldPath) &&
                fileCounts[file.name] == 1 &&
                history.none { it.videoPath == file.path && it.extractor != "External" }) {
                PluginDatabaseUtil.reconnectMediaPath(oldPath, file.path)
                PluginMediaMetadataStore.save(file.path, quality = file.quality)
                recorded.add(file.path)
            }
            if (!recorded.add(file.path)) continue
            // 이력 연결 없이 발견된 AI 복제는 같은 폴더의 원본 이력을 복사해 원본 게시글에 묶습니다.
            val origin = if (PluginMediaMetadataStore.isAiDuplicate(file.path)) origins(file.path) else null
            if (origin != null) {
                PluginMediaMetadataStore.save(file.path, quality = file.quality, sourceQuality = origin.sourceQuality ?: origin.quality,
                    postPageIndex = origin.postPageIndex, postPageCount = origin.postPageCount, aiDuplicate = true)
                PluginDatabaseUtil.insertInfoImmediately(origin.copy(id = 0, videoPath = file.path,
                    thumbnailUrl = if (origin.thumbnailUrl == origin.videoPath) file.path else origin.thumbnailUrl))
                continue
            }
            PluginMediaMetadataStore.save(file.path, quality = file.quality)
            PluginDatabaseUtil.insertInfoImmediately(com.junkfood.seal.database.objects.DownloadedVideoInfo(
                id = 0, videoTitle = file.name, videoAuthor = "", videoUrl = file.path,
                thumbnailUrl = file.path, videoPath = file.path, extractor = "External"))
        }
    }

    /** 다운로드 설정의 기본 경로를 사용하며 SAF는 이미 저장된 접근 권한을 재사용합니다. */
    fun rootPath(preferences: DownloadUtil.DownloadPreferences): String = when {
        preferences.sdcard -> preferences.sdcardUri
        preferences.privateDirectory -> com.junkfood.seal.App.privateDownloadDir
        else -> videoDownloadDir
    }

    /** 루트와 심볼릭 링크를 보존하며 실제로 비어 있는 하위 폴더만 비재귀 삭제합니다. */
    fun cleanEmptyFolders(preferences: DownloadUtil.DownloadPreferences): Int {
        check(!preferences.sdcard) { context.getString(R.string.revision6_saf_cleanup_hint) }
        val root = File(rootPath(preferences)).canonicalFile
        var count = 0
        root.walkBottomUp().onEnter { !MediaRules.isNoFacePath(it.path) && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { directory ->
            if (directory != root && directory.isDirectory &&
                directory.canonicalFile.toPath().startsWith(root.toPath()) &&
                directory.listFiles()?.isEmpty() == true && directory.delete()) count++
        }
        return count
    }

    /** SAF 폴더에서도 이동에 필요한 부모 URI를 함께 보관합니다. */
    private fun listTreeVideos(parent: DocumentFile, extensions: Set<String>, includeClassified: Boolean, excluded: Boolean = false): List<MediaFile> {
        // NOFACE도 게시글과 경로를 연결해야 하므로 읽기 목록에는 포함합니다.
        val skipProcessing = excluded || MediaRules.isNoFacePath(parent.name.orEmpty())
        if (!parent.canRead()) throw SecurityException(context.getString(R.string.revision6_folder_access_error, parent.uri.toString()))
        return parent.listFiles().flatMap { file ->
            if (file.isDirectory) {
                if (file.name == "tmp" || (!includeClassified && file.name.orEmpty().matches(Regex("[0-9]+(p|x[0-9]+)")))) emptyList()
                else listTreeVideos(file, extensions, includeClassified, skipProcessing)
            }
            else if (file.name.orEmpty().substringAfterLast('.').lowercase() in extensions)
                listOf(
                    MediaFile(
                        file.uri.toString(),
                        file.name.orEmpty(),
                        file.length(),
                        0.0,
                        parent.uri.toString(),
                        excludedFromProcessing = skipProcessing,
                    )
                )
            else emptyList()
        }
    }

    /** 기존 파일을 덮어쓰지 않고 화질 폴더로 이동하며 이력도 갱신합니다. */
    suspend fun classify(file: MediaFile, preferences: DownloadUtil.DownloadPreferences, replaceDuplicates: Boolean = false): String {
        if (file.excludedFromProcessing || PluginMediaMetadataStore.isProcessingExcluded(file.path) || MediaRules.isNoFacePath(file.parentUri.orEmpty())) return file.path
        val classificationQuality = file.sourceQuality ?: file.quality
        val group = if (preferences.classifyMedia) MediaRules.classificationFolder(classificationQuality,
            preferences.imageTiers, preferences.videoTiers, preferences.codecRatios, preferences.classificationTarget, preferences.classificationUpperFirst) else null
        val newPath =
            if (file.parentUri == null)
                finish(
                        listOf(file.path),
                        preferences.copyPluginOptions(mediaProcessing = true, transcodeAv1 = false),
                        "file-manager",
                        classificationQuality = classificationQuality,
                        preserveParent = true,
                        collisionReplace = replaceDuplicates,
                        mediaRoot = File(if (preferences.privateDirectory) com.junkfood.seal.App.privateDownloadDir else videoDownloadDir),
                    )
                    .single()
            else {
                val tree =
                    DocumentFile.fromTreeUri(context, Uri.parse(preferences.sdcardUri))
                        ?: error(com.junkfood.seal.App.context.getString(R.string.loc_storage_unavailable))
                val parent = DocumentFile.fromSingleUri(context, Uri.parse(file.parentUri))
                    ?.takeIf { it.isDirectory } ?: error(context.getString(R.string.loc_parent_folder_unavailable))
                val base = classificationTreeBase(tree, parent.uri)
                    ?: error(context.getString(R.string.loc_parent_folder_unavailable))
                // 비활성 구간의 기존 파일도 기본 폴더로 돌리며 같은 위치면 이동하지 않습니다.
                val target = if (group == null) base else
                    base.findFile(group)?.also { check(it.isDirectory) }
                        ?: base.createDirectory(group)
                        ?: error(com.junkfood.seal.App.context.getString(R.string.loc_quality_folder_create_failed))
                if (android.provider.DocumentsContract.getDocumentId(target.uri) ==
                    android.provider.DocumentsContract.getDocumentId(parent.uri)) return file.path
                if (target.findFile(file.name) != null) {
                    val newPath = moveDocumentCollision(file, target, replaceDuplicates)
                    PluginDatabaseUtil.updateMediaPath(file.path, newPath)
                    return newPath
                }
                // 이동을 지원하지 않는 공급자는 오류를 표시하고 원본을 보존합니다.
                val moved = runCatching { android.provider.DocumentsContract.moveDocument(
                        context.contentResolver,
                        Uri.parse(file.path),
                        parent.uri,
                        target.uri,
                    ) }.getOrNull()
                if (moved != null) moved.toString() else {
                    // 공급자가 이동을 지원하지 않으면 복사 크기를 확인한 뒤에만 원본을 제거합니다.
                    val source = DocumentFile.fromSingleUri(context, Uri.parse(file.path))
                        ?: error(context.getString(R.string.loc_storage_unavailable))
                    val copy = target.createFile(source.type ?: "video/mp4", file.name)
                        ?: error(context.getString(R.string.loc_storage_move_unsupported))
                    try {
                        val bytes = context.contentResolver.openInputStream(source.uri)?.use { input ->
                            context.contentResolver.openOutputStream(copy.uri)?.use { output -> input.copyTo(output) }
                        } ?: error(context.getString(R.string.loc_storage_move_unsupported))
                        check(bytes > 0 && bytes == source.length() && copy.length() == bytes)
                        check(source.delete()) { context.getString(R.string.loc_file_delete_failed) }
                        copy.uri.toString()
                    } catch (error: Exception) {
                        copy.delete()
                        throw error
                    }
                }
            }
        PluginDatabaseUtil.updateMediaPath(file.path, newPath)
        return newPath
    }

    /** 사용자 분류 대상 폴더로 이동하며 중복 번호·SAF 검증·이력 갱신을 재사용합니다. */
    suspend fun moveToFolder(file: MediaFile, targetPath: String, targetDocument: DocumentFile? = null): String {
        val moved = if (file.parentUri == null) {
            val target = File(targetPath)
            check(target.isDirectory && !java.nio.file.Files.isSymbolicLink(target.toPath())) { "대상 폴더를 확인하세요." }
            moveClassifiedFile(File(file.path), File(target, file.name), false).absolutePath
        } else {
            val target = targetDocument
                ?.takeIf { it.isDirectory && it.canWrite() } ?: error("대상 폴더의 쓰기 권한이 필요합니다.")
            moveDocumentCollision(file, target, false)
        }
        // 이동은 확정됐으므로 중단 요청이 와도 이력 경로 갱신까지 완료합니다.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { PluginDatabaseUtil.updateMediaPath(file.path, moved) }
        if (file.parentUri == null) MediaScannerConnection.scanFile(context, arrayOf(file.path, moved), null, null)
        return moved
    }

    /** 로컬 중복 처리 뒤에만 파일과 이력을 교체하며 일반 다운로드의 충돌 거부 규칙은 유지합니다. */
    private fun moveClassifiedFile(source: File, destination: File, replace: Boolean?): File {
        var target = destination
        val collision = target.exists()
        if (collision) {
            check(replace != null) { context.getString(R.string.loc_target_name_conflict, target.name) }
            if (replace == false) target = target.resolveSibling(MediaRules.availableFileName(target.name) {
                target.resolveSibling(it).exists()
            })
            else check(target.isFile && !java.nio.file.Files.isSymbolicLink(target.toPath())) { "폴더나 연결된 파일은 대체할 수 없습니다." }
        }
        if (collision && replace == true) {
            try { java.nio.file.Files.move(source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            runBlocking { PluginDatabaseUtil.deleteInfoByPath(target.absolutePath) }
        } else java.nio.file.Files.move(source.toPath(), target.toPath())
        return target
    }

    /** SAF 중복은 먼저 복사 크기를 검증하고 교체 대상은 임시 이름으로 보존해 실패 시 복원합니다. */
    private suspend fun moveDocumentCollision(file: MediaFile, target: DocumentFile, replace: Boolean): String {
        val source = DocumentFile.fromSingleUri(context, Uri.parse(file.path)) ?: error("원본 파일이 없습니다.")
        val existing = target.findFile(file.name)
        check(!replace || existing == null || existing.isFile) { "같은 이름의 폴더는 대체할 수 없습니다." }
        val name = if (replace) file.name else MediaRules.availableFileName(file.name) { target.findFile(it) != null }
        val tempName = if (replace) file.name + "." + java.util.UUID.randomUUID() + ".stage" else name
        val copy = target.createFile(source.type ?: "application/octet-stream", tempName) ?: error("대상 파일을 만들 수 없습니다.")
        val overwrittenPath = existing?.uri?.toString()
        var backedUp = false
        var committed = false
        try {
            val bytes = context.contentResolver.openInputStream(source.uri)?.use { input ->
                context.contentResolver.openOutputStream(copy.uri)?.use { output -> input.copyTo(output) }
            } ?: error("파일을 복사할 수 없습니다.")
            check(bytes > 0 && bytes == source.length() && copy.length() == bytes) { "복사 파일 검증에 실패했습니다." }
            if (replace) {
                if (existing != null) {
                    check(existing.renameTo(file.name + "." + java.util.UUID.randomUUID() + ".backup")) { "기존 파일을 안전하게 보관할 수 없습니다." }
                    backedUp = true
                }
                check(copy.renameTo(name) && copy.name == name) { "대체 파일 이름을 적용할 수 없습니다." }
                committed = true
                if (existing != null) check(existing.delete()) { "기존 파일 정리에 실패했습니다." }
            } else committed = true
            check(source.delete()) { context.getString(R.string.loc_file_delete_failed) }
            if (replace && overwrittenPath != null) PluginDatabaseUtil.deleteInfoByPath(overwrittenPath)
            return copy.uri.toString()
        } catch (failure: Exception) {
            // 새 파일 이름 적용 전 실패하면 기존 대상을 되돌리고 원본을 유지합니다.
            if (!committed) {
                copy.delete()
                if (backedUp) check(existing?.renameTo(file.name) == true) { "기존 파일 복원에 실패했습니다: ${existing?.uri}" }
            }
            throw failure
        }
    }

    /** 문서 ID의 경로 형식을 추측하지 않고 실제 트리에서 부모 또는 화질 폴더의 상위를 찾습니다. */
    private fun classificationTreeBase(directory: DocumentFile, parentUri: Uri,
        ancestor: DocumentFile? = null): DocumentFile? {
        if (android.provider.DocumentsContract.getDocumentId(directory.uri) ==
            android.provider.DocumentsContract.getDocumentId(parentUri)) {
            return if (ancestor != null && MediaRules.isQualityFolder(directory.name.orEmpty())) ancestor else directory
        }
        // ponytail: 공급자별 불투명 ID를 지원하는 폴더 순회입니다. 대규모 SAF 스캔은 부모 인덱스로 개선합니다.
        for (child in directory.listFiles().filter { it.isDirectory && it.name != "tmp" && !MediaRules.isNoFacePath(it.name.orEmpty()) }) {
            classificationTreeBase(child, parentUri, directory)?.let { return it }
        }
        return null
    }

    /** 같은 링크의 동시 다운로드를 막되 서로 다른 링크는 병렬 처리합니다. */
    fun <T> withDownloadLock(key: String, block: () -> T): T {
        // ponytail: 사용한 식별자의 잠금을 유지합니다. 매우 큰 이력에서 메모리가 문제면 참조 수로 회수합니다.
        return downloadLocks.computeIfAbsent(key) { ReentrantLock() }.withLock(block)
    }

    /** 링크 이력과 Seal의 모든 하위 폴더에서 이미 받은 실제 파일을 찾습니다. */
    fun findExisting(info: VideoInfo, preferences: DownloadUtil.DownloadPreferences,
        fallbackNames: Set<String> = emptySet()): List<String> {
        val selected = preferences.selectedFolder?.also { DownloadFolders.validate(it, preferences) }
        val identity = MediaRules.identity(info.webpageUrl ?: info.originalUrl.orEmpty())
        val history = runBlocking { PluginDatabaseUtil.getDownloadHistory() }
        val recorded = history.filter { MediaRules.identity(it.videoUrl) == identity }
        val extensions =
            when {
                info.ext in setOf("jpg", "jpeg", "png", "webp", "avif") ->
                    setOf("jpg", "jpeg", "png", "webp", "avif")
                preferences.extractAudio || info.vcodec == "none" ->
                    setOf("mp3", "m4a", "opus", "aac", "flac", "ogg", "wav")
                else -> setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "ts")
            }
        // 음원이나 썸네일만 저장된 이력이 영상 다운로드를 막지 않도록 종류도 비교합니다.
        val existing =
            recorded
                .map { it.videoPath }
                .filter { path ->
                    exists(path) && (selected == null || DownloadFolders.contains(selected, path, preferences)) &&
                        (if (path.startsWith("content://"))
                                DocumentFile.fromSingleUri(context, Uri.parse(path))?.name.orEmpty()
                            else File(path).name)
                            .substringAfterLast('.')
                            .lowercase() in extensions
                }
        if (existing.isNotEmpty()) return existing
        val names = recorded.map { File(it.videoPath).name }.toSet() + fallbackNames
        val id = info.id.takeIf { it.isNotBlank() }
        val roots = if (selected != null) { if (preferences.sdcard) emptyList() else listOf(DownloadFolders.local(selected)) } else
            listOf(
                File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Seal"),
                File(videoDownloadDir),
                File(audioDownloadDir),
                File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), ".Seal"),
            )
        val found =
            roots
                .distinctBy { it.absolutePath }
                .flatMap { root ->
                    root
                        .walkTopDown()
                        .onEnter { it.name != "tmp" && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                        .filter { file ->
                            file.isFile &&
                                !file.isHidden &&
                                file.extension.lowercase() in extensions &&
                                (file.name in names ||
                                    (id != null &&
                                        file.nameWithoutExtension
                                            .removeSuffix("-av1")
                                            .endsWith("[$id]")))
                        }
                        .map { it.absolutePath }
                        .toList()
                }
        if (found.isNotEmpty() || !preferences.sdcard) return found.filter { selected == null || DownloadFolders.contains(selected, it, preferences) }.distinct()
        val tree =
            selected?.let { DownloadFolders.document(it) } ?: DocumentFile.fromTreeUri(context, Uri.parse(preferences.sdcardUri))
                ?: return emptyList()
        return findInTree(tree, names, id, extensions)
    }

    /** SAF 폴더를 재귀 조회해 이름 또는 미디어 ID가 같은 완성 파일을 찾습니다. */
    private fun findInTree(
        directory: DocumentFile,
        names: Set<String>,
        id: String?,
        extensions: Set<String>,
    ): List<String> =
        directory.listFiles().flatMap { item ->
            if (item.isDirectory) {
                findInTree(item, names, id, extensions)
            }
            else {
                val name = item.name.orEmpty()
                if (
                    name.substringAfterLast('.').lowercase() in extensions &&
                        (name in names ||
                            (id != null &&
                                name
                                    .substringBeforeLast('.')
                                    .removeSuffix("-av1")
                                    .endsWith("[$id]")))
                )
                    listOf(item.uri.toString())
                else emptyList()
            }
        }

    /** 일반 파일과 SAF URI의 존재 여부를 확인합니다. */
    private fun exists(path: String): Boolean =
        if (path.startsWith("content://"))
            DocumentFile.fromSingleUri(context, Uri.parse(path))?.exists() == true
        else File(path).isFile

    /** 완료 카드에 표시할 실제 파일 용량을 읽습니다. */
    fun fileBytes(path: String): Long = runCatching {
        if (path.startsWith("content://")) DocumentFile.fromSingleUri(context, Uri.parse(path))?.length() ?: 0
        else File(path).length()
    }.getOrDefault(0)

    /** 이름이 유지된 파일을 하위 폴더에서 재인식하며 같은 이름이 여럿이면 임의 선택하지 않습니다. */
    fun resolveDownloadedPath(path: String, preferences: DownloadUtil.DownloadPreferences): String? {
        if (exists(path)) return path
        val content = path.startsWith("content://")
        if (content && DocumentFile.fromTreeUri(context, Uri.parse(preferences.sdcardUri))?.canRead() != true) return path
        val roots = listOf(File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "Seal"), File(videoDownloadDir),
            File(audioDownloadDir), File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), ".Seal")).distinct()
        if (!content && roots.none { it.isDirectory && it.canRead() }) return path
        val name = if (content) Uri.parse(path).lastPathSegment?.substringAfterLast('/') ?: return path else File(path).name
        val history = runBlocking { PluginDatabaseUtil.getDownloadHistory() }
        val original = history.filter { it.extractor != "External" &&
            (if (it.videoPath.startsWith("content://")) Uri.parse(it.videoPath).lastPathSegment?.substringAfterLast('/')
            else File(it.videoPath).name) == name }
        // 등록·뷰어·재사용에서 같은 백업 연결 기준을 사용합니다.
        if (original.isNotEmpty()) {
            val source = MediaRules.reconnectionSource(name, original)
            // 이미 경로를 갱신한 이력은 이전 경로를 참조하는 완료 카드에서도 재사용합니다.
            if (source == null || (original.any { it.videoPath == path } && source != path)) return null
        }
        // 새 파일 탐색기를 만들지 않고 기존 중복 파일 검색의 이름·하위 폴더·SAF 처리를 재사용합니다.
        val found = findExisting(VideoInfo(id = "", title = name, webpageUrl = path, ext = name.substringAfterLast('.').lowercase()),
            preferences, setOf(name)).filter {
                (if (it.startsWith("content://")) DocumentFile.fromSingleUri(context, Uri.parse(it))?.name else File(it).name) == name
            }.distinct()
        return if (found.size == 1) found.single() else path.takeIf { found.isNotEmpty() }
    }

    /** 파일의 실제 재생 시간을 초 단위로 읽습니다. */
    fun durationSeconds(path: String): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            if (path.startsWith("content://")) retriever.setDataSource(context, Uri.parse(path))
            else retriever.setDataSource(path)
            retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()
                ?.div(1000) ?: 0.0
        } catch (_: Exception) {
            0.0
        } finally {
            retriever.release()
        }
    }

    /** 실제 영상 트랙의 해상도·프레임률·코덱을 읽으며 이미지·음원·읽기 실패는 제외합니다. */
    fun readVideoQuality(path: String): VideoQuality? {
        val extractor = MediaExtractor()
        return try {
            val content = path.startsWith("content://")
            if (content) extractor.setDataSource(context, Uri.parse(path), null)
            else extractor.setDataSource(path)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val format = tracks.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: tracks.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            // 트랙별 선택 메타데이터가 없어도 나머지 정보와 파일 저장은 유지합니다.
            val bytes = if (content) DocumentFile.fromSingleUri(context, Uri.parse(path))?.length() ?: 0
                else File(path).length()
            VideoQuality(
                width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0),
                height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0),
                frameRate = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                    .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrDefault(0.0) },
                duration = durationSeconds(path),
                bytes = bytes,
                codec = format.getString(MediaFormat.KEY_MIME).orEmpty(),
            )
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /** 이미지는 헤더만 해석하고 영상은 기존 플랫폼 메타데이터 함수를 재사용합니다. */
    fun readMediaQuality(path: String): VideoQuality? {
        val image = runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (path.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(path))?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            } else android.graphics.BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0)
                VideoQuality(width = bounds.outWidth, height = bounds.outHeight, bytes = fileBytes(path), codec = bounds.outMimeType.orEmpty())
            else null
        }.getOrNull()
        return image ?: readVideoQuality(path)
    }

    /** 준비 중이거나 재시도 대기 중인 작업도 취소하고 해당 작업의 프로세스만 종료합니다. */
    fun cancel(taskId: String): Boolean =
        operations[taskId]?.let {
            synchronized(it) { it.canceled = true; it.process?.destroy() }
            true
        } ?: false

    /** 다운로드한 영상에 AV1 변환과 선택한 폴더 분류를 적용합니다. */
    fun finish(
        paths: List<String>,
        preferences: DownloadUtil.DownloadPreferences,
        taskId: String,
        progress: ((Float, Long, String) -> Unit)? = null,
        classificationQuality: VideoQuality? = null,
        onOutput: (String, VideoQuality?) -> Unit = { _, _ -> },
        preserveParent: Boolean = false,
        mediaRoot: File? = null,
        collisionReplace: Boolean? = null,
    ): List<String> {
        if (!preferences.mediaProcessing) return paths
        val operation = Operation()
        check(operations.putIfAbsent(taskId, operation) == null) { "같은 미디어 작업이 이미 진행 중입니다." }
        return try { paths.flatMap { original ->
            if (operation.canceled) throw kotlinx.coroutines.CancellationException("미디어 작업이 취소되었습니다.")
            if (PluginMediaMetadataStore.isProcessingExcluded(original)) return@flatMap listOf(original)
            val source = File(original)
            val video = source.extension.lowercase() in setOf("mp4", "mkv", "webm", "mov", "m4v")
            val actualQuality = readMediaQuality(original)
            val originalQuality = classificationQuality ?: actualQuality
            var file =
                if (
                    video &&
                        preferences.mediaProcessing && preferences.transcodeAv1 &&
                        MediaRules.needsAv1Encoding(actualQuality?.codec.orEmpty()) &&
                        !source.nameWithoutExtension.endsWith("-av1")
                )
                    transcode(source, taskId, preferences.av1Crf, preferences.av1Preset, preferences.keepAv1Original,
                        if (preferences.mergeToMkv) "mkv" else MediaRules.videoContainer(preferences.videoContainer), progress)
                else source
            // 보존한 원본과 AV1 결과 모두 분류·저장·이력 처리 대상으로 반환합니다.
            val outputs = if (file != source && preferences.keepAv1Original) listOf(source, file)
                else listOf(file)
            outputs.map { output ->
                file = output
                val image = actualQuality?.codec?.startsWith("image/") == true
                val tier = if (preferences.classifyMedia) MediaRules.classificationFolder(originalQuality,
                    preferences.imageTiers, preferences.videoTiers, preferences.codecRatios, preferences.classificationTarget, preferences.classificationUpperFirst) else null
                if ((video || image) && (mediaRoot != null || preferences.classifyMedia || preserveParent)) {
                    val parent = file.absoluteFile.parentFile ?: error("파일의 상위 폴더가 없습니다.")
                    var base = if (preserveParent) {
                        MediaRules.classificationBase(parent, mediaRoot ?: File(rootPath(preferences)))
                    } else if (mediaRoot != null) {
                        parent.takeIf { it.canonicalFile.toPath().startsWith(mediaRoot.canonicalFile.toPath()) }?.let {
                            MediaRules.classificationBase(it, mediaRoot)
                        } ?: mediaRoot
                    } else if (MediaRules.isQualityFolder(parent.name)) (parent.parentFile ?: parent) else parent
                    if (!preserveParent && mediaRoot != null && preferences.selectedFolder == null) {
                        val name = if (image) MediaRules.optionalFolderName(preferences.imageFolderName, "Images")
                            else MediaRules.optionalFolderName(preferences.videoFolderName, "Videos")
                        if (name.isNotEmpty() && base.name != name) base = base.resolve(name)
                    }
                    if (tier != null) base = base.resolve(tier)
                    // 기존 화질 폴더가 심볼릭 링크여도 지정 루트 밖으로 파일을 이동하지 않습니다.
                    if (mediaRoot != null) require(base.canonicalFile.toPath().startsWith(mediaRoot.canonicalFile.toPath())) {
                        "다운로드 폴더 밖으로 파일을 이동할 수 없습니다."
                    }
                    val target = base.resolve(file.name)
                    if (target.canonicalFile != file.canonicalFile) {
                        check(base.isDirectory || base.mkdirs())
                        file = moveClassifiedFile(file, target, collisionReplace)
                    }
                }
                if (file.absolutePath != original) {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(original, file.absolutePath),
                        null,
                        null,
                    )
                }
                onOutput(file.absolutePath, originalQuality.takeIf { output != source })
                file.absolutePath
            }
        }.distinct() } finally { operations.remove(taskId, operation); operation.process?.destroy() }
    }

    /** 원본 SDR 크기를 지원하는 하드웨어 디코더·CQ 인코더의 기존 FFmpeg 인수를 준비합니다. */
    private fun hardwareArguments(source: File, crf: Int): Pair<List<String>, List<String>?> {
        if (android.os.Build.VERSION.SDK_INT < 29 || !ffmpegHardwareAvailable) return emptyList<String>() to null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.path)
            val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                .first { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
            // HDR 색 공간과 품질 제약을 임의로 낮추지 않고 기존 소프트웨어 경로를 유지합니다.
            if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD) && format.getInteger(MediaFormat.KEY_COLOR_STANDARD) == 6 ||
                format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) && format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) in listOf(6, 7)) return emptyList<String>() to null
            val width = format.getInteger(MediaFormat.KEY_WIDTH); val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() ?: 30.0 else 30.0
            val codecs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            /** 드라이버가 해당 MIME·크기·프레임률을 지원할 때만 하드웨어를 요청합니다. */
            fun supports(info: android.media.MediaCodecInfo, mime: String): Boolean = info.isHardwareAccelerated &&
                mime in info.supportedTypes && runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities.areSizeAndRateSupported(width, height, fps)
                }.getOrDefault(false)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val decoderType = when (mime) {
                "video/avc" -> "h264"; "video/hevc" -> "hevc"; "video/av01" -> "av1"
                "video/x-vnd.on2.vp8" -> "vp8"; "video/x-vnd.on2.vp9" -> "vp9"; "video/mp4v-es" -> "mpeg4"
                else -> null
            }
            val decoder = codecs.firstOrNull { !it.isEncoder && supports(it, mime) }
            // codec_name은 FFmpeg 인코더 전용입니다. 독립 프로세스에서는 NDK 디코더 경로를 명시합니다.
            val decode = if (decoderType != null && decoder != null) listOf("-c:v", "${decoderType}_mediacodec", "-ndk_codec", "1") else emptyList()
            val encoder = codecs.firstOrNull { it.isEncoder && supports(it, "video/av01") && runCatching {
                it.getCapabilitiesForType("video/av01").encoderCapabilities.isBitrateModeSupported(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
            }.getOrDefault(false) }
            val encode = encoder?.let {
                val range = it.getCapabilitiesForType("video/av01").encoderCapabilities.qualityRange
                val quality = MediaRules.hardwareQuality(crf, range.lower, range.upper)
                if (quality <= 0) null else listOf("-c:v", "av1_mediacodec", "-ndk_codec", "1", "-codec_name", it.name, "-bitrate_mode", "cq", "-global_quality", quality.toString())
            }
            decode to encode
        } catch (failure: Exception) {
            android.util.Log.w("MediaLibrary", "하드웨어 코덱 확인 실패로 기존 변환 사용", failure)
            emptyList<String>() to null
        } finally { extractor.release() }
    }

    /** 하드웨어를 먼저 시도하되 실패·취소·결과 검증 전에 원본을 교체하지 않습니다. */
    private fun transcode(
        source: File,
        taskId: String,
        crf: Int,
        preset: Int,
        keepOriginal: Boolean,
        container: String,
        progress: ((Float, Long, String) -> Unit)?,
    ): File {
        val operation = checkNotNull(operations[taskId])
        val destination = source.resolveSibling(source.nameWithoutExtension + "-av1.$container")
        check(!destination.exists()) { com.junkfood.seal.App.context.getString(R.string.loc_av1_target_exists_named, destination.name) }
        val temporary =
            source.resolveSibling(".${source.nameWithoutExtension}-${taskId.hashCode()}.av1.$container")
        check(!temporary.exists()) { com.junkfood.seal.App.context.getString(R.string.loc_av1_temporary_exists) }
        val executable = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val (decode, encode) = hardwareArguments(source, crf)
        val software = listOf("-c:v", "libsvtav1", "-preset", MediaRules.av1Preset(preset).toString(), "-crf", crf.coerceIn(20, 50).toString())
        val inputs = listOf("-i", source.absolutePath)
        val plans = buildList {
            if (encode != null) add(decode + inputs + encode)
            if (decode.isNotEmpty()) add(decode + inputs + software)
            add(inputs + software)
        }.distinct()
        val outputArguments = listOf(
                    "-map",
                    "0:v:0",
                    "-map",
                    "0:a?",
                    "-map",
                    "0:s?",
                    "-map_metadata",
                    "0",
                    "-c:a",
                    "copy",
                    "-c:s",
                    if (container == "mp4") "mov_text" else "copy",
                    "-progress",
                    "pipe:1",
                    temporary.absolutePath,
                )
        var process: Process? = null
        val duration = durationSeconds(source.absolutePath)
        val messages = ArrayDeque<String>()
        try {
        for (plan in plans) {
            if (!ffmpegHardwareAvailable && plan.any { it.endsWith("_mediacodec") }) continue
            if (operation.canceled) throw kotlinx.coroutines.CancellationException(context.getString(R.string.loc_av1_canceled))
            val running = ProcessBuilder(listOf(executable.absolutePath, "-y", "-nostdin") + plan + outputArguments)
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] =
                        listOf("ffmpeg", "python", "aria2c").joinToString(":") {
                            packages.resolve("$it/usr/lib").absolutePath
                        }
                }
                .start()
            process?.destroy(); process = running; operation.process = running
            // 이전 시도가 끝난 순간 취소가 들어와도 다음 시도까지 실행하지 않습니다.
            if (operation.canceled) throw kotlinx.coroutines.CancellationException(context.getString(R.string.loc_av1_canceled))
            android.util.Log.i("MediaLibrary", "AV1 변환 경로: ${plan.joinToString(" ") { if (it == source.absolutePath) "원본" else it }}")
            running.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    messages.addLast(line)
                    if (messages.size > 30) messages.removeFirst()
                    if (line.startsWith("out_time_us=") && duration > 0) {
                        val seconds =
                            line.substringAfter('=').toDoubleOrNull()?.div(1_000_000) ?: 0.0
                        progress?.invoke(
                            (seconds / duration * 100).toFloat().coerceIn(0f, 100f),
                            0,
                            com.junkfood.seal.App.context.getString(R.string.loc_av1_converting),
                        )
                    }
                }
            }
            val exit = running.waitFor()
            if (operation.canceled) throw kotlinx.coroutines.CancellationException(context.getString(R.string.loc_av1_canceled))
            val valid = exit == 0 && temporary.length() > 0 && duration > 0 &&
                kotlin.math.abs(durationSeconds(temporary.absolutePath) - duration) <= 2.0
            if (valid) break
            if (messages.any { "Failed to create media format" in it || "No Java virtual machine" in it }) ffmpegHardwareAvailable = false
            android.util.Log.w("MediaLibrary", "AV1 경로 실패·소프트웨어 재시도: ${messages.takeLast(6).joinToString("\n")}")
            check(plan != plans.last()) {
                context.getString(R.string.loc_av1_failed, messages.joinToString("\n"))
            }
        }
            val convertedDuration = durationSeconds(temporary.absolutePath)
            // 검증 후 이름 교환·원본 보존 선택은 취소와 엇갈리지 않는 하나의 확정 단계입니다.
            return synchronized(operation) {
                if (operation.canceled) throw kotlinx.coroutines.CancellationException(context.getString(R.string.loc_av1_canceled))
                MediaRules.replaceWithAv1(
                source,
                temporary,
                destination,
                duration,
                convertedDuration,
                operation.canceled,
                keepOriginal,
                failureMessage = { reason ->
                    context.getString(when (reason) {
                        MediaRules.Av1Failure.CANCELED -> R.string.loc_av1_canceled
                        MediaRules.Av1Failure.OUTPUT_INVALID -> R.string.loc_av1_output_invalid
                        MediaRules.Av1Failure.DURATION_INVALID -> R.string.loc_av1_duration_invalid
                        MediaRules.Av1Failure.TARGET_EXISTS -> R.string.loc_av1_target_exists
                        MediaRules.Av1Failure.SAVE_FAILED -> R.string.loc_av1_save_failed
                        MediaRules.Av1Failure.SOURCE_DELETE_FAILED -> R.string.loc_av1_source_delete_failed
                    })
                },
            ) }
        } finally {
            process?.destroy()
            if (temporary.exists()) temporary.delete()
        }
    }
}
