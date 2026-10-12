package com.plugin

import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.plugin.compatibility.HistoryCleanup
import com.plugin.compatibility.PluginDatabaseUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 확인 불가·새로 생성된 파일을 보존하며 누락 이력만 삭제하는지 검사합니다. */
class HistoryCleanupTest {
    /** 승인 이후 생긴 파일과 권한 없는 문서는 유지하고 확인된 누락 이력만 삭제합니다. */
    @Test
    fun missingCleanupRechecksFilesAndPreservesUnknown() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "history-cleanup-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val present = File(root, "present.png").apply { writeText("보존할 파일") }
        val restored = File(root, "restored.png")
        val missing = File(root, "missing.png")
        val unknown = "content://unknown.invalid.provider/document/${root.name}"
        val paths = setOf(present.path, restored.path, missing.path, unknown)
        try {
            paths.forEach { path -> PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, "정리 검사", "", "https://example.invalid/${root.name}", "", path, "External")) }
            assertEquals(HistoryCleanup.Presence.UNKNOWN, HistoryCleanup.presence("relative-file.png"))
            val records = PluginDatabaseUtil.getDownloadHistory().filter { it.videoPath in paths }
            val plan = HistoryCleanup.inspect(records)
            assertEquals(2, plan.missing.size); assertEquals(1, plan.unknown)
            restored.writeText("검사 뒤 복구된 파일")
            assertEquals(1, HistoryCleanup.remove(plan))
            val remaining = PluginDatabaseUtil.getDownloadHistory().filter { it.videoPath in paths }.map { it.videoPath }.toSet()
            assertEquals(setOf(present.path, restored.path, unknown), remaining)
            assertEquals("보존할 파일", present.readText()); assertEquals("검사 뒤 복구된 파일", restored.readText())
        } finally {
            paths.forEach { PluginDatabaseUtil.deleteInfoByPath(it) }
            root.deleteRecursively()
        }
    }
}
