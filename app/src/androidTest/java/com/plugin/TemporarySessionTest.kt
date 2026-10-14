package com.plugin

import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.App
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.download.DownloaderV2
import com.plugin.compatibility.*
import com.plugin.runtime.*
import com.plugin.queue.PluginTask
import com.plugin.queue.PluginTaskBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** 별도 검사 앱의 캐시만 사용해 격리·통합·폐기·충돌 보존을 검증합니다. */
class TemporarySessionTest {
    /** 일반 파일·이력은 숨기고 통합에서 충돌 파일을 덮어쓰지 않으며 폐기 시 일반 자료를 보존합니다. */
    @Test fun isolationMergeAndDiscard(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicecheck"))
        PluginAppHelper.initDownloader(context)
        val engine = GlobalContext.get().get<DownloaderV2>().pluginQueue() as PluginTaskEngine
        val deadline = android.os.SystemClock.uptimeMillis() + 30_000
        while (!engine.restored && android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
        assertTrue(engine.restored)
        val fixture = File(context.cacheDir, "temporary-check-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val oldRoot = App.videoDownloadDir
        val oldAudio = App.audioDownloadDir
        val normalTasks = engine.getTaskStateMap().toMap()
        val normalFile = File(fixture, "post.png").apply { writeText("일반 자료") }
        val originalBytes = normalFile.readBytes()
        var merged: String? = null
        try {
            App.videoDownloadDir = fixture.path
            App.audioDownloadDir = File(fixture, "Audio").path
            val preferences = createPluginPreferences()
            PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, "일반", "계정", "https://example.invalid/normal", "", normalFile.path))
            TemporarySessionController.enter(engine)
            val root = checkNotNull(TemporarySession.root)
            assertEquals(root, App.videoDownloadDir)
            assertEquals(root, App.audioDownloadDir)
            assertTrue(PluginDatabaseUtil.getDownloadHistory().isEmpty())
            assertTrue(engine.getTaskStateMap().isEmpty())
            val temporary = File(root, "post.png").apply { writeText("임시 자료") }
            PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, "임시", "계정", "https://example.invalid/temporary", "", temporary.path))
            PluginMediaMetadataStore.save(temporary.path, postPageCount = 3)
            val task = PluginTask("https://example.invalid/temporary", preferences = preferences)
            engine.getTaskStateMap()[task] = PluginTask.State(PluginTask.DownloadState.Completed(temporary.path), null, PluginTask.ViewState())
            PluginTaskBackup.save(engine.getTaskStateMap().toMap())
            assertTrue(File(root, ".data/queue.json").isFile)
            assertFalse(PluginTaskBackup.load(null).containsKey(task))
            assertEquals(listOf(temporary.path), PluginDatabaseUtil.getDownloadHistory().map { it.videoPath })
            TemporarySessionController.exit(engine, true)
            assertNull(TemporarySession.root)
            assertFalse(File(root).exists())
            assertArrayEquals(originalBytes, normalFile.readBytes())
            merged = PluginDatabaseUtil.getDownloadHistory().single { it.videoTitle == "임시" }.videoPath
            assertNotEquals(normalFile.path, merged)
            assertEquals("임시 자료", File(merged!!).readText())
            assertEquals(3, PluginMediaMetadataStore.get(merged!!)?.postPageCount)
            assertTrue(engine.getTaskStateMap().values.any { (it.downloadState as? PluginTask.DownloadState.Completed)?.filePath == merged })
            TemporarySessionController.enter(engine)
            val discarded = checkNotNull(TemporarySession.root)
            File(discarded, "discard.png").writeText("폐기")
            val marker = File(discarded, ".session")
            val owner = marker.readText()
            marker.writeText("잘못된 소유 기록")
            assertTrue(runCatching { TemporarySessionController.exit(engine, false) }.isFailure)
            assertEquals(discarded, TemporarySession.root)
            assertTrue(File(discarded, "discard.png").exists())
            marker.writeText(owner)
            TemporarySessionController.exit(engine, false)
            assertFalse(File(discarded).exists())
            assertTrue(File(merged!!).exists())
            assertArrayEquals(originalBytes, normalFile.readBytes())
        } finally {
            if (TemporarySession.root != null) TemporarySessionController.exit(engine, false)
            PluginDatabaseUtil.deleteInfoByPath(normalFile.path)
            merged?.let { PluginDatabaseUtil.deleteInfoByPath(it); PluginMediaMetadataStore.delete(it) }
            engine.switchSession(null, normalTasks)
            PluginTaskBackup.save(normalTasks, null)
            App.videoDownloadDir = oldRoot
            App.audioDownloadDir = oldAudio
            fixture.deleteRecursively()
        }
    }
}
