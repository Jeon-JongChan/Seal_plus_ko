package com.plugin.compatibility

/** 화면 항목만 최신순 또는 이름순으로 정렬하며 실행 대기열은 변경하지 않습니다. */
fun <T> Iterable<T>.sortMedia(byName: Boolean, time: (T) -> Long, name: (T) -> String): List<T> =
    sortedWith(if (byName) compareBy(String.CASE_INSENSITIVE_ORDER, name).thenByDescending(time)
        else compareByDescending(time).thenBy(String.CASE_INSENSITIVE_ORDER, name))
