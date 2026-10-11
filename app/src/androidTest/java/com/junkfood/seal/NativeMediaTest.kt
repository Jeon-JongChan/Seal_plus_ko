package com.junkfood.seal

import com.plugin.compatibility.MediaRules
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.util.DownloadUtil
import com.plugin.compatibility.MediaLibrary
import com.junkfood.seal.util.DatabaseUtil
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.VideoInfo
import com.plugin.instagram.InstagramUtil
import com.plugin.compatibility.InstagramMedia
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first

/** 번들 FFmpeg와 Android 메타데이터를 사용해 변환·분류·하위 폴더 중복 확인을 검증합니다. */
@RunWith(AndroidJUnit4::class)
class NativeMediaTest {
    /** 공개 프로필 조회와 원본 영상 저장을 등록 쿠키 없이 확인합니다. */
    @Test(timeout = 300_000)
    fun publicProfileWithoutCookies() {
        if (InstrumentationRegistry.getArguments().getString("profileProbe") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)
        FFmpeg.init(context)
        val root = File(context.cacheDir, "public-profile-check-${System.nanoTime()}").apply { check(mkdirs()) }
        val previous = App.videoDownloadDir
        App.videoDownloadDir = root.absolutePath
        try {
            kotlinx.coroutines.runBlocking {
                val preferences = DownloadUtil.DownloadPreferences.EMPTY.copy(cookies = false, cookieProfileId = -1)
                val items = InstagramUtil.fetch("https://www.instagram.com/dianyang0928/", preferences, "public-profile-check")
                assertTrue(items.isNotEmpty())
                assertTrue(items.all { it.author == "dianyang0928" && InstagramUtil.safeMediaUrl(it.url) })
                val video = items.first { !it.image }.copy(id = root.name)
                val paths = InstagramUtil.download(video, preferences, "public-profile-video", null).getOrThrow()
                assertTrue(paths.all { it.startsWith(root.absolutePath + "/") && File(it).length() > 0 })
                assertTrue(paths.all { File(it).name.contains(root.name) })
                assertNotNull(MediaLibrary.readVideoQuality(paths.first()))
                android.util.Log.i("ProfileProbe", "cookies=false posts=${items.map { it.postUrl }.distinct().size} files=${items.size} downloaded=${paths.size}")
            }
        } finally {
            kotlinx.coroutines.runBlocking {
                DatabaseUtil.getDownloadHistory().filter { it.videoPath.startsWith(root.absolutePath + "/") }
                    .forEach { DatabaseUtil.deleteInfoById(it.id) }
            }
            App.videoDownloadDir = previous
            root.deleteRecursively()
        }
    }

