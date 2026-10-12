package com.plugin

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.junkfood.seal.App
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import com.plugin.compatibility.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 실제 기기 파일 이동과 누락 경로·공개 프로필 전송을 합성 자료로 검증합니다. */
class MediaRevision9Test {
    /** 번호 추가는 두 파일을 보존하고 대체는 새 파일의 URL만 연결합니다. */
    @Test fun duplicateClassificationPreservesOrReplacesFiles(): Unit = runBlocking {
        val fixture = File(App.privateDownloadDir, "revision9-" + java.util.UUID.randomUUID()).apply { mkdirs() }
        val base = fixture.resolve("Instagram")
        val source = base.resolve("80x160/사진.png")
        val target = base.resolve("160x320/사진.png")
        val numbered = target.resolveSibling("사진 (1).png")
        val prefs = DownloadPreferences.EMPTY.copy(privateDirectory = true).copyPluginOptions(
            mediaProcessing = true, classifyMedia = true, imageTiers = "80x160,160x320", classificationUpperFirst = false)
        /** 검사용 PNG를 부모 폴더와 함께 만들고 비트맵 메모리를 해제합니다. */
        fun write(file: File, color: Int) {
            file.parentFile!!.mkdirs()
            val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        /** 현재 실제 파일의 화질만 스캔 결과로 전달합니다. */
        fun entry() = MediaLibrary.MediaFile(source.path, source.name, source.length(), 0.0,
            quality = checkNotNull(MediaLibrary.readMediaQuality(source.path)))
        try {
            write(source, Color.GREEN); write(target, Color.RED)
            val incoming = source.readBytes(); val previous = target.readBytes()
            assertEquals(numbered.path, MediaLibrary.classify(entry(), prefs, replaceDuplicates = false))
            assertArrayEquals(previous, target.readBytes()); assertArrayEquals(incoming, numbered.readBytes())
            assertFalse(source.exists())
            write(source, Color.BLUE)
            val replacement = source.readBytes()
            val old = com.junkfood.seal.database.objects.DownloadedVideoInfo(0, "기존 파일", "review",
                "https://example.invalid/revision9/old", "", target.path, "Review")
            PluginDatabaseUtil.insertInfoImmediately(old)
            PluginDatabaseUtil.insertInfoImmediately(old.copy(videoTitle = "새 파일", videoPath = source.path,
                videoUrl = "https://example.invalid/revision9/new"))
            assertEquals(target.path, MediaLibrary.classify(entry(), prefs, replaceDuplicates = true))
            assertArrayEquals(replacement, target.readBytes()); assertArrayEquals(incoming, numbered.readBytes())
            assertFalse(source.exists())
            val linked = PluginDatabaseUtil.getDownloadHistory().filter { it.videoPath == target.path }
            assertEquals(1, linked.size); assertEquals("https://example.invalid/revision9/new", linked.single().videoUrl)
            assertEquals(0L, MediaLibrary.fileBytes(source.path))
        } finally {
            listOf(source, target, numbered).forEach { PluginDatabaseUtil.deleteInfoByPath(it.path) }
            fixture.walkBottomUp().forEach { it.delete() }
        }
    }

    /** APK의 원본 호출이 현재 설정을 따르는 플러그인으로 연결되는지 확인합니다. */
    @Test fun originalStorageGetterUsesPluginSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val kv = com.tencent.mmkv.MMKV.defaultMMKV()
        val keys = listOf(com.junkfood.seal.util.PRIVATE_DIRECTORY, com.junkfood.seal.util.VIDEO_DIRECTORY)
        val exists = keys.associateWith { kv.containsKey(it) }
        val oldPrivate = kv.decodeBool(keys[0], false)
        val oldPath = kv.decodeString(keys[1], "")
        val fixture = File(context.cacheDir, "storage-rule-" + java.util.UUID.randomUUID())
        val getter = com.junkfood.seal.util.FileUtil::class.java.declaredMethods.single {
            it.name.startsWith("getExternalDownloadDirectory") && it.parameterCount == 0
        }.apply { isAccessible = true }
        try {
            kv.encode(keys[0], false); kv.encode(keys[1], fixture.path)
            assertEquals(fixture, getter.invoke(com.junkfood.seal.util.FileUtil))
            kv.encode(keys[0], true)
            assertEquals(File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), ".Seal"),
                getter.invoke(com.junkfood.seal.util.FileUtil))
        } finally {
            if (exists.getValue(keys[0])) kv.encode(keys[0], oldPrivate) else kv.removeValueForKey(keys[0])
            if (exists.getValue(keys[1])) kv.encode(keys[1], oldPath) else kv.removeValueForKey(keys[1])
            fixture.delete()
        }
    }

    /** 없는 로컬 경로를 문서 공급자에 보내지 않고 0으로 표시합니다. */
    @Test fun missingLocalFilesAreSafe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missing = File(context.cacheDir, "missing-" + java.util.UUID.randomUUID()).path
        assertEquals(0L, MediaLibrary.fileBytes(missing))
    }

    /** 모의 응답으로 공개 프로필 전송·계정 검증을 확인하며 쿠키 해제 시 전송하지 않습니다. */
    @Test fun profilePictureUsesSharedPublicRequest(): Unit = runBlocking {
        val html = """<script>{"user":{"username":"review","profile_pic_url_hd":"https://s.cdninstagram.com/review.jpg"}}</script>"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Cookie"))
            assertEquals("navigate", chain.request().header("Sec-Fetch-Mode"))
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body(html.toResponseBody("text/html".toMediaType())).build()
        }.build()
        val result = SocialProfilePictures.publicPage("https://www.instagram.com/review/",
            DownloadPreferences.EMPTY.copy(cookies = false), "revision9-profile", client)
        assertEquals("https://s.cdninstagram.com/review.jpg", SocialProfilePictures.parseProfilePicture(result.first, "review"))
        assertNull(SocialProfilePictures.parseProfilePicture(result.first, "other"))
    }
}
