package com.plugin

import com.junkfood.seal.database.objects.VideoQuality
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.*
import com.plugin.queue.PluginTask
import com.plugin.queue.PluginTaskAdapter
import com.plugin.queue.PluginTaskBackup
import com.plugin.runtime.actionForRetry
import com.plugin.runtime.preferencesForRetry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 화질 구간 방향·저장 경계·요청 복원·일반 오류 쿠키 재시도를 함께 검증합니다. */
class DownloadFoldersTest {
    /** 형식 선택을 거친 재시도도 요청 쿠키를 보존하고 사용자가 고른 형식·플러그인 설정은 유지합니다. */
    @Test fun selectionKeepsRequestCookiesAndChosenFormat() {
        val request = DownloadPreferences.EMPTY.copy(cookies = true, proxy = true, proxyUrl = "http://localhost:8080")
            .attachPluginOptions(PluginPreferencesRecord(cookieProfileId = 7, cookieProfileExplicit = true))
        val selected = DownloadPreferences.EMPTY.copy(cookies = false, videoResolution = 720)
            .attachPluginOptions(PluginPreferencesRecord(classificationUpperFirst = true))
        val actual = com.plugin.ui.selectionRequestPreferences(selected, request)
        assertTrue(actual.cookies)
        assertEquals(7, actual.cookieProfileId)
        assertTrue(actual.cookieProfileExplicit)
        assertEquals(request.proxyUrl, actual.proxyUrl)
        assertEquals(720, actual.videoResolution)
        assertTrue(actual.classificationUpperFirst)
        assertFalse(selected.cookies)
        assertSame(selected, com.plugin.ui.selectionRequestPreferences(selected, null))
    }

    /** 대소문자·하위 폴더·SAF 인코딩을 제외하며 비슷한 파일명은 허용합니다. */
    @Test fun noFaceFoldersAreExcluded() {
        for (path in listOf("C:\\Seal\\NOFACE\\a.jpg", "/Seal/noface/720p/a.jpg",
            "content://provider/document/primary%3ASeal%2FNoFace%2Fa.jpg", "content://provider/document/primary%3ANOFACE%2Fa.jpg")) {
            assertTrue(path, MediaRules.isNoFacePath(path))
        }
        assertFalse(MediaRules.isNoFacePath("/Seal/noface.jpg"))
        assertFalse(MediaRules.isNoFacePath("/Seal/mynoface/a.jpg"))
        assertFalse(DownloadFolders.validRelative("Images/NOFACE/child"))
        assertTrue(DownloadFolders.validRelative("Images/people"))
    }

    /** 재시도 쿠키 사본은 원래 요청과 다음 요청의 쿠키·프로필·저장폴더를 변경하지 않습니다. */
    @Test fun retryCookiesStayWithTheirRequest() {
        val original = DownloadPreferences.EMPTY.copy(cookies = false).attachPluginOptions(PluginPreferencesRecord(cookieProfileId = -1))
        val changed = original.copyPreservingPluginOptions { copy(cookies = true) }
            .copyPluginOptions(cookieProfileId = 42, cookieProfileExplicit = true)
        val request = Action.FetchPlaylist("https://example.com/failed", original)
        val retry = actionForRetry(request, changed) as Action.FetchPlaylist
        assertTrue(retry.preferences.cookies)
        assertEquals(42, retry.preferences.cookieProfileId)
        assertFalse(original.cookies)
        assertEquals(-1, original.cookieProfileId)
        assertFalse((request.preferences.copyPreservingPluginOptions { copy() }).cookies)
        assertNotSame(request.preferences, retry.preferences)
    }

    /** 480p·720p 활성 구간의 양쪽 꼬리와 정확한 경계·사이값을 확인합니다. */
    @Test fun qualityPriority() {
        val tiers = "480:0.07:0.105,720:0.14:0.210"
        val quality = VideoQuality(1440, 720, 30.0, 60.0, 120_000_000, "video/avc")
        for ((height, lower, upper) in listOf(Triple(360, "480p", null), Triple(480, "480p", "480p"),
            Triple(481, "720p", "480p"), Triple(600, "720p", "480p"), Triple(720, "720p", "720p"),
            Triple(721, null, "720p"), Triple(1080, null, "720p"))) {
            val video = quality.copy(width = height * 2, height = height)
            assertEquals("하위 우선 $height", lower, MediaRules.classificationFolder(video, videos = tiers, upperFirst = false))
            assertEquals("상위 우선 $height", upper, MediaRules.classificationFolder(video, videos = tiers, upperFirst = true))
        }
        assertEquals("720p", MediaRules.classificationFolder(quality.copy(height = 600, bytes = 1), videos = tiers, upperFirst = false))
        assertNull(MediaRules.classificationFolder(quality.copy(codec = "unknown"), videos = tiers, upperFirst = false))
        val disabled = MediaRules.withDisabledTiers(tiers, "480p,720p")
        assertNull(MediaRules.classificationFolder(quality, videos = disabled, upperFirst = false))
        val image = VideoQuality(900, 450, 0.0, 0.0, 500_000, "image/jpeg")
        assertEquals("480x960", MediaRules.classificationFolder(image, images = "480x960,720x1440", upperFirst = false))
        assertNull(MediaRules.classificationFolder(image, images = "480x960,720x1440", upperFirst = true))
        assertNull(MediaRules.classificationFolder(image, target = 1, upperFirst = false))
    }