    /** 실제 이미지 폴더·메타데이터·게시물별 페이지 중복 방지를 네트워크 없이 확인합니다. */
    @Test(timeout = 30_000)
    fun imageOrganizationAndPageDuplicateLookup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "page-check-${System.nanoTime()}").apply { check(mkdirs()) }
        val previous = App.videoDownloadDir
        App.videoDownloadDir = root.absolutePath
        try {
            val prefs = DownloadUtil.DownloadPreferences.EMPTY.copy(classifyMedia = true)
            val bitmap = android.graphics.Bitmap.createBitmap(720, 1280, android.graphics.Bitmap.Config.ARGB_8888)
            val paths = (0..1).map { index ->
                val input = root.resolve("page-$index.jpg")
                input.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)) }
                MediaLibrary.finish(listOf(input.absolutePath), prefs, "page-$index", mediaRoot = root, platform = "Instagram").single()
            }
            bitmap.recycle()
            assertTrue(paths.all { it.contains("/Instagram/Images/720x1280/") })
            kotlinx.coroutines.runBlocking {
                paths.forEachIndexed { index, path ->
                    DatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, "검사", "검사",
                        "https://www.instagram.com/p/page-check-${root.name}/#seal-media=$index", "", path,
                        "Instagram", postPageIndex = index, postPageCount = 2))
                }
                val records = DatabaseUtil.getDownloadHistory().filter { it.videoPath in paths }
                assertEquals(2, records.size)
                assertTrue(records.all { it.postPageCount == 2 && it.quality?.width == 720 && it.quality?.height == 1280 })
                // 파일을 다시 받지 않아도 같은 이력의 시도 수와 페이지 순서를 갱신해야 합니다.
                val first = records.first()
                DatabaseUtil.insertInfoImmediately(first.copy(postPageCount = 3, postPageIndex = 1))
                val reused = DatabaseUtil.getDownloadHistory().single { it.id == first.id }
                assertEquals(3, reused.postPageCount)
                assertEquals(1, reused.postPageIndex)
                paths.forEachIndexed { index, path ->
                    val info = com.junkfood.seal.util.VideoInfo(id = "page-$index", ext = "jpg",
                        webpageUrl = "https://www.instagram.com/p/page-check-${root.name}/?utm_source=copy#seal-media=$index")
                    assertEquals(listOf(path), MediaLibrary.findExisting(info, prefs))
                }
                // 게시글 전체가 아니라 선택한 페이지 파일과 그 이력만 제거해야 합니다.
                com.junkfood.seal.util.FileUtil.deleteFile(paths.first()).getOrThrow()
                DatabaseUtil.deleteInfoByPath(paths.first())
                assertFalse(File(paths.first()).exists())
                assertTrue(File(paths.last()).isFile)
                val remaining = DatabaseUtil.getDownloadHistory().filter { it.videoPath in paths }
                assertEquals(listOf(paths.last()), remaining.map { it.videoPath })
            }
        } finally {
            kotlinx.coroutines.runBlocking {
                DatabaseUtil.getDownloadHistory().filter { it.videoPath.startsWith(root.absolutePath + "/") }
                    .forEach { DatabaseUtil.deleteInfoById(it.id) }
            }
            App.videoDownloadDir = previous
            root.deleteRecursively()
        }
    }
    /** 명시적으로 요청한 UI 검사에만 혼합 목록을 표시하며 다운로드나 사용자 이력은 변경하지 않습니다. */
    @Test(timeout = 90_000)
    fun mixedSelectionUiFixture() {
        if (InstrumentationRegistry.getArguments().getString("mixedSelectionFixture") != "true") return
        val scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java)
        var viewModel: com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel? = null
        try {
            scenario.onActivity { activity ->
                // 실제 활동의 기존 ViewModel을 사용해 별도 테스트 화면을 만들지 않습니다.
                val delegate = MainActivity::class.java.getDeclaredField("dialogViewModel\$delegate").apply { isAccessible = true }.get(activity) as Lazy<*>
                val model = delegate.value as com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
                viewModel = model
                val url = "https://www.instagram.com/p/BA/"
                val image = InstagramMedia("1", url, "https://s.cdninstagram.com/fixture.jpg", "", true, "선택 화면 검사", "fixture")
                val video = image.copy(id = "2", image = false)
                model.postAction(com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action.ShowSheet())
                val field = model.javaClass.getDeclaredField("mSheetStateFlow").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val flow = field.get(model) as kotlinx.coroutines.flow.MutableStateFlow<com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState>
                flow.value = com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState.InstagramSelection(
                    (1..20).map { image.copy(id = "image-$it") } + video, DownloadUtil.DownloadPreferences.EMPTY)
            }
            Thread.sleep(45_000)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel?.postAction(com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action.HideSheet)
            }
            scenario.close()
        }
    }

    /** 공개 게시물의 영상 분석 결과를 기존 다운로드 함수로 실제 저장합니다. */
    @Test(timeout = 180_000)
    fun publicSocialVideoUsesOriginalEngine() {
        val url = InstrumentationRegistry.getArguments().getString("publicVideoUrl") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "public-video-check-${System.nanoTime()}").apply { check(mkdirs()) }
        val previousDirectory = App.videoDownloadDir
        App.videoDownloadDir = directory.absolutePath
        try {
            YoutubeDL.init(context)
            FFmpeg.init(context)
            kotlinx.coroutines.runBlocking {
                // EMPTY는 빈 파일 이름 템플릿이므로 실제 앱의 기본 템플릿을 명시합니다.
                val preferences = DownloadUtil.DownloadPreferences.EMPTY.copy(cookies = false,
                    outputTemplate = DownloadUtil.OUTPUT_TEMPLATE_ID)
                val media = InstagramUtil.fetch(url, preferences, "public-video-check")
                assertTrue(media.isNotEmpty() && media.none { it.image })
                assertFalse(com.plugin.compatibility.MediaRules.requiresMediaSelection(listOf(url), media, false))
                // 공개 CDN은 그대로 사용하고 검사 식별자만 분리해 사용자 파일을 중복 재사용하지 않습니다.
                val info = media.first().videoInfo().copy(id = directory.name,
                    webpageUrl = "https://example.invalid/${directory.name}")
                val paths = DownloadUtil.downloadVideo(info, taskId = "public-video-save",
                    downloadPreferences = preferences, progressCallback = null).getOrThrow()
                assertTrue(paths.isNotEmpty())
                paths.forEach { path ->
                    assertTrue(path.startsWith(directory.absolutePath + "/Instagram/Videos/"))
                    assertTrue(File(path).isFile && File(path).length() > 0)
                    assertTrue(MediaLibrary.durationSeconds(path) > 0)
                }
                println("자동 분석한 영상의 기존 엔진 저장 성공")
            }
        } finally {
            kotlinx.coroutines.runBlocking {
                DatabaseUtil.getDownloadHistory().filter { it.videoPath.startsWith(directory.absolutePath + "/") }
                    .forEach { DatabaseUtil.deleteInfoById(it.id) }
            }
            App.videoDownloadDir = previousDirectory
            directory.deleteRecursively()
        }
    }

    /** 자체 생성 프로필만 삭제해 DB·빠른 적용·쿠키 파일 정리를 검증합니다. */
    @Test(timeout = 30_000)
    fun cookieProfileDeletionClearsQuickSelectionAndFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "https://example.invalid/cookie-cleanup-${System.nanoTime()}"
        val previousQuick = com.junkfood.seal.util.PreferenceUtil.run { com.junkfood.seal.util.QUICK_COOKIE_PROFILE.getInt(-1) }
        var profile: com.junkfood.seal.database.objects.CookieProfile? = null
        try {
            kotlinx.coroutines.runBlocking {
                DatabaseUtil.insertCookieProfile(com.junkfood.seal.database.objects.CookieProfile(0, url, ""))
                val created = DatabaseUtil.getCookiesFlow().first().single { it.url == url }
                profile = created
                val file = com.junkfood.seal.util.FileUtil.run { context.getCookiesFile(created.id) }
                file.writeText("테스트 전용 쿠키")
                com.junkfood.seal.util.PreferenceUtil.run { com.junkfood.seal.util.QUICK_COOKIE_PROFILE.updateInt(created.id) }
                com.junkfood.seal.ui.page.settings.network.CookiesViewModel().deleteCookieProfile(created)
                kotlinx.coroutines.withTimeout(10_000) {
                    while (DatabaseUtil.getCookieById(created.id) != null || file.readText().isNotEmpty() ||
                        com.junkfood.seal.util.PreferenceUtil.run { com.junkfood.seal.util.QUICK_COOKIE_PROFILE.getInt(-1) } != -1)
                        kotlinx.coroutines.delay(50)
                }
            }
        } finally {
            com.junkfood.seal.util.PreferenceUtil.run { com.junkfood.seal.util.QUICK_COOKIE_PROFILE.updateInt(previousQuick) }
            profile?.let { created -> kotlinx.coroutines.runBlocking { DatabaseUtil.deleteCookieProfile(created) } }
        }
    }

    /** 사용자 등록 쿠키를 출력하지 않고 같은 게시물의 조회·저장 결과만 비교합니다. */
    @Test(timeout = 180_000)
    fun compareInstagramCookieDownload() {
        val url = InstrumentationRegistry.getArguments().getString("compareInstagramUrl") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "cookie-image-check-${System.nanoTime()}").apply { check(mkdirs()) }
        val previousDirectory = App.videoDownloadDir
        App.videoDownloadDir = directory.absolutePath
        try {
            kotlinx.coroutines.runBlocking {
                val profile = DatabaseUtil.getCookiesFlow().first().firstOrNull {
                    runCatching { java.net.URI(it.url).host in setOf("instagram.com", "www.instagram.com") }.getOrDefault(false)
                }
                if (profile == null) {
                    DownloadUtil.getCookiesContentFromDatabase().getOrThrow().let { content ->
                        com.junkfood.seal.util.FileUtil.run { context.getCookiesFile() }.writeText(content)
                    }
                }
                var authenticatedDownload = false
                for (enabled in listOf(false, true)) {
                    val preferences = DownloadUtil.DownloadPreferences.createFromPreferences().copy(cookies = enabled,
                        cookieProfileId = profile?.id ?: -1, extractAudio = false, mediaSelection = 2,
                        privateMode = false, privateDirectory = false, sdcard = false, transcodeAv1 = false, classifyMedia = false)
                    val result = runCatching { InstagramUtil.fetch(url, preferences, "cookie-compare-$enabled") }
                    println("쿠키 사용=$enabled; 조회 성공=${result.isSuccess}; 미디어 수=${result.getOrNull()?.size ?: 0}")
                    if (result.isFailure) println("조회 오류=${result.exceptionOrNull()?.message}")
                    val image = result.getOrNull()?.firstOrNull { it.image }
                    if (image != null) {
                        val saved = InstagramUtil.download(image, preferences, "cookie-image-$enabled", null)
                        println("쿠키 사용=$enabled; 이미지 저장 성공=${saved.isSuccess}")
                        if (saved.isFailure) println("저장 오류=${saved.exceptionOrNull()?.message}")
                        if (enabled) {
                            authenticatedDownload = saved.isSuccess
                            // 테스트 식별자를 바꿔 기존 파일 재사용 대신 같은 CDN의 새 파일 저장을 확인합니다.
                            val direct = InstagramUtil.download(image.copy(id = image.id + "1", carousel = true),
                                preferences.copy(cookies = false), "cookie-image-direct", null)
                            println("조회한 CDN의 쿠키 미사용 저장=${direct.isSuccess}")
                            if (direct.isFailure) println("CDN 저장 오류=${direct.exceptionOrNull()?.message}")
                            assertTrue("이미지 주소 확보 후에는 로그인 쿠키 없이 CDN 파일을 저장해야 합니다.", direct.isSuccess)
                        }
                    }
                }
                assertTrue("등록 쿠키로 실제 이미지 저장에 성공해야 합니다.", authenticatedDownload)
            }
        } finally {
            kotlinx.coroutines.runBlocking {
                DatabaseUtil.getDownloadHistory().filter { it.videoPath.startsWith(directory.absolutePath + "/") }
                    .forEach { DatabaseUtil.deleteInfoById(it.id) }
            }
            App.videoDownloadDir = previousDirectory
            directory.deleteRecursively()
        }
    }

    /** 지정한 공개 CDN 이미지로 실제 파일 저장과 DB 등록·재사용을 검증합니다. */
    @Test(timeout = 120_000)
    fun imageFileDownloadWithoutCookies() {
        val url = InstrumentationRegistry.getArguments().getString("publicImageUrl") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = System.nanoTime().toString()
        val directory = File(context.cacheDir, "image-check-$id").apply { check(mkdirs()) }
        val previousDirectory = App.videoDownloadDir
        App.videoDownloadDir = directory.absolutePath
        try {
            val media = InstagramMedia(id, "https://www.instagram.com/p/image-check-$id/", url,
                url, true, "이미지 저장 검사", "image-check")
            val preferences = DownloadUtil.DownloadPreferences.EMPTY.copy(cookies = false)
            val paths = kotlinx.coroutines.runBlocking { InstagramUtil.download(media, preferences, id, null).getOrThrow() }
            val file = File(paths.single())
            assertTrue(file.isFile && file.length() > 0)
            assertNotNull(android.graphics.BitmapFactory.decodeFile(file.absolutePath))
            kotlinx.coroutines.runBlocking {
                assertTrue(DatabaseUtil.getDownloadHistory().any { it.videoPath == file.absolutePath })
                assertEquals(paths, InstagramUtil.download(media, preferences, "$id-repeat", null).getOrThrow())
            }
        } finally {
            kotlinx.coroutines.runBlocking {
                DatabaseUtil.getDownloadHistory().filter { it.videoPath.startsWith(directory.absolutePath + "/") }
                    .forEach { DatabaseUtil.deleteInfoById(it.id) }
            }
            App.videoDownloadDir = previousDirectory
            directory.deleteRecursively()
        }
    }

    /** 새 조회 결과로 공개 게시물의 모든 이미지를 로그인 쿠키 없이 저장합니다. */
    @Test(timeout = 120_000)
    fun publicInstagramWithoutCookies() {
        val url = InstrumentationRegistry.getArguments().getString("publicInstagramUrl") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "public-image-check-${System.nanoTime()}").apply { check(mkdirs()) }
        val previousDirectory = App.videoDownloadDir
        App.videoDownloadDir = directory.absolutePath
        try {
            kotlinx.coroutines.runBlocking {
                // EMPTY 설정으로 등록 프로필·이전 조회·기존 다운로드 파일을 재사용하지 않습니다.
                val preferences = DownloadUtil.DownloadPreferences.EMPTY.copy(cookies = false)
                val images = InstagramUtil.fetch(url, preferences, "public-image-check").filter { it.image }
                assertTrue("공개 게시물의 이미지 정보가 필요합니다.", images.isNotEmpty())
                InstrumentationRegistry.getArguments().getString("expectedImages")?.toInt()?.let {
                    assertEquals("캐러셀 이미지가 누락되면 안 됩니다.", it, images.size)
                }
                images.forEachIndexed { index, image ->
                    // 실제 게시물 조회와 CDN을 사용하되 파일·이력 식별자는 검사 전용으로 분리합니다.
                    val fresh = image.copy(id = "${directory.name}-$index",
                        postUrl = "https://www.instagram.com/p/${directory.name}/", carousel = true,
                        pageIndex = index, pageCount = images.size)
                    val paths = InstagramUtil.download(fresh, preferences, "public-image-$index", null).getOrThrow()
                    val file = File(paths.single())
                    assertTrue(file.absolutePath.startsWith(directory.absolutePath + "/Instagram/Images/"))
                    assertTrue(file.isFile && file.length() > 0)
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    assertNotNull(bitmap)
                    assertEquals("원본 이미지 가로 해상도가 실제 파일과 같아야 합니다.", bitmap.width, image.width)
                    assertEquals("원본 이미지 세로 해상도가 실제 파일과 같아야 합니다.", bitmap.height, image.height)
                    bitmap.recycle()
                    assertTrue(DatabaseUtil.getDownloadHistory().any { it.videoPath == file.absolutePath })
                }
                println("등록 쿠키 없는 새 게시물 조회·이미지 저장 성공; 이미지 수=${images.size}")
            }
        } finally {
            kotlinx.coroutines.runBlocking {
                DatabaseUtil.getDownloadHistory().filter { it.videoPath.startsWith(directory.absolutePath + "/") }
                    .forEach { DatabaseUtil.deleteInfoById(it.id) }
            }
            App.videoDownloadDir = previousDirectory
            directory.deleteRecursively()
        }
    }

    /** 짧은 자체 생성 영상을 AV1으로 대체하고 이동한 파일을 다시 찾는 전체 경로를 확인합니다. */
    @Test(timeout = 120_000)
    fun av1ReplacementClassificationAndDuplicateLookup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YoutubeDL.init(context)
        FFmpeg.init(context)
        val id = "native-check-${System.nanoTime()}"
        val directory = File(context.cacheDir, id).apply { check(mkdirs()) }
        val source = directory.resolve("sample [$id].mp4")
        val previousVideoDirectory = App.videoDownloadDir
        try {
            val process =
                ProcessBuilder(
                        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath,
                        "-nostdin",
                        "-f",
                        "lavfi",
                        "-i",
                        "testsrc2=size=256x144:rate=12",
                        "-t",
                        "2",
                        "-c:v",
                        "libx264",
                        "-pix_fmt",
                        "yuv420p",
                        source.absolutePath,
                    )
                    .redirectErrorStream(true)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] =
                            listOf("ffmpeg", "python", "aria2c").joinToString(":") {
                                File(
                                        context.noBackupFilesDir,
                                        "youtubedl-android/packages/$it/usr/lib",
                                    )
                                    .absolutePath
                            }
                    }
                    .start()
            val output =
                try {
                    process.inputStream.bufferedReader().readText().also {
                        assertEquals(it.takeLast(2000), 0, process.waitFor())
                    }
                } finally {
                    process.destroy()
                }
            assertTrue(output.takeLast(2000), source.length() > 0)
            assertEquals(2.0, MediaLibrary.durationSeconds(source.absolutePath), 0.2)
            val sourceQuality = MediaLibrary.readVideoQuality(source.absolutePath)!!
            assertEquals(256, sourceQuality.width)
            assertEquals(144, sourceQuality.height)
            assertEquals(12.0, sourceQuality.frameRate, 0.1)
            assertEquals("video/avc", sourceQuality.codec)
            assertEquals(source.length(), sourceQuality.bytes)
            assertNull(MediaLibrary.readVideoQuality(directory.resolve("missing.mp4").absolutePath))
            val retainedSource = directory.resolve("retained [$id-keep].mp4")
            source.copyTo(retainedSource)
            val originalBytes = retainedSource.readBytes()
            val preferences =
                DownloadUtil.DownloadPreferences.EMPTY.copy(
                    transcodeAv1 = true,
                    classifyMedia = true,
                    videoTiers = "144:0.000001:0.000001",
                )
            var recordedSource: com.junkfood.seal.database.objects.VideoQuality? = null
            val converted =
                File(MediaLibrary.finish(listOf(source.absolutePath), preferences, id,
                    onOutput = { _, quality -> recordedSource = quality }).single())
            assertEquals(sourceQuality, recordedSource)
            val convertedQuality = MediaLibrary.readVideoQuality(converted.absolutePath)!!
            assertEquals("video/av01", convertedQuality.codec)
            assertEquals(sourceQuality.width, convertedQuality.width)
            // 확장자·파일 이름을 바꾼 AV1도 복제 없이 그대로 재사용해야 합니다.
            val alreadyAv1 = directory.resolve("already-encoded.mp4")
            converted.copyTo(alreadyAv1)
            val encodedBytes = alreadyAv1.readBytes()
            assertEquals(listOf(alreadyAv1.absolutePath), MediaLibrary.finish(listOf(alreadyAv1.absolutePath),
                preferences.copy(classifyMedia = false, keepAv1Original = true), "$id-skip"))
            assertArrayEquals(encodedBytes, alreadyAv1.readBytes())
            kotlinx.coroutines.runBlocking {
                val record = DownloadedVideoInfo(0, id, "test", "https://example.invalid/$id",
                    "", converted.absolutePath, sourceQuality = recordedSource)
                DatabaseUtil.insertInfoImmediately(record)
                val saved = DatabaseUtil.getDownloadHistory().single { it.videoPath == converted.absolutePath }
                assertEquals(convertedQuality, saved.quality)
                assertEquals(sourceQuality, saved.sourceQuality)
                DatabaseUtil.deleteInfoById(saved.id)
            }
            assertFalse("검증 후 원본을 제거해야 합니다.", source.exists())
            assertTrue(converted.isFile)
            assertEquals("144p", converted.parentFile!!.name)
            assertEquals(2.0, MediaLibrary.durationSeconds(converted.absolutePath), 0.2)
            val moved =
                directory.resolve("moved/nested").apply { check(mkdirs()) }.resolve(converted.name)
            check(converted.renameTo(moved))
            App.videoDownloadDir = directory.absolutePath
            assertEquals(moved.absolutePath, MediaLibrary.resolveDownloadedPath(converted.absolutePath, preferences))
            assertNull(MediaLibrary.resolveDownloadedPath(directory.resolve("missing-$id.mp4").absolutePath, preferences))
            val info =
                VideoInfo(
                    id = id,
                    title = "native-check",
                    webpageUrl = "https://example.invalid/$id",
                    ext = "mp4",
                )
            assertTrue(MediaLibrary.findExisting(info, preferences).contains(moved.absolutePath))
            // 같은 네이티브 경로에서 원본 보존·분류·재시도까지 확인합니다.
            val retention = preferences.copy(keepAv1Original = true)
            val retainedPaths = MediaLibrary.finish(listOf(retainedSource.absolutePath), retention, "$id-keep")
            assertEquals(2, retainedPaths.size)
            val original = retainedPaths.first { it.endsWith(".mp4") }
            assertArrayEquals(originalBytes, File(original).readBytes())
            // 선택한 폴더 안에서만 분류하고 이웃 폴더의 파일은 보존합니다.
            val selectedFolder = directory.resolve("selected").apply { check(mkdirs()) }
            val selectedFile = retainedSource.takeIf { it.exists() } ?: File(original)
            val selectedCopy = selectedFolder.resolve("selected.mp4")
            selectedFile.copyTo(selectedCopy)
            val neighbor = directory.resolve("neighbor").apply { check(mkdirs()) }.resolve("untouched.mp4")
            selectedFile.copyTo(neighbor)
            val classified = kotlinx.coroutines.runBlocking {
                MediaLibrary.classify(MediaLibrary.MediaFile(selectedCopy.absolutePath, selectedCopy.name,
                    selectedCopy.length(), sourceQuality.duration, quality = sourceQuality), preferences)
            }
            assertEquals(selectedFolder.resolve("144p"), File(classified).parentFile)
            assertArrayEquals(originalBytes, neighbor.readBytes())
            assertTrue(retainedPaths.all { File(it).parentFile!!.name == "144p" })
            val retainedInfo = info.copy(id = "$id-keep", webpageUrl = "https://example.invalid/$id-keep")
            val repeated = DownloadUtil.downloadVideo(videoInfo = retainedInfo, taskId = "$id-keep-repeat",
                downloadPreferences = retention, progressCallback = null).getOrThrow()
            assertEquals(retainedPaths.toSet(), repeated.toSet())
            assertArrayEquals(originalBytes, File(original).readBytes())
        } finally {
            kotlinx.coroutines.runBlocking {
                DatabaseUtil.getDownloadHistory().filter { it.videoUrl.startsWith("https://example.invalid/$id") }
                    .forEach { DatabaseUtil.deleteInfoById(it.id) }
            }
            App.videoDownloadDir = previousVideoDirectory
            directory.deleteRecursively()
        }
    }
}
