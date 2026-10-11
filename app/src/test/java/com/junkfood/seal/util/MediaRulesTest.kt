package com.junkfood.seal.util

import com.plugin.compatibility.InstagramMedia
import com.plugin.compatibility.MediaRules
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** 화질 경계값·링크 동등성·캐러셀 분리·잘못된 입력을 함께 검증합니다. */
class MediaRulesTest {
    /** JSON 편집 입력·파일 유형·코덱 표시·번역된 기본 폴더와 다중 페이지 선택을 확인합니다. */
    @Test
    fun revisionFourRulesAndSelection() {
        val json = MediaRules.classificationJson(MediaRules.DEFAULT_IMAGE_TIERS, MediaRules.DEFAULT_VIDEO_TIERS)
        val rules = MediaRules.parseClassificationJson(json)!!
        assertEquals(MediaRules.imageTiers(MediaRules.DEFAULT_IMAGE_TIERS), MediaRules.imageTiers(rules.first))
        assertEquals(MediaRules.videoTiers(MediaRules.DEFAULT_VIDEO_TIERS), MediaRules.videoTiers(rules.second))
        assertNull(MediaRules.parseClassificationJson("{}"))
        assertNull(MediaRules.parseClassificationJson(json.replace("720", "-720")))
        assertEquals("H.264", MediaRules.codecLabel("video/avc"))
        assertEquals("AV1", MediaRules.codecLabel("video/av01"))
        assertEquals("VP9", MediaRules.codecLabel("video/x-vnd.on2.vp9"))
        assertEquals(2, MediaRules.mediaKind("/a.jpg"))
        assertEquals(3, MediaRules.mediaKind("content://file", "audio/mp4a-latm"))
        assertEquals(1, MediaRules.mediaKind("/a.mp4"))
        assertEquals("Images", MediaRules.folderName("이미지", "Images"))
        val media = InstagramMedia("1", "https://www.instagram.com/p/BA/", "", "", true, "", "")
        assertFalse(MediaRules.requiresMediaSelection(listOf(media.postUrl), listOf(media), false))
        assertTrue(MediaRules.requiresMediaSelection(listOf(media.postUrl), listOf(media, media.copy(id = "2")), false))
        assertFalse(MediaRules.requiresMediaSelection(listOf(media.postUrl), listOf(media, media.copy(id = "2")), true))
        assertFalse(MediaRules.requiresMediaSelection(listOf("https://www.instagram.com/test/"), listOf(media), true))
    }
    /** 빠른 설정과 상세 설정 경계, 손상된 저장 값의 기본값 복구를 확인합니다. */
    @Test
    fun av1SpeedPresets() {
        listOf(0, 6, 8, 10, 12).forEach { assertEquals(it, MediaRules.av1Preset(it)) }
        listOf(-1, 13, Int.MAX_VALUE).forEach { assertEquals(8, MediaRules.av1Preset(it)) }
    }