    /** 확장자·점으로 시작하는 이름을 유지하고 이미 있는 번호를 건너뜁니다. */
    @Test fun duplicateFileNamesKeepBothFiles() {
        assertEquals("영상 (2).mp4", MediaRules.availableFileName("영상.mp4") { it in setOf("영상.mp4", "영상 (1).mp4") })
        assertEquals("사진.jpg", MediaRules.availableFileName("사진.jpg") { false })
        assertEquals(".hidden (1)", MediaRules.availableFileName(".hidden") { it == ".hidden" })
    }

    /** 비공개 선택이 공개 기본 경로보다 우선하고 공개 사용자 경로는 보존합니다. */
    @Test fun storageDirectoryHonorsPrivacyWithoutCreatingFolders() {
        val base = File("build/storage-rule-check/Downloads").absoluteFile
        assertEquals(base.resolve(".Seal"), StorageDirectories.selected(true, base.resolve("Seal").path, base))
        val custom = base.resolve("사용자 폴더")
        assertEquals(custom, StorageDirectories.selected(false, custom.path, base))
        assertEquals(base.resolve("Seal"), StorageDirectories.selected(false, "", base))
        assertEquals(base.resolve("Seal"), StorageDirectories.selected(false, "content://provider/tree/root", base))
        assertFalse(base.exists())
    }

    /** 상대 경로 이동과 화질 폴더 하위 진입을 모두 차단하고 실제 기존 디렉터리만 허용합니다. */
    @Test fun directoryBoundary() {
        for (name in listOf("", "Instagram", "Instagram/Hot")) assertTrue(DownloadFolders.validRelative(name))
        for (name in listOf("../outside", "/outside", "Instagram//Hot", "Instagram/../Hot", "720p", "Instagram/720p/Hot", "1080x1920", "Instagram/720P", "Instagram\\Hot", "tmp")) assertFalse(name, DownloadFolders.validRelative(name))
        val base = java.nio.file.Files.createTempDirectory(File("build").apply { mkdirs() }.toPath(), "folder-check-").toFile()
        try {
            val hot = base.resolve("Instagram/Hot").apply { mkdirs() }
            assertEquals(hot, DownloadFolders.local(DownloadFolder(base.path, "Instagram/Hot")))
            assertEquals(base, DownloadFolders.local(DownloadFolder(base.path)))
            assertTrue(runCatching { DownloadFolders.local(DownloadFolder(base.path, "../outside")) }.isFailure)
            assertTrue(runCatching { DownloadFolders.local(DownloadFolder(base.path, "missing")) }.isFailure)
        } finally { base.walkBottomUp().forEach { it.delete() } }
    }

    /** 선택한 폴더와 우선순위는 백업·사본에 보존하고 스위치를 끈 새 요청에서는 제외합니다. */
    @Test fun requestSnapshotAndRetry() {
        val folder = DownloadFolder("/Seal", "Instagram/Hot")
        val prefs = DownloadPreferences.EMPTY.copyPluginOptions(selectedFolder = folder, classificationUpperFirst = true, classifyMedia = true)
        val task = PluginTask("https://example.com/one", preferences = prefs)
        val state = PluginTask.State(PluginTask.DownloadState.Canceled(PluginTask.RestartableAction.FetchInfo), null, PluginTask.ViewState())
        val restored = PluginTaskBackup.decode(PluginTaskBackup.encode(mapOf(task to state))).keys.single()
        assertEquals(folder, restored.preferences.selectedFolder)
        assertTrue(restored.preferences.classificationUpperFirst)
        assertTrue(restored.preferences.classifyMedia)
        assertEquals(folder, prefs.copyPreservingPluginOptions { copy(cookies = true) }.selectedFolder)
        assertFalse(prefs.useWebsiteFolder(image = false))
        val disabled = PluginPolicy.preferences(prefs, emptySet())
        assertNull(disabled.selectedFolder)
        assertFalse(disabled.classificationUpperFirst)
        val disabledTask = task.copy(preferences = disabled)
        assertNull(PluginTaskBackup.decode(PluginTaskBackup.encode(mapOf(disabledTask to state))).keys.single().preferences.selectedFolder)
        val other = PluginTask(task.url, preferences = prefs.copyPluginOptions(selectedFolder = folder.copy(relative = "Instagram")))
        assertNotEquals(task.id, other.id)
        val native = com.junkfood.seal.download.Task(task.url, preferences = prefs)
        val adapted = PluginTaskAdapter.fromSeal(native)
        assertEquals(adapted.id, PluginTaskAdapter.fromSeal(PluginTaskAdapter.toSeal(adapted)).id)
        val actions = listOf<Action>(Action.FetchFormats(task.url, false, prefs), Action.FetchPlaylist(task.url, prefs), Action.DownloadWithPreset(listOf(task.url), prefs))
        for (action in actions) {
            assertEquals(folder, checkNotNull(preferencesForRetry(action)).selectedFolder)
            val retry = actionForRetry(action, prefs.copyPreservingPluginOptions { copy(cookies = true) }.copyPluginOptions(cookieProfileId = 3))
            val changed = checkNotNull(preferencesForRetry(retry))
            assertTrue(changed.cookies)
            assertEquals(3, changed.cookieProfileId)
            assertEquals(folder, changed.selectedFolder)
        }
    }
}
