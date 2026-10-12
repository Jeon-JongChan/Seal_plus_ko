package com.plugin

import com.plugin.compatibility.sortMedia
import org.junit.Assert.assertEquals
import org.junit.Test

/** 두 화면이 사용하는 정렬과 이름 동률의 최신순 처리를 검증합니다. */
class MediaOrderingTest {
    /** 정렬해도 입력 순서와 원본 데이터는 변경되지 않습니다. */
    @Test fun sortsByTimeAndCaseInsensitiveNameWithoutMutatingInput() {
        val input = listOf(3L to "z", 1L to "a", 2L to "A")
        assertEquals(listOf(3L, 2L, 1L), input.sortMedia(false, { it.first }, { it.second }).map { it.first })
        assertEquals(listOf(2L, 1L, 3L), input.sortMedia(true, { it.first }, { it.second }).map { it.first })
        assertEquals(listOf(3L, 1L, 2L), input.map { it.first })
        assertEquals(listOf(1L, 3L), listOf(3L to "나", 1L to "가").sortMedia(true, { it.first }, { it.second }).map { it.first })
    }
}
