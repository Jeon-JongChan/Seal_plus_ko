package com.plugin.compatibility

import okhttp3.Cookie
import okhttp3.HttpUrl

/** Netscape 쿠키를 한 번 검증하고 기존 OkHttp의 도메인·경로·보안 검사를 재사용합니다. */
object CookieRules {
    private const val WINDOWS_EPOCH_SECONDS = 11_644_473_600L
    private const val MAX_UNIX_SECONDS = 253_402_300_799L

    /** WebView의 1601년 기준 마이크로초를 Netscape의 Unix 초로 변환합니다. */
    fun webViewExpiry(value: Long): Long = if (value == 0L) 0 else value / 1_000_000 - WINDOWS_EPOCH_SECONDS

    /** HttpOnly 행·세션 쿠키를 보존하고 만료·잘못된 값·중복된 구형 값은 제외합니다. */
    fun parse(content: String, now: Long = System.currentTimeMillis()): List<Cookie> {
        val records = linkedMapOf<Triple<String, String, String>, Cookie>()
        content.lineSequence().forEach { line ->
            val raw = line.trimEnd('\r')
            if (raw.startsWith('#') && !raw.startsWith("#HttpOnly_")) return@forEach
            val fields = raw.removePrefix("#HttpOnly_").split('\t', limit = 7)
            if (fields.size != 7 || fields[1] !in setOf("TRUE", "FALSE") || fields[3] !in setOf("TRUE", "FALSE")) return@forEach
            if (fields.any { it.any(Char::isISOControl) } || fields[5].isBlank() || !fields[5].matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) || fields[6].any { it.code !in 0x21..0x7e || it == ';' }) return@forEach
            val encodedExpiry = fields[4].toLongOrNull() ?: return@forEach
            // 구형 Seal이 직접 내보낸 Chromium 시간 값도 같은 초 단위로 복구합니다.
            val expiry = if (encodedExpiry > MAX_UNIX_SECONDS) webViewExpiry(encodedExpiry) else encodedExpiry
            if (expiry < 0 || expiry > MAX_UNIX_SECONDS || expiry != 0L && expiry <= now / 1000) return@forEach
            val cookie = runCatching {
                Cookie.Builder().name(fields[5]).value(fields[6]).path(fields[2]).apply {
                    val domain = fields[0].removePrefix(".")
                    if (fields[1] == "TRUE") domain(domain) else hostOnlyDomain(domain)
                    if (fields[3] == "TRUE") secure()
                    if (raw.startsWith("#HttpOnly_")) httpOnly()
                    if (expiry != 0L) expiresAt(expiry * 1000)
                }.build()
            }.getOrNull() ?: return@forEach
            records[Triple(cookie.domain, cookie.path, cookie.name)] = cookie
        }
        return records.values.toList()
    }

    /** yt-dlp에도 동일하게 검증된 Netscape 형식을 전달합니다. */
    fun normalized(content: String): String {
        val cookies = parse(content)
        require(cookies.isNotEmpty()) { "사용 가능한 쿠키가 없습니다. 쿠키 로그인 또는 프로필을 갱신하세요." }
        return "# Netscape HTTP Cookie File\n" + cookies.joinToString("\n", postfix = "\n") {
            (if (it.httpOnly) "#HttpOnly_" else "") + listOf(if (it.hostOnly) it.domain else ".${it.domain}",
                (!it.hostOnly).toString().uppercase(), it.path, it.secure.toString().uppercase(),
                if (it.persistent) (it.expiresAt / 1000).toString() else "0", it.name, it.value).joinToString("\t")
        }
    }

    /** 다른 사이트·하위 호스트·경로·HTTP에 쿠키가 섞이지 않게 실제 요청 URL로 제한합니다. */
    fun header(content: String, url: HttpUrl): Map<String, String> = parse(content).filter { it.matches(url) }
        .sortedWith(compareBy<Cookie> { it.path.length }.thenBy { it.domain.length }).associate { it.name to it.value }
}
