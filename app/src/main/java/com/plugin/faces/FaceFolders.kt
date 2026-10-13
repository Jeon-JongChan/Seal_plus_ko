package com.plugin.faces

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.MediaLibrary
import com.plugin.compatibility.videoFolderName
import java.io.File
import java.nio.file.Files

/** 이미지 탐색 위치와 화질 폴더를 제외한 이동 기준 폴더를 함께 유지합니다. */
object FaceFolders {
    /** 현재 파일 경로와 이동 시점에만 NOFACE를 만들 부모 위치입니다. */
    data class Candidate(val file: MediaLibrary.MediaFile, val destinationParent: String,
        val documentParent: DocumentFile? = null, val root: String = "")

    /** 목록은 비트맵을 포함하지 않으며 NOFACE·영상·링크를 순회하기 전에 제외합니다. */
    fun candidates(context: Context, preferences: DownloadPreferences, perFolder: Boolean): Sequence<Candidate> = sequence {
        val rootPath = MediaLibrary.rootPath(preferences)
        if (preferences.sdcard) {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(rootPath)) ?: error("저장 폴더를 열 수 없습니다.")
            check(root.canRead() && root.canWrite()) { "저장 폴더의 읽기·쓰기 권한이 필요합니다." }
            yieldAll(documents(root, root, preferences.videoFolderName, perFolder, 0))
        } else {
            val root = File(rootPath)
            check(root.isDirectory && root.canRead() && root.canWrite()) { "저장 폴더의 읽기·쓰기 권한이 필요합니다: $rootPath" }
            check(!Files.isSymbolicLink(root.toPath())) { "연결된 저장 폴더는 스캔할 수 없습니다." }
            yieldAll(local(root, root, root.canonicalFile, preferences.videoFolderName, perFolder, 0))
        }
    }

    /** 일반 폴더는 새 기준으로 삼고 화질 폴더는 직전 일반 폴더를 상속합니다. */
    private fun local(directory: File, base: File, boundary: File, videoFolder: String,
        perFolder: Boolean, depth: Int): Sequence<Candidate> = sequence {
        check(depth <= 128) { "폴더 깊이가 너무 큽니다." }
        if (FaceRules.excluded(directory.name, videoFolder)) return@sequence
        check(directory.canonicalFile.toPath().startsWith(boundary.toPath())) { "저장 폴더 밖의 경로입니다." }
        val children = directory.listFiles() ?: error("폴더를 읽을 수 없습니다: ${directory.path}")
        for (child in children) {
            if (Files.isSymbolicLink(child.toPath())) continue
            if (child.isDirectory) {
                val nextBase = if (!perFolder || com.plugin.compatibility.MediaRules.isQualityFolder(child.name)) base else child
                yieldAll(local(child, nextBase, boundary, videoFolder, perFolder, depth + 1))
            } else if (child.isFile && FaceRules.isImage(child.name)) {
                yield(Candidate(MediaLibrary.MediaFile(child.absolutePath, child.name, child.length(), 0.0), base.absolutePath,
                    root = boundary.path))
            }
        }
    }

    /** SAF 문서 ID를 경로로 추측하지 않고 실제 트리의 부모를 기준으로 분류합니다. */
    private fun documents(directory: DocumentFile, base: DocumentFile, videoFolder: String,
        perFolder: Boolean, depth: Int): Sequence<Candidate> = sequence {
        check(depth <= 128) { "폴더 깊이가 너무 큽니다." }
        if (FaceRules.excluded(directory.name.orEmpty(), videoFolder)) return@sequence
        check(directory.canRead() && directory.canWrite()) { "폴더 접근 권한이 필요합니다: ${directory.name}" }
        for (child in directory.listFiles()) {
            val name = child.name.orEmpty()
            if (child.isDirectory) {
                val nextBase = if (!perFolder || com.plugin.compatibility.MediaRules.isQualityFolder(name)) base else child
                yieldAll(documents(child, nextBase, videoFolder, perFolder, depth + 1))
            } else if (child.isFile && FaceRules.isImage(name)) {
                yield(Candidate(MediaLibrary.MediaFile(child.uri.toString(), name, child.length(), 0.0,
                    parentUri = directory.uri.toString()), base.uri.toString(), documentParent = base))
            }
        }
    }

    /** 얼굴 없음이 확정된 파일을 옮길 때만 대상 폴더를 만들고 기존 파일은 보존합니다. */
    suspend fun move(context: Context, candidate: Candidate): String {
        val parent = candidate.destinationParent
        var targetDocument: DocumentFile? = null
        val target = if (parent.startsWith("content://")) {
            // 단일 문서 객체는 폴더 생성을 지원하지 않으므로 탐색한 트리 객체를 보존합니다.
            val document = candidate.documentParent ?: error("대상 폴더를 열 수 없습니다.")
            targetDocument = document.findFile("NOFACE")?.also { check(it.isDirectory) { "NOFACE 이름의 파일이 이미 있습니다." } }
                ?: document.createDirectory("NOFACE") ?: error("NOFACE 폴더를 만들 수 없습니다.")
            targetDocument.uri.toString()
        } else {
            val boundary = File(candidate.root).canonicalFile.toPath()
            check(File(candidate.file.path).canonicalFile.toPath().startsWith(boundary) &&
                File(parent).canonicalFile.toPath().startsWith(boundary)) { "스캔 후 폴더 경로가 변경되었습니다." }
            val folder = File(parent, "NOFACE")
            check(!Files.isSymbolicLink(folder.toPath())) { "NOFACE가 연결된 폴더입니다." }
            check(folder.isDirectory || folder.mkdir()) { "NOFACE 폴더를 만들 수 없습니다." }
            folder.absolutePath
        }
        return MediaLibrary.moveToFolder(candidate.file, target, targetDocument)
    }
}
