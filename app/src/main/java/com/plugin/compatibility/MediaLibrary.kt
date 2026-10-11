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

/** 다운로드 중복 확인, 비트레이트 폴더 분류와 AV1 후처리를 담당합니다. */
object MediaLibrary {
    private val downloadLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val canceled = ConcurrentHashMap.newKeySet<String>()

    /** 파일 관리자에 표시할 실제 영상과 부모 폴더 정보입니다. */
    data class MediaFile(
        val path: String,
        val name: String,
        val bytes: Long,
        val duration: Double,
        val parentUri: String? = null,
        val quality: VideoQuality? = null,
        val sourceQuality: VideoQuality? = null,
    )

    /** Seal과 지정한 다운로드 폴더의 모든 하위 폴더에서 영상을 읽습니다. */
    fun listVideos(preferences: DownloadUtil.DownloadPreferences): List<MediaFile> {
        val extensions = setOf("mp4", "mkv", "webm", "mov", "m4v", "jpg", "jpeg", "png", "webp", "avif", "gif")
        val roots = listOf(FileUtil.getExternalDownloadDirectory(), File(videoDownloadDir))
        val files =
            roots
                .flatMap { root ->
                    root
                        .walkTopDown()
                        .onEnter { it.name != "tmp" }
                        .filter {
                            it.isFile && !it.isHidden && it.extension.lowercase() in extensions
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
            DocumentFile.fromTreeUri(context, Uri.parse(preferences.sdcardUri))?.let { tree ->
                files += listTreeVideos(tree, extensions)
            }
        val history = runBlocking { PluginDatabaseUtil.getDownloadHistory() }.associateBy { it.videoPath }
        return files.map { file ->
            val saved = history[file.path]
            val quality = saved?.quality ?: readMediaQuality(file.path)
            // 이전 버전 이력은 파일 관리자에서 처음 읽을 때만 화질 정보를 보충합니다.
            if (saved != null && saved.quality == null && quality != null)
                PluginMediaMetadataStore.save(saved.videoPath, quality = quality)
            file.copy(duration = quality?.duration ?: file.duration,
                quality = quality, sourceQuality = saved?.sourceQuality)
        }.sortedBy { it.name.lowercase() }
    }

    /** SAF 폴더에서도 이동에 필요한 부모 URI를 함께 보관합니다. */
    private fun listTreeVideos(parent: DocumentFile, extensions: Set<String>): List<MediaFile> =
        parent.listFiles().flatMap { file ->
            if (file.isDirectory) listTreeVideos(file, extensions)
            else if (file.name.orEmpty().substringAfterLast('.').lowercase() in extensions)
                listOf(
                    MediaFile(
                        file.uri.toString(),
                        file.name.orEmpty(),
                        file.length(),
                        0.0,
                        parent.uri.toString(),
                    )
                )
            else emptyList()
        }

    /** 기존 파일을 덮어쓰지 않고 화질 폴더로 이동하며 이력도 갱신합니다. */
    suspend fun classify(file: MediaFile, preferences: DownloadUtil.DownloadPreferences): String {
        val classificationQuality = file.sourceQuality ?: file.quality
        val group = MediaRules.classificationFolder(classificationQuality,
            preferences.imageTiers, preferences.videoTiers, preferences.codecRatios)
        val newPath =
            if (file.parentUri == null)
                finish(
                        listOf(file.path),
                        preferences.copyPluginOptions(classifyMedia = true, transcodeAv1 = false),
                        "file-manager",
                        classificationQuality = classificationQuality,
                        preserveParent = true,
                    )
                    .single()
            else {
                val tree =
                    DocumentFile.fromTreeUri(context, Uri.parse(preferences.sdcardUri))
                        ?: error(com.junkfood.seal.App.context.getString(R.string.loc_storage_unavailable))
                val parent = findDirectory(tree, file.parentUri) ?: error(com.junkfood.seal.App.context.getString(R.string.loc_parent_folder_unavailable))
                val base = if (parent.name.orEmpty().matches(Regex("[0-9]+(p|x[0-9]+)"))) parent.parentFile ?: parent else parent
                // 비활성 구간의 기존 파일도 기본 폴더로 돌리며 같은 위치면 이동하지 않습니다.
                val target = if (group == null) base else
                    base.findFile(group)?.also { check(it.isDirectory) }
                        ?: base.createDirectory(group)
                        ?: error(com.junkfood.seal.App.context.getString(R.string.loc_quality_folder_create_failed))
                if (target.uri == parent.uri) return file.path
                check(target.findFile(file.name) == null) { com.junkfood.seal.App.context.getString(R.string.loc_target_file_exists) }
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

    /** 루트 접근 권한을 유지하며 이동할 실제 하위 폴더를 찾습니다. */
    private fun findDirectory(parent: DocumentFile, uri: String): DocumentFile? {
        if (parent.uri.toString() == uri) return parent
        return parent
            .listFiles()
            .filter { it.isDirectory }
            .firstNotNullOfOrNull { findDirectory(it, uri) }
    }

    /** 같은 링크의 동시 다운로드를 막되 서로 다른 링크는 병렬 처리합니다. */
    fun <T> withDownloadLock(key: String, block: () -> T): T {
        // ponytail: 사용한 식별자의 잠금을 유지합니다. 매우 큰 이력에서 메모리가 문제면 참조 수로 회수합니다.
        return downloadLocks.computeIfAbsent(key) { ReentrantLock() }.withLock(block)
    }

    /** 링크 이력과 Seal의 모든 하위 폴더에서 이미 받은 실제 파일을 찾습니다. */
    fun findExisting(info: VideoInfo, preferences: DownloadUtil.DownloadPreferences,
        fallbackNames: Set<String> = emptySet()): List<String> {
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
                    exists(path) &&
                        (if (path.startsWith("content://"))
                                DocumentFile.fromSingleUri(context, Uri.parse(path))?.name.orEmpty()
                            else File(path).name)
                            .substringAfterLast('.')
                            .lowercase() in extensions
                }
        if (existing.isNotEmpty()) return existing
        val names = recorded.map { File(it.videoPath).name }.toSet() + fallbackNames
        val id = info.id.takeIf { it.isNotBlank() }
        val roots =
            listOf(
                FileUtil.getExternalDownloadDirectory(),
                File(videoDownloadDir),
                File(audioDownloadDir),
                File(com.junkfood.seal.App.privateDownloadDir),
            )
        val found =
            roots
                .distinctBy { it.absolutePath }
                .flatMap { root ->
                    root
                        .walkTopDown()
                        .onEnter { it.name != "tmp" }
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
        if (found.isNotEmpty() || !preferences.sdcard) return found.distinct()
        val tree =
            DocumentFile.fromTreeUri(context, Uri.parse(preferences.sdcardUri))
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
            if (item.isDirectory) findInTree(item, names, id, extensions)
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
        val roots = listOf(FileUtil.getExternalDownloadDirectory(), File(videoDownloadDir),
            File(audioDownloadDir), File(com.junkfood.seal.App.privateDownloadDir)).distinct()
        if (!content && roots.none { it.isDirectory && it.canRead() }) return path
        val name = if (content) Uri.parse(path).lastPathSegment?.substringAfterLast('/') ?: return path else File(path).name
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

    /** FFmpeg가 실행 중인 후처리를 취소합니다. */
    fun cancel(taskId: String): Boolean =
        processes[taskId]?.let {
            canceled.add(taskId)
            it.destroy()
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
        platform: String = "",
    ): List<String> =
        if (!preferences.mediaProcessing) paths else paths.flatMap { original ->
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
                    preferences.imageTiers, preferences.videoTiers, preferences.codecRatios) else null
                val organizeFolders = (if (image) preferences.imageFolderName else preferences.videoFolderName).isNotBlank()
                if ((video || image) && ((mediaRoot != null && organizeFolders) || preferences.classifyMedia)) {
                    val parent = file.parentFile!!
                    var base = if (mediaRoot != null && organizeFolders) {
                        val platformName = MediaRules.folderName(platform.substringBefore(':'), "Unknown")
                        (if (preferences.platformFolders) mediaRoot.resolve(platformName) else mediaRoot)
                            .resolve(if (image) MediaRules.folderName(preferences.imageFolderName, "Images")
                                else MediaRules.folderName(preferences.videoFolderName, "Videos"))
                    } else if (parent.name.matches(Regex("[0-9]+(p|x[0-9]+)"))) parent.parentFile!! else parent
                    if (tier != null) base = base.resolve(tier)
                    val target = base.resolve(file.name)
                    if (target.canonicalFile != file.canonicalFile) {
                        check(base.isDirectory || base.mkdirs())
                        check(!target.exists()) { context.getString(R.string.loc_target_name_conflict, target.name) }
                        check(file.renameTo(target)) { context.getString(R.string.loc_quality_move_failed) }
                        file = target
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
        }.distinct()

    /** AV1 결과 검증 후 원본 보존 선택을 적용하며 실패 시 원본을 유지합니다. */
    private fun transcode(
        source: File,
        taskId: String,
        crf: Int,
        preset: Int,
        keepOriginal: Boolean,
        container: String,
        progress: ((Float, Long, String) -> Unit)?,
    ): File {
        val destination = source.resolveSibling(source.nameWithoutExtension + "-av1.$container")
        check(!destination.exists()) { com.junkfood.seal.App.context.getString(R.string.loc_av1_target_exists_named, destination.name) }
        val temporary =
            source.resolveSibling(".${source.nameWithoutExtension}-${taskId.hashCode()}.av1.$container")
        check(!temporary.exists()) { com.junkfood.seal.App.context.getString(R.string.loc_av1_temporary_exists) }
        val executable = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        val process =
            ProcessBuilder(
                    executable.absolutePath,
                    "-nostdin",
                    "-i",
                    source.absolutePath,
                    "-map",
                    "0:v:0",
                    "-map",
                    "0:a?",
                    "-map",
                    "0:s?",
                    "-map_metadata",
                    "0",
                    "-c:v",
                    "libsvtav1",
                    "-preset",
                    MediaRules.av1Preset(preset).toString(),
                    "-crf",
                    crf.coerceIn(20, 50).toString(),
                    "-c:a",
                    "copy",
                    "-c:s",
                    if (container == "mp4") "mov_text" else "copy",
                    "-progress",
                    "pipe:1",
                    temporary.absolutePath,
                )
                .redirectErrorStream(true)
                .apply {
                    environment()["LD_LIBRARY_PATH"] =
                        listOf("ffmpeg", "python", "aria2c").joinToString(":") {
                            packages.resolve("$it/usr/lib").absolutePath
                        }
                }
                .start()
        processes[taskId] = process
        val duration = durationSeconds(source.absolutePath)
        val messages = ArrayDeque<String>()
        try {
            process.inputStream.bufferedReader().useLines { lines ->
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
            check(process.waitFor() == 0 && temporary.length() > 0) {
                context.getString(R.string.loc_av1_failed, messages.joinToString("\n"))
            }
            val convertedDuration = durationSeconds(temporary.absolutePath)
            return MediaRules.replaceWithAv1(
                source,
                temporary,
                destination,
                duration,
                convertedDuration,
                taskId in canceled,
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
            )
        } finally {
            processes.remove(taskId, process)
            canceled.remove(taskId)
            process.destroy()
            if (temporary.exists()) temporary.delete()
        }
    }
}