    /** 폴더 분류 경계·프레임률·원본 코덱 압축률·입력 검증과 페이지별 중복 식별을 확인합니다. */
    @Test
    fun revisionThreeClassificationAndPageIdentity() {
        val quality = com.junkfood.seal.database.objects.VideoQuality(width = 1920, height = 1080,
            frameRate = 30.0, duration = 10.0, bytes = 10_000_000, codec = "video/avc")
        assertEquals("1080p", MediaRules.classificationFolder(quality))
        assertEquals("720p", MediaRules.classificationFolder(quality.copy(bytes = 9_999_999)))
        assertEquals("720p", MediaRules.classificationFolder(quality.copy(frameRate = 60.0)))
        assertEquals("480p", MediaRules.classificationFolder(quality.copy(codec = "video/av01", bytes = 5_500_000)))
        assertEquals("480p", MediaRules.classificationFolder(quality.copy(codec = "video/av01", bytes = 5_499_999)))
        assertNull(MediaRules.classificationFolder(quality.copy(bytes = 1_249_999)))
        assertNull(MediaRules.classificationFolder(quality.copy(frameRate = 0.0)))
        assertNull(MediaRules.classificationFolder(quality.copy(codec = "unknown")))
        val image = quality.copy(codec = "image/jpeg", bytes = 1000)
        assertEquals("1080x1920", MediaRules.classificationFolder(image))
        assertEquals("1080x1920", MediaRules.classificationFolder(image.copy(width = 1080, height = 1920)))
        assertEquals("720x1280", MediaRules.classificationFolder(image.copy(height = 1079)))
        assertNull(MediaRules.classificationFolder(image.copy(width = 700, height = 700)))
        assertEquals("1080x1920", MediaRules.classificationFolder(image.copy(width = 4000, height = 8000), images = "1080x1920"))
        assertNull(MediaRules.videoTiers("1080:NaN:1.5"))
        assertNull(MediaRules.videoTiers("720:2:3,1080:1:1.5"))
        assertNull(MediaRules.imageTiers("../1080"))
        assertNull(MediaRules.codecRatios("0,0.77,0.55"))
        assertEquals("Images", MediaRules.folderName("../Images", "Images"))
        assertEquals("Images", MediaRules.folderName("a/b", "Images"))
        assertTrue(MediaRules.isPlaylistUrl("https://www.youtube.com/watch?v=abc&list=xyz"))
        assertFalse(MediaRules.isPlaylistUrl("https://www.youtube.com/watch?v=abc"))
        assertEquals(MediaRules.identity("https://www.tiktok.com/@name/photo/123?utm_source=copy#seal-media=1"),
            MediaRules.identity("https://www.tiktok.com/@name/photo/123#seal-media=1"))
        assertNotEquals(MediaRules.identity("https://www.instagram.com/p/AB/#seal-media=1"),
            MediaRules.identity("https://www.instagram.com/p/AB/#seal-media=2"))
    }
    /** AV1·VP9는 이름이나 대체·복제 설정과 관계없이 재인코딩을 생략합니다. */
    @Test
    fun existingAv1AndVp9SkipEncoding() {
        listOf("video/av01", "VIDEO/AV01", "video/x-vnd.on2.vp9", "av1", "vp9", "vp09")
            .forEach { assertFalse(MediaRules.needsAv1Encoding(it)) }
        assertTrue(MediaRules.needsAv1Encoding("video/avc"))
        assertTrue(MediaRules.needsAv1Encoding("video/hevc"))
        assertTrue(MediaRules.usesInstagramMediaApi("https://www.instagram.com/p/BA/", 3, false))
        assertTrue(MediaRules.accepts(3, true))
        assertTrue(MediaRules.accepts(3, false))
    }
    /** 확장자와 인스타그램 자동 분석·음원 분리·주소 검증을 확인합니다. */
    @Test
    fun containerAndDefaultVideoEngine() {
        assertEquals("mp4", MediaRules.videoContainer(""))
        assertEquals("mp4", MediaRules.videoContainer("invalid"))
        assertEquals("mkv", MediaRules.videoContainer("MKV"))
        listOf("p", "reel", "reels", "tv").forEach { path ->
            val post = "https://www.instagram.com/$path/example/"
            assertTrue(MediaRules.usesInstagramMediaApi(post, 0, false))
            assertTrue(MediaRules.usesInstagramMediaApi(post, 1, false))
            assertTrue(MediaRules.usesInstagramMediaApi(post, 2, false))
            assertFalse(MediaRules.usesInstagramMediaApi(post, 2, true))
        }
        val account = "https://www.instagram.com/account/"
        assertTrue(MediaRules.usesInstagramMediaApi(account, 0, false))
        assertTrue(MediaRules.usesInstagramMediaApi(account, 1, false))
        assertFalse(MediaRules.usesInstagramMediaApi(account, 0, true))
        listOf("https://example.com/reel/example/", "https://instagram.com.evil.example/account/",
            "https://user@instagram.com/account/", "https://instagram.com:99999/account/",
            "file://instagram.com/account/", "https://instagram.com/", "account", "").forEach {
            assertFalse(MediaRules.usesInstagramMediaApi(it, 2, false))
        }
    }

