package com.junkfood.seal.util

import java.io.File
import java.math.BigInteger
import java.net.URI

/** 파일 관리와 게시물 선택에 공통으로 사용하는 순수 계산 규칙입니다. */
object MediaRules {
    /** 검증한 변환 파일을 확정한 후에만 원본을 제거해 취소·검증 실패 시 데이터 손실을 막습니다. */
    fun replaceWithAv1(
        source: File,
        temporary: File,
        destination: File,
        duration: Double,
        convertedDuration: Double,
        canceled: Boolean,
    ): File {
        check(!canceled) { "AV1 변환을 취소했습니다. 원본을 보존했습니다." }
        check(source.isFile && temporary.isFile && temporary.length() > 0) {
            "AV1 출력 파일을 확인하지 못했습니다. 원본을 보존했습니다."
        }
        check(
            duration > 0 &&
                convertedDuration > 0 &&
                kotlin.math.abs(convertedDuration - duration) <= 2.0
        ) {
            "AV1 출력 길이 검증에 실패했습니다. 원본을 보존했습니다."
        }
        check(!destination.exists()) { "AV1 대상 파일이 이미 존재합니다." }
        // 같은 폴더에서 이름 변경이 성공한 이후에만 원본을 제거합니다.
        check(temporary.renameTo(destination)) { "검증한 AV1 파일을 저장하지 못했습니다." }
        check(source.delete()) { "AV1 파일은 저장했지만 원본을 제거하지 못했습니다." }
        return destination
    }

    /** 크기와 재생 시간으로 평균 Mbps를 계산하며 시간 미확인 파일은 분류하지 않습니다. */
    fun bitrateMbps(bytes: Long, durationSeconds: Double): Double? =
        if (bytes > 0 && durationSeconds.isFinite() && durationSeconds > 0)
            bytes * 8.0 / durationSeconds / 1_000_000
        else null

    /** 기본 4Mbps 이상의 파일을 높은 비트레이트 그룹으로 구분합니다. */
    fun qualityFolder(bytes: Long, durationSeconds: Double, thresholdMbps: Double): String =
        bitrateMbps(bytes, durationSeconds)?.let { if (it >= thresholdMbps) "고화질" else "저화질" }
            ?: "미분류"

    /** 추적 매개변수와 공유 주소 형식 차이를 제외한 다운로드 식별자를 만듭니다. */
    fun identity(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return url
        val query =
            uri.rawQuery.orEmpty().split('&').associate {
                it.substringBefore('=') to it.substringAfter('=', "")
            }
        val path = uri.path.orEmpty().trimEnd('/')
        val id =
            when {
                host == "youtu.be" -> "youtube:${path.removePrefix("/")}"
                host == "youtube.com" || host == "m.youtube.com" ->
                    query["v"]?.let { "youtube:$it" }
                        ?: if (path.startsWith("/shorts/") || path.startsWith("/live/"))
                            "youtube:${path.substringAfterLast('/')}"
                        else "$host$path?${uri.rawQuery.orEmpty()}"
                host == "instagram.com" && Regex("^/(p|reel|reels|tv)/[^/]+$").matches(path) ->
                    "instagram:${path.substringAfterLast('/')}"
                else -> "$host$path?${uri.rawQuery.orEmpty()}"
            }
        // 혼합 게시물의 각 이미지·영상은 서로 다른 파일로 취급합니다.
        return id + uri.fragment.orEmpty().takeIf { it.startsWith("seal-media=") }.orEmpty()
    }

    /** 인스타그램 게시물 단축 코드를 서버의 숫자 미디어 ID로 변환합니다. */
    fun instagramId(shortcode: String): String {
        require(shortcode.isNotEmpty() && shortcode.length <= 32)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return shortcode
            .fold(BigInteger.ZERO) { value, char ->
                val digit = alphabet.indexOf(char)
                require(digit >= 0) { "올바른 인스타그램 게시물 주소가 아닙니다." }
                value.shiftLeft(6).add(BigInteger.valueOf(digit.toLong()))
            }
            .toString()
    }

    /** 모두/영상만/이미지만 선택에 맞는 미디어인지 확인합니다. */
    fun accepts(selection: Int, image: Boolean): Boolean =
        when (selection) {
            1 -> !image
            2 -> image
            else -> true
        }

    /** 기존 영상 설정의 선택 인덱스를 실제 높이로 변환합니다. */
    fun videoHeight(selection: Int): Int =
        when (selection) {
            1 -> 2160
            2 -> 1440
            3 -> 1080
            4 -> 720
            5 -> 480
            6 -> 360
            7 -> 1
            else -> 0
        }
}
