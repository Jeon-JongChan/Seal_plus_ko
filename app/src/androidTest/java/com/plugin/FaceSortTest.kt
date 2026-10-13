package com.plugin

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.App
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.*
import com.plugin.faces.*
import com.plugin.ui.PluginMainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** 테스트가 생성한 캐시 이미지로만 두 모델과 무손실 폴더 분류를 검사합니다. */
class FaceSortTest {
    /** 폴더 이름이 보이지 않는 SAF ID도 읽기 메타데이터를 보존하고 변경 작업에서 제외합니다. */
    @Test fun opaqueNoFaceIdExcludesProcessingOnly(): Unit = runBlocking {
        val path = "content://test-provider/tree/root/document/${UUID.randomUUID()}.png"
        try {
            PluginMediaMetadataStore.save(path, postPageIndex = 2, postPageCount = 3, excludedFromProcessing = true)
            assertEquals(3, PluginMediaMetadataStore.get(path)?.postPageCount)
            assertTrue(PluginMediaMetadataStore.isProcessingExcluded(path))
            assertTrue(com.plugin.compatibility.enhancement.EnhancementRules.postImages(listOf(path)).isEmpty())
            val preferences = DownloadPreferences.EMPTY.copy(sdcard = false)
            assertEquals(path, MediaLibrary.classify(MediaLibrary.MediaFile(path, "page.png", 10, 0.0), preferences))
            PluginMediaMetadataStore.save(path, excludedFromProcessing = false)
            assertEquals(3, PluginMediaMetadataStore.get(path)?.postPageCount)
            assertFalse(PluginMediaMetadataStore.isProcessingExcluded(path))
            assertEquals(listOf(path), com.plugin.compatibility.enhancement.EnhancementRules.postImages(listOf(path)))
        } finally { PluginMediaMetadataStore.delete(path) }
    }
    /** 얼굴 분류로 이동한 사진도 원래 게시글에 연결되고 읽기 검색만 허용됩니다. */
    @Test fun noFaceKeepsPostMatching(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".devicecheck")) { "전용 검사 앱에서만 실행합니다." }
        val fixture = File(context.cacheDir, "noface-post-${UUID.randomUUID()}").apply { mkdirs() }
        val oldRoot = App.videoDownloadDir
        val id = UUID.randomUUID().toString()
        val source = File(fixture, "post [$id].png")
        val url = "https://www.instagram.com/p/$id/"
        var moved: String? = null
        try {
            App.videoDownloadDir = fixture.path
            val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            try { source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
            PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, "게시글", "작성자", url, source.path,
                source.path, "Instagram"))
            val preferences = DownloadPreferences.EMPTY.copy(sdcard = false, privateDirectory = false)
            moved = FaceFolders.move(context, FaceFolders.candidates(context, preferences, false).single())
            val recognized = MediaLibrary.listVideos(preferences).single()
            assertEquals(moved, recognized.path)
            assertEquals(moved, MediaLibrary.resolveDownloadedPath(source.path, preferences))
            assertEquals(listOf(moved), MediaLibrary.findExisting(com.junkfood.seal.util.VideoInfo(
                id = id, webpageUrl = url, ext = "png"), preferences))
            val saved = PluginDatabaseUtil.getDownloadHistory().single { it.videoPath == moved }
            assertEquals(url, saved.videoUrl)
            assertEquals("작성자", saved.videoAuthor)
            assertEquals(moved, MediaLibrary.classify(recognized, preferences))
            assertTrue(FaceFolders.candidates(context, preferences, false).none())
            assertTrue(com.plugin.compatibility.enhancement.EnhancementRules.postImages(listOf(moved!!)).isEmpty())
        } finally {
            PluginDatabaseUtil.deleteInfoByPath(source.path)
            moved?.let { PluginDatabaseUtil.deleteInfoByPath(it) }
            App.videoDownloadDir = oldRoot
            fixture.walkBottomUp().forEach { it.delete() }
        }
    }

    /** 배포 축소 후에도 MediaPipe JNI 초기화와 두 장치 선택의 실제 판독을 검사합니다. */
    @Test fun mediaPipeCpuAndGpuDetectFaces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val portrait = instrumentation.context.assets.open("face-sort/portrait.jpg").use {
            android.graphics.BitmapFactory.decodeStream(it)
        } ?: error("검사 얼굴을 읽을 수 없습니다.")
        val blank = Bitmap.createBitmap(321, 181, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        try {
            for (backend in listOf("cpu", "gpu")) {
                FaceDetector(context, "mediapipe", 0.5f, 640, backend).use { detector ->
                    assertTrue("$backend 얼굴 미검출", detector.hasFace(portrait))
                    assertFalse("$backend 빈 화면 오검출", detector.hasFace(blank))
                    assertTrue("$backend 반복 얼굴 미검출", detector.hasFace(portrait))
                    assertFalse("호출자의 원본 비트맵을 해제했습니다.", portrait.isRecycled)
                    android.util.Log.i("FaceMediaPipeCheck", "$backend: ${detector.engine}")
                }
            }
        } finally { blank.recycle(); portrait.recycle() }
    }

    /** Android LiteRT API의 입력 이름·버퍼·점수가 검증된 ONNX 기준값과 일치합니다. */
    @Test fun yuNetLiteRtAndroidTensorContract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        YuNetLiteRt(context, 320, false).use { detector ->
            val score = detector.score(FloatArray(320 * 320 * 3) { 128f })
            assertEquals(0.00697874f, score, 0.0002f)
        }
    }

    /** GPU 요청이 CPU 대체로 끝난 경우를 GPU 성공으로 집계하지 않습니다. */
    @Test fun yuNetExecutesLiteRtGpu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "yunet-gpu-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val portrait = File(fixture, "portrait.jpg")
            instrumentation.context.assets.open("face-sort/portrait.jpg").use { input -> portrait.outputStream().use { input.copyTo(it) } }
            val bitmap = FaceImages.decode(context, portrait.path, 320)
            try {
                FaceDetector(context, "yunet", 0.5f, 320, "gpu").use { detector ->
                    assertTrue(detector.hasFace(bitmap))
                    assertEquals("YuNet · LiteRT GPU", detector.engine)
                    val blank = Bitmap.createBitmap(321, 181, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
                    val small = Bitmap.createScaledBitmap(blank, 320, 180, true)
                    try { assertFalse(detector.hasFace(small)); assertTrue(detector.hasFace(bitmap)) }
                    finally { small.recycle(); blank.recycle() }
                    android.util.Log.i("FaceGpuCheck", detector.engine)
                }
                FaceDetector(context, "mediapipe", 0.5f, 320, "gpu").use { detector ->
                    assertTrue(detector.hasFace(bitmap))
                    assertEquals("MediaPipe · GPU", detector.engine)
                    android.util.Log.i("FaceGpuCheck", detector.engine)
                }
            } finally { bitmap.recycle() }
        } finally { fixture.walkBottomUp().forEach { it.delete() } }
    }

    /** 실제 MediaPipe·YuNet이 얼굴과 빈 화면을 구분하고 비정사각형·동적 입력을 처리합니다. */
    @Test fun bothModelsDetectFaces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "face-model-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val portrait = File(fixture, "portrait.jpg")
            instrumentation.context.assets.open("face-sort/portrait.jpg").use { input -> portrait.outputStream().use { input.copyTo(it) } }
            for (model in listOf("yunet", "mediapipe")) FaceDetector(context, model, 0.5f).use { detector ->
                for (resolution in listOf(320, 640)) {
                    val image = FaceImages.decode(context, portrait.path, resolution)
                    try { assertTrue("$model / $resolution 얼굴 미검출", detector.hasFace(image)) }
                    finally { image.recycle() }
                }
                val blank = Bitmap.createBitmap(321, 181, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
                try { assertFalse("$model 빈 화면 오검출", detector.hasFace(blank)) }
                finally { blank.recycle() }
            }
        } finally { fixture.walkBottomUp().forEach { it.delete() } }
    }

    /** 화질 폴더 포함·영상 제외·중복 번호·이력과 로컬 썸네일 갱신을 실제 파일로 확인합니다. */
    @Test fun routesOnlyFixtureImages(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "face-route-" + UUID.randomUUID()).apply { mkdirs() }
        val oldRoot = App.videoDownloadDir
        val paths = mutableListOf<String>()
        try {
            App.videoDownloadDir = fixture.path
            val images = File(fixture, "Images").apply { mkdirs() }
            val hot = File(images, "Hot").apply { mkdirs() }
            val quality = File(images, "720p").apply { mkdirs() }
            val hotQuality = File(hot, "1080x1920").apply { mkdirs() }
            val videos = File(fixture, "Videos").apply { mkdirs() }
            val already = File(hot, "NOFACE").apply { mkdirs() }
            for (parent in listOf(images, hot, quality, hotQuality, videos, already)) {
                val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
                try { File(parent, "sample.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            val prefs = DownloadPreferences.EMPTY.copy(sdcard = false, privateDirectory = false).copyPluginOptions(videoFolderName = "Videos")
            val candidates = FaceFolders.candidates(context, prefs, true).toList()
            assertEquals(4, candidates.size)
            assertEquals(images.path, candidates.single { it.file.path.startsWith(quality.path + "/") }.destinationParent)
            assertEquals(hot.path, candidates.single { it.file.path.startsWith(hotQuality.path + "/") }.destinationParent)
            val candidate = candidates.single { it.file.path.startsWith(hotQuality.path + "/") }
            val source = File(candidate.file.path); val bytes = source.readBytes()
            paths.add(source.path)
            PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, "얼굴 분류 검사", "", source.path, source.path, source.path, "External"))
            val moved = FaceFolders.move(context, candidate); paths.add(moved)
            assertEquals(already.path, File(moved).parent)
            assertNotEquals("sample.png", File(moved).name)
            assertFalse(source.exists()); assertArrayEquals(bytes, File(moved).readBytes())
            assertTrue(File(already, "sample.png").exists())
            val saved = PluginDatabaseUtil.getDownloadHistory().single { it.videoPath == moved }
            assertEquals(moved, saved.thumbnailUrl); assertEquals(moved, saved.videoUrl)
            val recognized = MediaLibrary.listVideos(prefs)
            assertTrue("NOFACE 파일이 라이브러리에서 사라졌습니다.", recognized.any { it.path == moved })
            assertEquals(moved, MediaLibrary.classify(recognized.single { it.path == moved }, prefs))
            assertTrue("NOFACE 파일을 다시 작업 대상으로 선택했습니다.",
                FaceFolders.candidates(context, prefs, true).none { it.file.path == moved })
            assertFalse(File(hotQuality, "NOFACE").exists())
            val single = FaceFolders.candidates(context, prefs, false).first()
            assertEquals(fixture.path, single.destinationParent)
            assertTrue(File(videos, "sample.png").exists())
            val corrupt = File(images, "broken.jpg").apply { writeText("이미지가 아닙니다.") }
            assertTrue(runCatching { FaceImages.decode(context, corrupt.path, 640) }.isFailure)
            assertTrue(corrupt.exists())
        } finally {
            paths.forEach { PluginDatabaseUtil.deleteInfoByPath(it) }
            App.videoDownloadDir = oldRoot
            fixture.walkBottomUp().forEach { it.delete() }
        }
    }

    /** 서비스도 사용자가 보유한 폴더 대신 테스트 전용 캐시에서만 실제 이동을 수행합니다. */
    @Test fun serviceScansOnlyFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.cacheDir, "face-service-" + UUID.randomUUID()).apply { mkdirs() }
        val oldRoot = App.videoDownloadDir
        try {
            App.videoDownloadDir = fixture.path
            val image = File(fixture, "blank.png")
            val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            ActivityScenario.launch(PluginMainActivity::class.java).use {
                val prefs = DownloadPreferences.EMPTY.copy(sdcard = false, privateDirectory = false)
                FaceScanService.start(context, prefs, FaceScanService.Options())
                val deadline = SystemClock.uptimeMillis() + 30_000
                while (FaceScanService.state.value.running && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
                val result = FaceScanService.state.value
                assertFalse("스캔 시간 초과", result.running)
                assertEquals(result.error, 0, result.failures)
                assertEquals(1, result.moved)
                assertEquals(1, result.scanned)
                assertTrue(File(fixture, "NOFACE/blank.png").exists())
            }
        } finally {
            FaceScanService.stop(context)
            App.videoDownloadDir = oldRoot
            fixture.walkBottomUp().forEach { it.delete() }
        }
    }
}