    /** 단일·동종 게시물은 즉시 진행하고 혼합 게시물과 계정 목록만 선택하도록 검증합니다. */
    @Test
    fun instagramSelectionOnlyForMixedPostsOrAccounts() {
        val post = listOf("https://www.instagram.com/reel/example/")
        assertFalse(MediaRules.requiresInstagramSelection(post, listOf(false)))
        assertFalse(MediaRules.requiresInstagramSelection(post, listOf(false, false)))
        assertFalse(MediaRules.requiresInstagramSelection(post, listOf(true)))
        assertFalse(MediaRules.requiresInstagramSelection(post, listOf(true, true)))
        assertTrue(MediaRules.requiresInstagramSelection(post, listOf(false, true)))
        assertTrue(MediaRules.requiresInstagramSelection(listOf("https://www.instagram.com/example/"), listOf(false)))
        assertTrue(MediaRules.requiresInstagramSelection(listOf("https://www.instagram.com/example/"), listOf(true, true)))
    }

    /** 동종·혼합·모두 받기와 서로 다른 게시물의 종류가 섞인 경우를 구분합니다. */
    @Test
    fun automaticMediaSelectionAndDownloadAll() {
        val url = "https://www.instagram.com/p/BA/"
        val image = InstagramMedia("1", url, "https://s.cdninstagram.com/a.jpg", "", true, "", "test")
        val video = image.copy(id = "2", image = false)
        assertFalse(MediaRules.requiresMediaSelection(listOf(url), listOf(image), false))
        assertTrue(MediaRules.requiresMediaSelection(listOf(url), listOf(video, video.copy(id = "3")), false))
        assertTrue(MediaRules.requiresMediaSelection(listOf(url), listOf(image, video), false))
        assertFalse(MediaRules.requiresMediaSelection(listOf(url), listOf(image, video), true))
        val secondUrl = "https://www.instagram.com/p/BB/"
        assertTrue(MediaRules.requiresMediaSelection(listOf(url, secondUrl), listOf(image, video.copy(postUrl = secondUrl)), false))
        assertFalse(MediaRules.requiresMediaSelection(listOf("https://www.instagram.com/test/"), listOf(image), true))
        assertTrue(MediaRules.usesSocialMediaApi("https://www.tiktok.com/@test/photo/1234", false))
        assertFalse(MediaRules.usesSocialMediaApi("https://www.tiktok.com/@test/photo/1234", true))
    }

    /** 두 목록 경로 중 일반 재생목록 입력의 유효 주소와 위험한 입력을 검사합니다. */
    @Test
    fun playlistUrlValidatesBeforeFetching() {
        val url = "https://www.youtube.com/playlist?list=PL_example"
        assertEquals(url, MediaRules.playlistUrl(" $url "))
        assertEquals("http://127.0.0.1:8080/list#items", MediaRules.playlistUrl("http://127.0.0.1:8080/list#items"))
        listOf("", "playlist", "file:///tmp/list", "javascript:alert(1)",
            "https://user:secret@example.com/list", "https://example.com:0/list",
            "https://example.com:99999/list", "https://example.com/a b").forEach {
            assertNull(MediaRules.playlistUrl(it))
        }
    }

