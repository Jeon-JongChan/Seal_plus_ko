package com.plugin.compatibility

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.tencent.mmkv.MMKV
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 등록 당시 기본 디렉터리와 그 안의 상대 경로를 함께 저장합니다. */
@Serializable
data class DownloadFolder(val root: String, val relative: String = "") {
    /** 같은 이름의 하위 폴더도 상위 경로로 구분합니다. */
    val label: String get() = relative.ifEmpty { rootLabel.substringAfterLast('/').ifEmpty { rootLabel } }
    /** 내부 문서 URI 대신 사용자가 고른 기본 디렉터리 이름을 표시합니다. */
    val rootLabel: String get() = if (root.startsWith("content://"))
        runCatching { java.net.URLDecoder.decode(root.substringAfterLast('/').replace("+", "%2B"), "UTF-8") }.getOrDefault(root)
    else root
    /** 루트가 중첩되어도 다른 등록 항목의 UI·작업 식별자가 충돌하지 않게 합니다. */
    val key: String get() = "${root.length}:$root$relative"
}

/** 기본 디렉터리 경계·화질 폴더 제외·등록 목록 저장을 모든 다운로드 경로에서 공유합니다. */
object DownloadFolders {
    internal const val QUICK_KEY = "plugin_download_quick_folder"
    private const val KEY = "plugin_download_folders"
    private val json = Json { ignoreUnknownKeys = true }

    /** 기본 디렉터리가 바뀌면 이전 루트의 등록 항목을 새 루트에 잘못 연결하지 않습니다. */
    fun roots(preferences: DownloadPreferences): List<String> = when {
        preferences.sdcard -> listOf(preferences.sdcardUri)
        preferences.privateDirectory -> listOf(App.privateDownloadDir)
        else -> listOf(App.videoDownloadDir, App.audioDownloadDir).distinct()
    }.filter(String::isNotBlank)

    /** 기본 루트는 허용하고 모든 경로 성분의 이동·화질 폴더 진입은 차단합니다. */
    internal fun validRelative(path: String): Boolean = path.isEmpty() ||
        path.length <= 4096 && path.split('/').all { it.isNotEmpty() && it !in setOf(".", "..", "tmp") &&
            !it.contains('\\') && !it.any(Char::isISOControl) && !MediaRules.isQualityFolder(it) && !MediaRules.isNoFacePath(it) }

    /** 정규 경로와 각 하위 링크를 검사해 지정 루트 밖으로 나가는 경로를 거부합니다. */
    internal fun local(folder: DownloadFolder): File {
        require(validRelative(folder.relative)) { "화질 폴더 또는 잘못된 폴더 경로입니다." }
        val boundary = File(folder.root).canonicalFile
        var current = File(folder.root)
        folder.relative.split('/').filter(String::isNotEmpty).forEach { part ->
            current = current.resolve(part)
            require(!java.nio.file.Files.isSymbolicLink(current.toPath())) { "연결된 폴더는 선택할 수 없습니다." }
        }
        require(current.canonicalFile.toPath().startsWith(boundary.toPath())) { "기본 디렉터리 밖의 폴더입니다." }
        check(current.isDirectory && current.canRead() && current.canWrite()) { "폴더 접근 권한이 필요하거나 폴더가 없습니다." }
        return current
    }

    /** SAF의 저장된 기본 트리 권한 안에서만 실제 하위 문서를 찾습니다. */
    internal fun document(folder: DownloadFolder): DocumentFile {
        require(validRelative(folder.relative)) { "잘못된 폴더 경로입니다." }
        var current = DocumentFile.fromTreeUri(App.context, Uri.parse(folder.root)) ?: error("기본 폴더 접근 권한이 없습니다.")
        folder.relative.split('/').filter(String::isNotEmpty).forEach { part ->
            current = current.findFile(part) ?: error("등록한 하위 폴더가 없습니다.")
        }
        check(current.isDirectory && current.canRead() && current.canWrite()) { "폴더 접근 권한이 필요합니다." }
        return current
    }

