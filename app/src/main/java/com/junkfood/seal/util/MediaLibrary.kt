package com.junkfood.seal.util

import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.videoDownloadDir
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
    )

    /** Seal과 지정한 다운로드 폴더의 모든 하위 폴더에서 영상을 읽습니다. */
    fun listVideos(preferences: DownloadUtil.DownloadPreferences): List<MediaFile> {
        val extensions = setOf("mp4", "mkv", "webm", "mov", "m4v")
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
                                durationSeconds(it.absolutePath),
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
        return files.sortedBy { it.name.lowercase() }
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
                        durationSeconds(file.uri.toString()),
                        parent.uri.toString(),
                    )
                )
            else emptyList()
        }

    /** 기존 파일을 덮어쓰지 않고 화질 폴더로 이동하며 이력도 갱신합니다. */
    suspend fun classify(file: MediaFile, preferences: DownloadUtil.DownloadPreferences): String {
        val group =
            MediaRules.qualityFolder(
                file.bytes,
                file.duration,
                preferences.qualityThresholdMbps.toDouble(),
            )
        val newPath =
            if (file.parentUri == null)
                finish(
                        listOf(file.path),
                        preferences.copy(classifyMedia = true, transcodeAv1 = false),
                        "file-manager",
                    )
                    .single()
            else {
                val tree =
                    DocumentFile.fromTreeUri(context, Uri.parse(preferences.sdcardUri))
                        ?: error("저장 폴더에 접근하지 못했습니다.")
                val parent = findDirectory(tree, file.parentUri) ?: error("부모 폴더에 접근하지 못했습니다.")
                if (parent.name == group) return file.path
                val base =
                    if (parent.name in setOf("고화질", "저화질", "미분류")) parent.parentFile ?: parent
                    else parent
                val target =
                    base.findFile(group)?.also { check(it.isDirectory) }
                        ?: base.createDirectory(group)
                        ?: error("분류 폴더를 만들지 못했습니다.")
                check(target.findFile(file.name) == null) { "대상 폴더에 같은 파일이 있습니다." }
                // 이동을 지원하지 않는 공급자는 오류를 표시하고 원본을 보존합니다.
                android.provider.DocumentsContract.moveDocument(
                        context.contentResolver,
                        Uri.parse(file.path),
                        parent.uri,
                        target.uri,
                    )
                    ?.toString() ?: error("이 저장소는 파일 이동을 지원하지 않습니다.")
            }
        DatabaseUtil.updateMediaPath(file.path, newPath)
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
    fun findExisting(info: VideoInfo, preferences: DownloadUtil.DownloadPreferences): List<String> {
        val identity = MediaRules.identity(info.webpageUrl ?: info.originalUrl.orEmpty())
        val history = runBlocking { DatabaseUtil.getDownloadHistory() }
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
        val names = recorded.map { File(it.videoPath).name }.toSet()
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
    ): List<String> =
        paths.map { original ->
            val source = File(original)
            val video = source.extension.lowercase() in setOf("mp4", "mkv", "webm", "mov", "m4v")
            var file =
                if (
                    video &&
                        preferences.transcodeAv1 &&
                        !source.nameWithoutExtension.endsWith("-av1")
                )
                    transcode(source, taskId, preferences.av1Crf, progress)
                else source
            if (video && preferences.classifyMedia) {
                val group =
                    MediaRules.qualityFolder(
                        file.length(),
                        durationSeconds(file.absolutePath),
                        preferences.qualityThresholdMbps.toDouble(),
                    )
                if (file.parentFile?.name != group) {
                    val parent = file.parentFile!!
                    val base =
                        if (parent.name in setOf("고화질", "저화질", "미분류")) parent.parentFile!!
                        else parent
                    val target =
                        base
                            .resolve(group)
                            .apply { check(isDirectory || mkdirs()) }
                            .resolve(file.name)
                    check(!target.exists()) { "대상 폴더에 같은 이름의 파일이 있습니다: ${target.name}" }
                    check(file.renameTo(target)) { "파일을 분류 폴더로 이동하지 못했습니다." }
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
            file.absolutePath
        }

    /** 임시 AV1 파일을 검증한 다음에만 원본을 대체합니다. 실패하면 원본을 보존합니다. */
    private fun transcode(
        source: File,
        taskId: String,
        crf: Int,
        progress: ((Float, Long, String) -> Unit)?,
    ): File {
        val destination = source.resolveSibling(source.nameWithoutExtension + "-av1.mkv")
        check(!destination.exists()) { "AV1 대상 파일이 이미 존재합니다: ${destination.name}" }
        val temporary =
            source.resolveSibling(".${source.nameWithoutExtension}-${taskId.hashCode()}.av1.mkv")
        check(!temporary.exists()) { "이전 AV1 임시 파일이 남아 있습니다." }
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
                    "8",
                    "-crf",
                    crf.coerceIn(20, 50).toString(),
                    "-c:a",
                    "copy",
                    "-c:s",
                    "copy",
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
                            "AV1 변환 중",
                        )
                    }
                }
            }
            check(process.waitFor() == 0 && temporary.length() > 0) {
                "AV1 변환 실패·취소. 원본을 보존했습니다.\n${messages.joinToString("\n")}"
            }
            val convertedDuration = durationSeconds(temporary.absolutePath)
            return MediaRules.replaceWithAv1(
                source,
                temporary,
                destination,
                duration,
                convertedDuration,
                taskId in canceled,
            )
        } finally {
            processes.remove(taskId, process)
            canceled.remove(taskId)
            process.destroy()
            if (temporary.exists()) temporary.delete()
        }
    }
}
