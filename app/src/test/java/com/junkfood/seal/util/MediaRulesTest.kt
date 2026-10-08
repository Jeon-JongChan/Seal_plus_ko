package com.junkfood.seal.util

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** 화질 경계값·링크 동등성·캐러셀 분리·잘못된 입력을 함께 검증합니다. */
class MediaRulesTest {
    /** 취소·길이 불일치·대상 충돌에서는 원본을 보존하고 성공 때만 대체합니다. */
    @Test
    fun av1ReplacementPreservesOriginalOnFailure() {
        val directory = Files.createTempDirectory("seal-av1-check").toFile()
        val source = directory.resolve("source.mp4").apply { writeText("original") }
        val temporary = directory.resolve("temporary.mkv").apply { writeText("converted") }
        val destination = directory.resolve("source-av1.mkv")
        try {
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
