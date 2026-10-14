package com.plugin

import com.plugin.compatibility.enhancement.EnhancementClient
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

/** 화면 종료가 저장 중인 이미지·영상 결과를 먼저 삭제하지 않는지 확인합니다. */
class EnhancementPreviewTest {
    /** 복수 저장의 마지막 해제 뒤에만 삭제하고 닫힌 결과의 재사용을 거부합니다. */
    @Test fun disposalWaitsForActiveSaves() {
        val directory = Files.createTempDirectory("preview-lease").toFile()
        try {
            val result = File(directory, "preview.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val preview = EnhancementClient.Preview("source", directory, "hash", 2, 2, "mp4", "test", 0, video = true)
            preview.retain(); preview.retain(); preview.discard()
            assertArrayEquals(byteArrayOf(1, 2, 3), result.readBytes())
            preview.release(); assertTrue(result.isFile)
            preview.release(); assertFalse(directory.exists())
            assertTrue(runCatching { preview.retain() }.isFailure)
        } finally { directory.deleteRecursively() }
    }
}
