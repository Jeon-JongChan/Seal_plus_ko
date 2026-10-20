package com.plugin.compatibility

/** 화면 항목만 최신순 또는 이름순으로 정렬하며 실행 대기열은 변경하지 않습니다. */
fun <T> Iterable<T>.sortMedia(byName: Boolean, time: (T) -> Long, name: (T) -> String): List<T> =
    // 게시물의 최대 시각처럼 비싼 정렬 키도 항목당 한 번만 계산합니다.
    map { Triple(it, time(it), name(it)) }.sortedWith(
        if (byName) compareBy<Triple<T, Long, String>, String>(String.CASE_INSENSITIVE_ORDER) { it.third }
            .thenByDescending { it.second }
        else compareByDescending<Triple<T, Long, String>> { it.second }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.third }
    ).map { it.first }