    /** 취소·길이 불일치·대상 충돌에서는 원본을 보존하고 성공 때만 대체합니다. */
    @Test
    fun av1ReplacementPreservesOriginalOnFailure() {
        val directory = Files.createTempDirectory("seal-av1-check").toFile()
        val source = directory.resolve("source.mp4").apply { writeText("original") }
        val temporary = directory.resolve("temporary.mkv").apply { writeText("converted") }
        val destination = directory.resolve("source-av1.mkv")
        try {
            // 번역 공급자가 검증 실패 원인을 받아 안내 문구만 바꾸는지 확인합니다.
            val localizedFailure = runCatching {
                MediaRules.replaceWithAv1(source, temporary, destination, 60.0, 60.0, true,
                    failureMessage = { reason ->
                        assertEquals(MediaRules.Av1Failure.CANCELED, reason)
                        "번역된 취소 안내"
                    })
            }.exceptionOrNull()
            assertEquals("번역된 취소 안내", localizedFailure?.message)
            assertTrue(
                runCatching {
                        MediaRules.replaceWithAv1(source, temporary, destination, 60.0, 60.0, true)
                    }
                    .isFailure
            )
            assertEquals("original", source.readText())
            assertTrue(
                runCatching {
                        MediaRules.replaceWithAv1(source, temporary, destination, 60.0, 30.0, false)
                    }
                    .isFailure
            )
            assertTrue(source.isFile)
            destination.writeText("existing")
            assertTrue(
                runCatching {
                        MediaRules.replaceWithAv1(source, temporary, destination, 60.0, 60.0, false)
                    }
                    .isFailure
            )
            assertEquals("existing", destination.readText())
            assertTrue(destination.delete())
            assertEquals(
                destination,
                MediaRules.replaceWithAv1(source, temporary, destination, 60.0, 60.1, false),
            )
            assertFalse(source.exists())
            assertEquals("converted", destination.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    /** 원본 보존 모드와 계정 이름·프로필 URL의 입력 경계를 검증합니다. */
    @Test
    fun originalRetentionAndAccountInput() {
        val directory = Files.createTempDirectory("seal-av1-retain").toFile()
        val source = directory.resolve("source.mp4").apply { writeText("original") }
        val temporary = directory.resolve("temporary.mkv").apply { writeText("converted") }
        val destination = directory.resolve("source-av1.mkv")
        try {
            assertEquals(destination, MediaRules.replaceWithAv1(source, temporary, destination,
                60.0, 60.0, false, keepOriginal = true))
            assertEquals("original", source.readText())
            assertEquals("converted", destination.readText())
            assertFalse(temporary.exists())
            assertEquals("sample.name", MediaRules.instagramAccount(" @Sample.Name "))
            assertEquals("sample", MediaRules.instagramAccount("https://www.instagram.com/Sample/?igsh=share"))
            listOf("https://instagram.com.evil.example/sample/", "https://user@instagram.com/sample/",
                "https://instagram.com/p/BA/", "https://instagram.com/../", "..", "reels", "bad name")
                .forEach { assertNull(MediaRules.instagramAccount(it)) }
        } finally {
            directory.deleteRecursively()
        }
    }

    /** 실제 크기 계산과 공유 링크 변형이 같은 중복 판정을 내리는지 확인합니다. */
    @Test
    fun mediaBoundariesAndIdentity() {
        assertEquals(4.0, MediaRules.bitrateMbps(30_000_000, 60.0)!!, 0.00001)
        assertEquals("고화질", MediaRules.qualityFolder(30_000_000, 60.0, 4.0))
        assertEquals("저화질", MediaRules.qualityFolder(29_999_999, 60.0, 4.0))
        assertEquals("미분류", MediaRules.qualityFolder(30_000_000, 0.0, 4.0))
        assertNull(MediaRules.bitrateMbps(-1, 60.0))
        assertNull(MediaRules.bitrateMbps(10, Double.NaN))
        assertEquals(
            MediaRules.identity("https://youtu.be/abc?si=share"),
            MediaRules.identity("https://www.youtube.com/watch?v=abc&t=30"),
        )
        assertEquals(
            MediaRules.identity("https://www.youtube.com/shorts/abc"),
            MediaRules.identity("https://m.youtube.com/watch?v=abc"),
        )
        assertEquals(
            MediaRules.identity("https://www.instagram.com/p/AB_/"),
            MediaRules.identity("https://www.instagram.com/reel/AB_/?igsh=share"),
        )
        assertNotEquals(
            MediaRules.identity("https://www.instagram.com/p/AB_/#seal-media=1"),
            MediaRules.identity("https://www.instagram.com/p/AB_/#seal-media=2"),
        )
        assertEquals("64", MediaRules.instagramId("BA"))
        assertTrue(runCatching { MediaRules.instagramId("<script>") }.isFailure)
        assertTrue(MediaRules.accepts(0, true))
        assertFalse(MediaRules.accepts(1, true))
        assertTrue(MediaRules.accepts(2, true))
        assertEquals(720, MediaRules.videoHeight(4))
    }
}
