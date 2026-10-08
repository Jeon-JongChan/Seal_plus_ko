package com.junkfood.seal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.MediaLibrary
import com.junkfood.seal.util.VideoInfo
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 번들 FFmpeg와 Android 메타데이터를 사용해 변환·분류·하위 폴더 중복 확인을 검증합니다. */
@RunWith(AndroidJUnit4::class)
class NativeMediaTest {
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
            val preferences =
                DownloadUtil.DownloadPreferences.EMPTY.copy(
                    transcodeAv1 = true,
                    classifyMedia = true,
                )
            val converted =
                File(MediaLibrary.finish(listOf(source.absolutePath), preferences, id).single())
            assertFalse("검증 후 원본을 제거해야 합니다.", source.exists())
            assertTrue(converted.isFile)
            assertEquals("저화질", converted.parentFile!!.name)
            assertEquals(2.0, MediaLibrary.durationSeconds(converted.absolutePath), 0.2)
            val moved =
                directory.resolve("moved/nested").apply { check(mkdirs()) }.resolve(converted.name)
            check(converted.renameTo(moved))
            App.videoDownloadDir = directory.absolutePath
            val info =
                VideoInfo(
                    id = id,
                    title = "native-check",
                    webpageUrl = "https://example.invalid/$id",
                    ext = "mp4",
                )
            assertTrue(MediaLibrary.findExisting(info, preferences).contains(moved.absolutePath))
        } finally {
            App.videoDownloadDir = previousVideoDirectory
            directory.deleteRecursively()
        }
    }
}