    /** UI·다운로드 양쪽에서 현재 기본 디렉터리와 실제 폴더를 다시 검증합니다. */
    fun validate(folder: DownloadFolder, preferences: DownloadPreferences): DownloadFolder {
        require(!MediaRules.isNoFacePath(folder.root)) { "NOFACE 폴더는 플러그인 대상으로 선택할 수 없습니다." }
        require(folder.root in roots(preferences)) { "기본 디렉터리가 변경되었습니다. 저장폴더를 다시 선택하세요." }
        if (folder.root.startsWith("content://")) document(folder) else local(folder)
        return folder
    }

    /** 한 단계씩 탐색하며 화질 폴더와 링크는 목록에서도 숨깁니다. */
    fun children(folder: DownloadFolder, preferences: DownloadPreferences): List<DownloadFolder> {
        validate(folder, preferences)
        val names = if (folder.root.startsWith("content://")) document(folder).listFiles().filter { it.isDirectory }.mapNotNull { it.name }
        else checkNotNull(local(folder).listFiles()) { "폴더를 읽을 권한이 없습니다." }.filter { it.isDirectory }.map { it.name }
        return names.map { folder.copy(relative = listOf(folder.relative, it).filter(String::isNotEmpty).joinToString("/")) }
            .filter { validRelative(it.relative) && runCatching { validate(it, preferences) }.isSuccess }.sortedBy { it.relative.lowercase() }
    }

    /** 읽기 실패 시 임의의 빈 목록으로 저장하지 않고 기존 값을 보존합니다. */
    fun registered(): List<DownloadFolder> = MMKV.defaultMMKV().decodeString(KEY)?.let {
        json.decodeFromString<List<DownloadFolder>>(it).filter { folder -> validRelative(folder.relative) }.distinct()
    }.orEmpty()

    /** 등록한 한 폴더만 퀵 대상으로 읽으며 등록이 사라진 오래된 키는 사용하지 않습니다. */
    fun quick(): DownloadFolder? = registered().firstOrNull { it.key == MMKV.defaultMMKV().decodeString(QUICK_KEY) }

    /** 퀵 지정은 한 항목만 저장하며 해제하면 다운로드창에서 일반 선택 목록을 사용합니다. */
    fun setQuick(folder: DownloadFolder?) {
        require(folder == null || folder in registered()) { "등록한 폴더만 퀵으로 지정할 수 있습니다." }
        check(MMKV.defaultMMKV().encode(QUICK_KEY, folder?.key.orEmpty())) { "퀵 폴더를 저장하지 못했습니다." }
    }

    /** 등록 해제는 설정 목록만 바꾸며 실제 폴더·파일을 삭제하지 않습니다. */
    fun save(folders: List<DownloadFolder>) {
        require(folders.all { validRelative(it.relative) })
        check(MMKV.defaultMMKV().encode(KEY, json.encodeToString(folders.distinct()))) { "폴더 목록을 저장하지 못했습니다." }
        // 목록에서 제외한 폴더가 퀵 대상으로 남아 다시 적용되지 않게 합니다.
        val quickKey = MMKV.defaultMMKV().decodeString(QUICK_KEY)
        if (quickKey != null && folders.none { it.key == quickKey }) setQuick(null)
    }

    /** 같은 게시글이라도 선택 폴더 밖 파일은 재사용 결과에 포함하지 않습니다. */
    fun contains(folder: DownloadFolder, path: String, preferences: DownloadPreferences): Boolean {
        validate(folder, preferences)
        return if (folder.root.startsWith("content://")) runCatching {
            val candidate = Uri.parse(path)
            val parent = document(folder).uri
            candidate.authority == parent.authority && android.provider.DocumentsContract.getDocumentId(candidate)
                .startsWith(android.provider.DocumentsContract.getDocumentId(parent).trimEnd('/') + "/")
        }.getOrDefault(false)
        else !path.startsWith("content://") && File(path).canonicalFile.toPath().startsWith(local(folder).canonicalFile.toPath())
    }

    /** 로컬 출력 또는 SAF 임시 루트 안에 명시적으로 선택한 폴더를 연결합니다. */
    fun outputRoot(preferences: DownloadPreferences, defaultRoot: File): File {
        val selected = preferences.selectedFolder ?: return defaultRoot
        validate(selected, preferences)
        return if (preferences.sdcard) defaultRoot.resolve(selected.relative) else local(selected)
    }
}
