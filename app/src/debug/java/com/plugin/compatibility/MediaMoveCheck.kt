package com.plugin.compatibility

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.plugin.duplicates.*
import com.plugin.ui.PluginMainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

/** 앱 소유 검사 폴더에서 실제 이동·충돌·동일 폴더·경계 검증을 실행합니다. */
class MediaMoveCheck : Instrumentation() {
    /** 외부 저장소 권한이나 사용자 설정 변경 없이 검사를 시작합니다. */
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    /** 생성한 파일만 이동하며 검사 자료와 기존 대상 파일을 모두 보존합니다. */
    override fun onStart() {
        try {
            waitForIdleSync()
            // Android 외부 에뮬레이션 저장소는 링크 생성을 막으므로 링크 검사도 가능한 앱 내부 폴더를 사용합니다.
            val sandbox = File(targetContext.filesDir, "move-check/${UUID.randomUUID()}")
            val source = File(sandbox, "source").apply { check(mkdirs()) }
            val destination = File(sandbox, "destination").apply { check(mkdirs()) }
            val file = File(source, "page.jpg").apply { writeText("이동할 파일") }
            val existing = File(destination, file.name).apply { writeText("기존 파일 보존") }
            val media = MediaLibrary.MediaFile(file.path, file.name, file.length(), 0.0)
            runBlocking {
                check(MediaLibrary.moveToFolder(media, source.path) == file.path)
                check(file.readText() == "이동할 파일" && source.listFiles()!!.size == 1)
                val moved = MediaLibrary.moveToFolder(media, destination.path)
                check(!file.exists() && File(moved).readText() == "이동할 파일")
                check(moved != existing.path && existing.readText() == "기존 파일 보존")
                check(MediaLibrary.moveToFolder(media.copy(path = moved, name = File(moved).name), destination.path) == moved)
            }
            check(DownloadFolders.local(DownloadFolder(sandbox.path, "destination")) == destination)
            for (relative in listOf("../outside", "destination/../source", "destination\\escape", "1080p", "NOFACE", "tmp")) {
                check(runCatching { DownloadFolders.local(DownloadFolder(sandbox.path, relative)) }.isFailure) { relative }
            }
            val link = File(sandbox, "linked")
            java.nio.file.Files.createSymbolicLink(link.toPath(), destination.toPath())
            check(runCatching { DownloadFolders.local(DownloadFolder(sandbox.path, "linked")) }.isFailure)
            checkReview(sandbox)
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "동일 폴더 유지·실제 파일 이동·충돌 파일 보존·경로 이탈·링크 차단·321개 비교 UI 통과\n검사 자료: $sandbox")
            })
        } catch (failure: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", failure.stackTraceToString()) })
        }
    }

    /** 실제 Compose 비교창에 321장을 전달해 배치 전환·가로 스크롤·삭제 버튼 접근성을 검증합니다. */
    private fun checkReview(sandbox: File) {
        val image = File(sandbox, "preview.png")
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        try { image.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val media = (0..320).map { index ->
            val file = File(sandbox, "파일 $index.png")
            image.copyTo(file)
            DuplicateMedia(file.path, file.name, file.length(), file.lastModified(), 32, 32, 0, false, index.toString())
        }
        val group = DuplicateGroup(media, true, 1.0)
        val activity = startActivitySync(Intent(targetContext, PluginMainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PluginMainActivity
        try {
            runOnMainSync { activity.setContent {
                MaterialTheme {
                    DuplicateReviewDialog(listOf(group), emptyMap(), mapOf(group.id to media[0].path), emptySet(), false, 320, "",
                        onKeep = { _, _ -> }, onSelect = { _, _ -> }, onOpen = {}, onExclude = {}, onDelete = {}, onDismiss = {})
                }
            } }
            waitForIdleSync()
            runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    while (nodes().none { it.text?.toString() == "세로 비교" }) delay(100)
                }
            }
            check(nodes().any { it.text?.toString() == "세로 비교" }) { nodes().joinToString { "${it.packageName}:${it.text}:${it.contentDescription}" } }
            check(nodes().count { it.contentDescription?.toString()?.startsWith("파일 ") == true } in 1..20)
            check(clickLabel("가로 비교"))
            waitForIdleSync()
            runBlocking { delay(500) }
            check(nodes().any { it.text?.contains("320개") == true || it.contentDescription?.contains("선택한 중복 삭제") == true }) {
                nodes().joinToString { "${it.text}:${it.contentDescription}" }
            }
            // Compose는 수평 목록도 일반 앞으로 스크롤 동작으로 노출합니다.
            var row = nodes().first { it.contentDescription?.startsWith("파일 ") == true }.parent
            while (row != null && !row.isScrollable) row = row.parent
            check(row?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true)
            waitForIdleSync()
            check(clickLabel("세로 비교"))
        } finally { runOnMainSync { activity.finish() } }
    }

    /** Compose 텍스트의 실제 클릭 동작을 가진 상위 칩을 눌러 사용자 입력과 동일하게 전환합니다. */
    private fun clickLabel(label: String): Boolean {
        var node = nodes().firstOrNull { it.text?.toString() == label }
        while (node != null) {
            if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent
        }
        return false
    }

    /** 외부 UI 검사 라이브러리 없이 현재 비교창의 접근성 노드를 순회합니다. */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
        uiAutomation.rootInActiveWindow?.let { pending.add(it) }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst(); result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { pending.add(it) }
        }
        return result
    }
}
