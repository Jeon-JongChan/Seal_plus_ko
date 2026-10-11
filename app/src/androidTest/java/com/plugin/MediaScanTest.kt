package com.plugin

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.App
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.*
import com.plugin.library.MediaScanService
import com.plugin.ui.PluginMainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.activity.compose.setContent
import com.junkfood.seal.ui.theme.SealTheme
import com.plugin.library.MediaFileManagerDialog
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import com.junkfood.seal.R

/** 사용자 파일 대신 앱 캐시의 합성 이미지만 사용해 실제 스캔·이동을 검사합니다. */
@RunWith(AndroidJUnit4::class)
class MediaScanTest {
    /** 목록보기 없이 스캔·재스캔·닫기 작업만 표시하는지 확인합니다. */
    @Test
    fun fileManagerOnlyShowsScanActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "scan-ui-${System.nanoTime()}").apply { mkdirs() }
        val previousRoot = App.videoDownloadDir
        try {
            App.videoDownloadDir = root.path
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { SealTheme { MediaFileManagerDialog {} } } }
                // 비어 있고 읽을 수 있는 폴더에서도 권한 화면이 즉시 열려야 합니다.
                if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                    awaitPermissionSettings()
                    instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
                awaitDialog(context.getString(R.string.revision5_scan))
                awaitDialog(context.getString(R.string.revision5_rescan))
                awaitDialog(context.getString(R.string.close))
                // 읽을 수 있는 캐시 폴더에서도 권한 미부여 안내가 오류 발생 전에 표시되어야 합니다.
                if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager())
                    awaitDialog(context.getString(R.string.revision6_storage_permission))
                val window = instrumentation.uiAutomation.rootInActiveWindow
                assertFalse(containsLabel(window, "목록 보기"))
                assertFalse(containsLabel(window, "View files"))
            }
        } finally { App.videoDownloadDir = previousRoot }
    }

    /** 파일 이동·외부 선등록·중복 이름·ID 없는 백업을 합성 파일로 검증합니다. */
    @Test
    fun movedBackupReconnectsOnlyUniqueSourceFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "reconnect-${System.nanoTime()}").apply { mkdirs() }
        val previousRoot = App.videoDownloadDir
        val id = System.nanoTime().toString()
        val unique = root.resolve("source [$id].png").apply { writeBytes(byteArrayOf(1)) }
        val collision = root.resolve("duplicate [$id].png").apply { writeBytes(byteArrayOf(2)) }
        root.resolve("nested").apply { mkdirs() }.resolve(collision.name).writeBytes(byteArrayOf(3))
        val plain = root.resolve("plain.png").apply { writeBytes(byteArrayOf(4)) }
        try {
            App.videoDownloadDir = root.path
            // 먼저 외부 파일로 등록된 경우에도 백업의 원본 정보를 되살립니다.
            MediaLibrary.registerExternalFiles(MediaLibrary.listVideos(DownloadPreferences.EMPTY))
            for (file in listOf(unique, collision, plain)) {
                PluginDatabaseUtil.insertInfoImmediately(com.junkfood.seal.database.objects.DownloadedVideoInfo(
                    0, "원래 제목", "작성자", "https://example.com/video/$id", "https://example.com/thumb",
                    root.resolve("old/${file.name}").path, "Legacy"))
            }
            MediaLibrary.registerExternalFiles(MediaLibrary.listVideos(DownloadPreferences.EMPTY))
            val saved = PluginDatabaseUtil.getDownloadHistory()
            val connected = saved.filter { it.videoPath == unique.path }
            assertEquals(1, connected.size)
            assertEquals("Legacy", connected.single().extractor)
            assertEquals("원래 제목", connected.single().videoTitle)
            assertEquals("https://example.com/video/$id", connected.single().videoUrl)
            assertTrue(saved.any { it.videoPath == root.resolve("old/${collision.name}").path })
            assertTrue(saved.any { it.videoPath == root.resolve("old/${plain.name}").path })
            assertTrue(saved.any { it.videoPath == collision.path && it.extractor == "External" })
            assertTrue(saved.any { it.videoPath == plain.path && it.extractor == "External" })
            MediaLibrary.registerExternalFiles(MediaLibrary.listVideos(DownloadPreferences.EMPTY))
            assertEquals(saved.size, PluginDatabaseUtil.getDownloadHistory().size)
        } finally { App.videoDownloadDir = previousRoot }
    }
    /** 묶음 요청 URL이 다른 원본 인스타그램 백업과 음원 파일의 이동 연결을 확인합니다. */
    @Test
    fun legacyBatchAndAudioBackupReconnectAfterMove() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "legacy-batch-${System.nanoTime()}").apply { mkdirs() }
        val previousRoot = App.videoDownloadDir
        // 반복 실행의 이전 이력과 동명이 되지 않도록 제목만 고유하게 만들고 실제 ID는 유지합니다.
        val image = root.resolve("Video by ${root.name} [DeLP5J2zxk-].mp4").apply { writeBytes(byteArrayOf(1)) }
        val audio = root.resolve("Audio ${root.name} [1Lmy7qwmSMc].m4a").apply { writeBytes(byteArrayOf(2)) }
        try {
            App.videoDownloadDir = root.path
            for ((file, url) in listOf(image to "https://www.instagram.com/reel/DeLP1tIzeWG/",
                audio to "https://www.youtube.com/watch?v=1Lmy7qwmSMc")) {
                PluginDatabaseUtil.insertInfoImmediately(com.junkfood.seal.database.objects.DownloadedVideoInfo(
                    0, "백업 제목", "백업 작성자", url, "https://example.com/thumb",
                    root.resolve("old/${file.name}").path, if (file == image) "Instagram" else "Youtube"))
            }
            val files = MediaLibrary.listVideos(DownloadPreferences.EMPTY)
            assertEquals(setOf(image.path, audio.path), files.map { it.path }.toSet())
            MediaLibrary.registerExternalFiles(files)
            val saved = PluginDatabaseUtil.getDownloadHistory().filter { it.videoPath in setOf(image.path, audio.path) }
            assertEquals(2, saved.size)
            assertTrue(saved.all { it.extractor != "External" && it.videoTitle == "백업 제목" })
            assertEquals("https://www.instagram.com/reel/DeLP1tIzeWG/", saved.single { it.videoPath == image.path }.videoUrl)
            assertEquals(image.path, MediaLibrary.resolveDownloadedPath(root.resolve("old/${image.name}").path,
                DownloadPreferences.EMPTY))
        } finally { App.videoDownloadDir = previousRoot }
    }
    /** 기존 화질 폴더가 루트 밖을 가리키는 링크이면 합성 원본을 이동하지 않습니다. */
    @Test
    fun qualityFolderSymlinkCannotEscapeRoot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "symlink-check-${System.nanoTime()}").apply { mkdirs() }
        val outside = File(context.cacheDir, "outside-check-${System.nanoTime()}").apply { mkdirs() }
        android.system.Os.symlink(outside.path, root.resolve("1080p").path)
        val source = root.resolve("sample.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val previousRoot = App.videoDownloadDir
        try {
            App.videoDownloadDir = root.path
            val quality = com.junkfood.seal.database.objects.VideoQuality(1920, 1080, 30.0, 60.0, 120_000_000, "video/avc")
            val preferences = DownloadPreferences.EMPTY.copyPluginOptions(classifyMedia = true,
                videoTiers = MediaRules.DEFAULT_VIDEO_TIERS)
            try {
                MediaLibrary.classify(MediaLibrary.MediaFile(source.path, source.name, source.length(), 60.0,
                    quality = quality), preferences)
                fail("심볼릭 링크를 통해 루트 밖으로 이동했습니다.")
            } catch (_: IllegalArgumentException) { /* 루트 경계 검사의 예상 결과입니다. */ }
            assertArrayEquals(byteArrayOf(1, 2, 3), source.readBytes())
            assertTrue(outside.listFiles()!!.isEmpty())
        } finally { App.videoDownloadDir = previousRoot }
    }

    /** 원본 JSON 백업의 이력을 가져온 뒤 같은 경로의 합성 파일을 기존 이력으로 인식합니다. */
    @Test
    fun originalSealBackupPreservesHistoryAndRecognizesFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "backup-check-${System.nanoTime()}").apply { mkdirs() }
        val file = root.resolve("legacy.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val original = com.junkfood.seal.database.objects.DownloadedVideoInfo(1, "기존 제목", "기존 작성자",
            "https://example.com/legacy", "", file.path, "Legacy")
        val json = kotlinx.serialization.json.Json.encodeToString(
            com.junkfood.seal.database.backup.Backup.serializer(),
            com.junkfood.seal.database.backup.Backup(downloadHistory = listOf(original)))
        val previousRoot = App.videoDownloadDir
        try {
            App.videoDownloadDir = root.path
            val backup = com.junkfood.seal.database.backup.BackupUtil.run { json.decodeToBackup().getOrThrow() }
            assertEquals(1, com.junkfood.seal.util.DatabaseUtil.importBackup(backup,
                setOf(com.junkfood.seal.database.backup.BackupUtil.BackupType.DownloadHistory)))
            val files = MediaLibrary.listVideos(DownloadPreferences.EMPTY)
            assertEquals(1, files.size)
            assertFalse(files.single().external)
            MediaLibrary.registerExternalFiles(files)
            val saved = PluginDatabaseUtil.getDownloadHistory().filter { it.videoPath == file.path }
            assertEquals(1, saved.size)
            assertEquals(original.videoUrl, saved.single().videoUrl)
            assertEquals(original.videoTitle, saved.single().videoTitle)
            assertNotNull(saved.single().quality)
        } finally { App.videoDownloadDir = previousRoot }
    }

    /** 사용자 하위 폴더를 보존하며 반복 분류·전체 해제·실제 이름 충돌을 검증합니다. */
    @Test
    fun nestedFolderRescanIsIdempotentAndNeverOverwrites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "nested-rescan-${System.nanoTime()}").apply { mkdirs() }
        val parent = root.resolve("Instagram/여행").apply { mkdirs() }
        val source = parent.resolve("1920x1080").apply { mkdirs() }.resolve("sample.mp4")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val previousRoot = App.videoDownloadDir
        try {
            App.videoDownloadDir = root.path
            val quality = com.junkfood.seal.database.objects.VideoQuality(1920, 1080, 30.0, 60.0, 120_000_000, "video/avc")
            val preferences = DownloadPreferences.EMPTY.copyPluginOptions(classifyMedia = true,
                imageFolderName = "Images", videoFolderName = "Videos",
                videoTiers = MediaRules.DEFAULT_VIDEO_TIERS, codecRatios = MediaRules.DEFAULT_CODEC_RATIOS)
            val file = MediaLibrary.MediaFile(source.path, source.name, source.length(), 60.0, quality = quality)
            val moved = MediaLibrary.classify(file, preferences)
            assertEquals(parent.resolve("1080p/sample.mp4").path, moved)
            assertEquals(moved, MediaLibrary.classify(file.copy(path = moved), preferences))
            val disabled = preferences.copyPluginOptions(classifyMedia = false)
            val restored = MediaLibrary.classify(file.copy(path = moved), disabled)
            assertEquals(parent.resolve("sample.mp4").path, restored)
            assertEquals(restored, MediaLibrary.classify(file.copy(path = restored), disabled))
            val repeated = MediaLibrary.classify(file.copy(path = restored), preferences)
            assertEquals(moved, repeated)
            // 다른 실제 파일이 같은 이름을 차지하면 두 파일 모두 보존합니다.
            File(restored).writeBytes(byteArrayOf(4, 5, 6))
            try {
                MediaLibrary.classify(file.copy(path = moved), disabled)
                fail("기존 파일을 덮어썼습니다.")
            } catch (_: IllegalStateException) { /* 실제 이름 충돌의 예상 결과입니다. */ }
            assertArrayEquals(byteArrayOf(4, 5, 6), File(restored).readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), File(moved).readBytes())
            assertFalse(root.resolve("Videos").exists())
            assertFalse(root.resolve("Images").exists())
        } finally { App.videoDownloadDir = previousRoot }
    }

    /** 읽을 수 없는 폴더를 파일 0개로 오인하지 않고 접근 오류로 전달합니다. */
    @Test
    fun inaccessibleFolderReportsError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "unreadable-check-${System.nanoTime()}").apply { mkdirs() }
        val previousRoot = App.videoDownloadDir
        try {
            App.videoDownloadDir = root.path
            assertTrue(root.setReadable(false, false))
            try {
                MediaLibrary.listVideos(DownloadPreferences.EMPTY)
                fail("접근 거부 폴더를 빈 목록으로 처리했습니다.")
            } catch (error: SecurityException) {
                assertTrue(error.message.orEmpty().contains(root.path))
            }
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { SealTheme { MediaFileManagerDialog {} } }
                }
                if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                    awaitPermissionSettings()
                    InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    awaitDialog(root.path)
                    awaitDialog(context.getString(R.string.revision6_storage_permission))
                    // 거절 후 다시 목록을 읽어도 시스템 권한 화면을 자동으로 반복 실행하지 않습니다.
                    SystemClock.sleep(500)
                    assertEquals(context.packageName,
                        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow.packageName.toString())
                } else awaitDialog(root.path)
                val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                File(context.externalCacheDir, "unknown-permission-ui.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                screenshot.recycle()
            }
        } finally {
            assertTrue(root.setReadable(true, false))
            App.videoDownloadDir = previousRoot
        }
    }

    /** Unknown의 WebP와 메타데이터가 없는 MP4도 누락 없이 다운로드 이력에 등록합니다. */
    @Test
    fun unknownWebpAndMp4AreRegistered() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "unknown-check-${System.nanoTime()}").apply { mkdirs() }
        val unknown = root.resolve("Unknown/Images").apply { mkdirs() }
        val source = unknown.resolve("external.webp")
        val image = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)) }
        image.recycle()
        val video = root.resolve("Unknown/external.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val previousRoot = App.videoDownloadDir
        try {
            App.videoDownloadDir = root.path
            val preferences = DownloadPreferences.EMPTY.copyPluginOptions(classifyMedia = true,
                platformFolders = true, imageFolderName = "Images", videoFolderName = "Videos",
                imageTiers = MediaRules.DEFAULT_IMAGE_TIERS)
            ActivityScenario.launch(PluginMainActivity::class.java).use {
                assertEquals(2, MediaLibrary.listVideos(preferences).size)
                instrumentation.runOnMainSync { MediaScanService.start(context, preferences, all = true) }
                val result = awaitScan()
                assertEquals(2, result.done)
                // 영상 정보 추출이 실패해도 실제 파일과 다운로드 이력은 보존합니다.
                assertEquals(1, result.failures)
                assertTrue(unknown.resolve("720x1280/external.webp").isFile)
                assertFalse(source.exists())
                assertTrue(video.isFile)
                val records = runBlocking { PluginDatabaseUtil.getDownloadHistory() }
                    .filter { record -> record.videoPath.startsWith(root.path) }
                assertEquals(2, records.size)
                assertTrue(records.all { record -> record.extractor == "External" })
                assertTrue(records.any { record -> record.videoPath == video.path })
            }
        } finally { App.videoDownloadDir = previousRoot }
    }

    /** 영상 해상도 폴더 생성과 비활성 구간의 하위 폴더 이동을 실제 파일로 검사합니다. */
    @Test
    fun videoQualityFoldersFollowEnabledTiers() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "video-folder-check-${System.nanoTime()}").apply { mkdirs() }
        val source = root.resolve("sample.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val previousRoot = App.videoDownloadDir
        try {
            App.videoDownloadDir = root.absolutePath
            val quality = com.junkfood.seal.database.objects.VideoQuality(1280, 720, 30.0, 60.0, 120_000_000, "video/avc")
            val preferences = DownloadPreferences.EMPTY.copyPluginOptions(classifyMedia = true,
                platformFolders = false, videoFolderName = "Videos", videoTiers = MediaRules.DEFAULT_VIDEO_TIERS)
            val file = MediaLibrary.MediaFile(source.path, source.name, source.length(), 60.0, quality = quality)
            val moved = MediaLibrary.classify(file, preferences)
            assertEquals(root.resolve("720p/sample.mp4").path, moved)
            assertTrue(File(moved).isFile)
            val changed = preferences.copyPluginOptions(videoTiers = MediaRules.withDisabledTiers(preferences.videoTiers, "720p"))
            val lower = MediaLibrary.classify(file.copy(path = moved), changed)
            assertEquals(root.resolve("480p/sample.mp4").path, lower)
            assertTrue(File(lower).isFile)
            assertTrue(root.resolve("720p").isDirectory)
            assertEquals(1, MediaLibrary.cleanEmptyFolders(changed))
            assertTrue(root.resolve("480p").isDirectory)
        } finally { App.videoDownloadDir = previousRoot }
    }

    /** 일반 스캔의 화질 폴더 제외와 전체 재스캔의 빈 폴더명 적용을 확인합니다. */
    @Test
    fun scanSkipsClassifiedFilesAndRescanAppliesNewSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "scan-check-${System.nanoTime()}").apply { mkdirs() }
        val retained = root.resolve("720x1280").apply { mkdirs() }
        val empty = root.resolve("empty/nested").apply { mkdirs() }
        val nonempty = root.resolve("notes").apply { mkdirs() }
        nonempty.resolve("keep.txt").writeText("빈 폴더가 아닙니다.")
        val image = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        for (file in listOf(root.resolve("external.png"), retained.resolve("keep.png"))) {
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        image.recycle()
        val previousRoot = App.videoDownloadDir
        try {
            App.videoDownloadDir = root.absolutePath
            val preferences = DownloadPreferences.EMPTY.copyPluginOptions(mediaProcessing = true,
                classifyMedia = true, imageFolderName = "Images", videoFolderName = "", platformFolders = false,
                imageTiers = MediaRules.DEFAULT_IMAGE_TIERS, videoTiers = MediaRules.DEFAULT_VIDEO_TIERS)
            ActivityScenario.launch(PluginMainActivity::class.java).use { scenario ->
                instrumentation.runOnMainSync { MediaScanService.start(context, preferences, all = false) }
                val first = awaitScan()
                assertEquals(first.error, 0, first.failures)
                assertEquals(1, first.done)
                assertTrue(root.resolve("720x1280/external.png").isFile)
                assertTrue(retained.resolve("keep.png").isFile)
                assertTrue(empty.isDirectory)
                assertTrue(MediaLibrary.listVideos(preferences).all { file -> file.external })
                val registered = runBlocking { PluginDatabaseUtil.getDownloadHistory() }.filter { it.videoPath.startsWith(root.absolutePath) }
                assertEquals(2, registered.size)
                assertTrue(registered.all { it.extractor == "External" && it.quality != null })
                val changed = preferences.copyPluginOptions(imageFolderName = "",
                    imageTiers = MediaRules.withDisabledTiers(preferences.imageTiers, "720x1280"))
                instrumentation.runOnMainSync { MediaScanService.start(context, changed, all = true, cleanupEmpty = true) }
                val second = awaitScan()
                assertEquals(second.error, 0, second.failures)
                assertEquals(2, second.done)
                assertTrue(root.resolve("external.png").isFile)
                assertTrue(root.resolve("keep.png").isFile)
                assertFalse(retained.exists())
                assertFalse(root.resolve("Images").exists())
                assertFalse(root.resolve("empty").exists())
                assertTrue(second.cleaned >= 3)
                assertTrue(root.isDirectory)
                assertTrue(nonempty.resolve("keep.txt").isFile)
                assertEquals(root.absolutePath, MediaLibrary.rootPath(changed))
                val relocated = runBlocking { PluginDatabaseUtil.getDownloadHistory() }.filter { it.videoPath.startsWith(root.absolutePath) }
                assertEquals(2, relocated.size)
                assertTrue(relocated.all { it.videoPath in listOf(root.resolve("external.png").path, root.resolve("keep.png").path) })
                var scanLabel = ""
                scenario.onActivity { activity ->
                    scanLabel = activity.getString(R.string.revision5_scan)
                    activity.setContent { SealTheme { MediaFileManagerDialog {} } }
                }
                // 파일 관리자 진입 시 자동 권한 화면을 거절하고 앱 화면으로 돌아옵니다.
                if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                    awaitPermissionSettings()
                    instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
                awaitDialog(scanLabel)
                assertFalse(containsLabel(instrumentation.uiAutomation.rootInActiveWindow, "external.png"))
                assertFalse(containsLabel(instrumentation.uiAutomation.rootInActiveWindow, "폴더 변경"))
                assertFalse(containsLabel(instrumentation.uiAutomation.rootInActiveWindow, "기본 경로 사용"))
                instrumentation.waitForIdleSync()
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(context.externalCacheDir, "revision5-manager.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
                scenario.onActivity { activity ->
                    activity.setContent { SealTheme { com.plugin.library.EnhancedVideoListPage(onNavigateBack = {}) } }
                }
                awaitDialog(context.getString(R.string.revision5_external))
                val downloads = instrumentation.uiAutomation.takeScreenshot()
                File(context.externalCacheDir, "revision6-external-downloads.png").outputStream().use {
                    assertTrue(downloads.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                downloads.recycle()
            }
        } finally {
            App.videoDownloadDir = previousRoot
        }
    }

    /** 서비스 종료를 기다려 다음 스캔이 이전 서비스와 겹치지 않도록 합니다. */
    private fun awaitScan(): MediaScanService.ScanProgress = runBlocking {
        withTimeout(20_000) { MediaScanService.state.first { !it.running } }
    }

    /** 접근 실패 직후 시스템의 모든 파일 접근 설정이 자동으로 열리는지 확인합니다. */
    private fun awaitPermissionSettings() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(100) {
            if (automation.rootInActiveWindow?.packageName?.toString() == "com.android.settings") return
            SystemClock.sleep(100)
        }
        fail("저장소 권한 설정이 자동으로 열리지 않았습니다.")
    }

    /** Compose 전환이 끝나고 실제 파일 관리자 버튼이 표시된 뒤 화면을 기록합니다. */
    private fun awaitDialog(label: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(100) {
            if (containsLabel(automation.rootInActiveWindow, label)) return
            // 작은 에뮬레이터에서는 권한 버튼이 스크롤 영역 아래에 있을 수 있습니다.
            if (it >= 10 && it % 10 == 0) scrollDialog(automation.rootInActiveWindow)
            SystemClock.sleep(100)
        }
        fail("파일 관리자 화면을 찾지 못했습니다: $label")
    }

    /** 대화상자의 접근성 스크롤을 사용해 화면 아래의 버튼을 확인합니다. */
    private fun scrollDialog(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
        return (0 until node.childCount).any { scrollDialog(node.getChild(it)) }
    }

    /** 접근성 트리에서 파일 관리자 문구를 찾습니다. */
    private fun containsLabel(node: AccessibilityNodeInfo?, label: String): Boolean {
        if (node == null) return false
        if (node.text?.contains(label) == true) return true
        return (0 until node.childCount).any { containsLabel(node.getChild(it), label) }
    }
}
