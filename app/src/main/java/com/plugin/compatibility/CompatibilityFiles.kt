package com.plugin.compatibility

import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.annotation.CheckResult
import androidx.documentfile.provider.DocumentFile
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.R
import java.io.File
import com.plugin.runtime.pluginQueue

/** 기존 파일 API의 삭제 검증·SAF 보존 동작을 항상 제공합니다. */
object CompatibilityFiles {
    /** 전체 쿠키와 프로필 쿠키의 경로를 분리해 병렬 요청 충돌을 막습니다. */
    fun cookiesFile(owner: android.content.Context, profileId: Int): File =
        File(owner.cacheDir, if (profileId < 0) "cookies.txt" else "cookies-$profileId.txt")

    /** 삭제 성공 후에만 같은 파일의 완료 대기열 항목을 파일 없음으로 표시합니다. */
    fun deleteFile(path: String): Result<Unit> = runCatching {
        // 런타임 연결 실패를 파일 삭제 전에 확인해 파일만 사라진 실패 결과를 만들지 않습니다.
        val queue = org.koin.core.context.GlobalContext.get().get<com.junkfood.seal.download.DownloaderV2>().pluginQueue()
        val uri = Uri.parse(path)
        val deleted = if (uri.scheme == "content") {
            val document = DocumentFile.fromSingleUri(context, uri)
            document != null && (!document.exists() || document.delete())
        } else {
            val file = if (uri.scheme == "file") File(java.net.URI(path)) else File(path)
            !file.exists() || file.delete()
        }
        check(deleted) { context.getString(R.string.loc_file_delete_failed) }
        queue.removeCompletedFile(path)
    }

    @CheckResult
    /** 폴더 구조를 유지하며 SAF로 복사하고 모든 파일 검증이 성공한 경우에만 임시 원본을 정리합니다. */
    fun moveFilesToSdcard(tempPath: File, sdcardUri: String): Result<List<String>> {
        val uriList = mutableListOf<String>()
        val res =
            tempPath.runCatching {
                walkTopDown().forEach {
                    if (it.isDirectory || it.name.endsWith(".part")) return@forEach
                    var parent =
                        androidx.documentfile.provider.DocumentFile.fromTreeUri(
                            context,
                            Uri.parse(sdcardUri),
                        ) ?: error(com.junkfood.seal.App.context.getString(R.string.loc_storage_unavailable))
                    val relativeParent = it.parentFile!!.relativeTo(tempPath).path
                    if (relativeParent.isNotEmpty())
                        relativeParent.split(File.separatorChar).forEach { name ->
                            parent =
                                parent.findFile(name)?.also { folder -> check(folder.isDirectory) }
                                    ?: parent.createDirectory(name)
                                    ?: error(com.junkfood.seal.App.context.getString(R.string.loc_subfolder_create_failed))
                        }
                    check(parent.findFile(it.name) == null) { com.junkfood.seal.App.context.getString(R.string.loc_storage_name_conflict, it.name) }
                    val mimeType =
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension) ?: "*/*"

                    val destUri =
                        DocumentsContract.createDocument(
                            context.contentResolver,
                            parent.uri,
                            mimeType,
                            ".seal-${it.name}.part",
                        ) ?: error(com.junkfood.seal.App.context.getString(R.string.loc_storage_file_create_failed))

                    val expectedLength = it.length()
                    it.inputStream().use { input ->
                        val output =
                            context.contentResolver.openOutputStream(destUri)
                                ?: error(com.junkfood.seal.App.context.getString(R.string.loc_storage_file_open_failed))
                        output.use { stream ->
                            check(input.copyTo(stream) == expectedLength) { com.junkfood.seal.App.context.getString(R.string.loc_storage_size_verify_failed) }
                        }
                    }
                    val stored =
                        androidx.documentfile.provider.DocumentFile.fromSingleUri(context, destUri)
                    check(stored?.length() == expectedLength) { com.junkfood.seal.App.context.getString(R.string.loc_storage_size_mismatch) }
                    // 실패한 복사본은 완성 파일명으로 노출하지 않아 중복 검사에서 제외됩니다.
                    check(parent.findFile(it.name) == null && stored!!.renameTo(it.name)) {
                        com.junkfood.seal.App.context.getString(R.string.loc_storage_rename_failed)
                    }
                    uriList.add(stored.uri.toString())
                }
                uriList
            }
        if (res.isSuccess) tempPath.deleteRecursively()
        return res
    }

}
