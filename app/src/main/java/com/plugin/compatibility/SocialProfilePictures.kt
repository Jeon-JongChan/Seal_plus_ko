package com.plugin.compatibility

import com.junkfood.seal.App
import com.junkfood.seal.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.junkfood.seal.util.DownloadUtil

/** 기존 공개 페이지 전송·쿠키·프록시 처리와 계정 사진 조회를 공통 기반에서 연결합니다. */
object SocialProfilePictures {
    private val context get() = App.context
    private const val PUBLIC_PAGE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val calls = ConcurrentHashMap<String, Call>()

    /** 요청 ID가 일치하는 공개 페이지 조회만 취소합니다. */
    fun cancel(taskId: String): Boolean = calls[taskId]?.let { it.cancel(); true } ?: false

    /** 기존 프록시·IPv4 설정을 공통 전송 계층에서 그대로 적용합니다. */
    fun clientFor(preferences: DownloadUtil.DownloadPreferences): OkHttpClient {
        if (!preferences.proxy && !preferences.forceIpv4) return client
        return client
            .newBuilder()
            .apply {
                if (preferences.proxy) {
                    val uri = URI(preferences.proxyUrl)
                    require(
                        uri.host != null &&
                            uri.userInfo == null &&
                            uri.scheme in setOf("http", "socks", "socks5", "socks5h")
                    ) {
                        com.junkfood.seal.App.context.getString(R.string.loc_instagram_proxy_hint)
                    }
                    proxy(
                        Proxy(
                            if (uri.scheme == "http") Proxy.Type.HTTP else Proxy.Type.SOCKS,
                            InetSocketAddress.createUnresolved(
                                uri.host,
                                if (uri.port > 0) uri.port
                                else if (uri.scheme == "http") 80 else 1080,
                            ),
                        )
                    )
                }
                if (preferences.forceIpv4)
                    dns(
                        /** :의 독립 플러그인 처리를 제공합니다. */
                        object : Dns {
                            /** IPv4 주소만 엔진에 전달합니다. */
                            override fun lookup(hostname: String) =
                                Dns.SYSTEM.lookup(hostname).filterIsInstance<Inet4Address>()
                        }
                    )
            }
            .build()
    }

    /** 실제 쿠키를 매번 다시 읽고 리다이렉트로 다른 호스트에 전달하지 않습니다. */
    suspend fun publicPage(url: String, preferences: DownloadUtil.DownloadPreferences, taskId: String,
        httpClient: OkHttpClient = clientFor(preferences), jsonResponse: Boolean = false): Pair<String, String> {
        currentCoroutineContext().ensureActive()
        val call = httpClient.newCall(Request.Builder()
            .url(url)
            // 단순 페이지 요청에 없던 문서 탐색 헤더를 전달해 공개 게시물 데이터를 조회합니다.
            .header("User-Agent", preferences.userAgentString.ifBlank { PUBLIC_PAGE_USER_AGENT })
            .header("Accept", if (jsonResponse) "application/json" else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .apply {
                if (jsonResponse) {
                    header("X-IG-App-ID", "936619743392459")
                    header("Referer", "https://www.instagram.com/")
                    header("Sec-Fetch-Dest", "empty")
                    header("Sec-Fetch-Mode", "cors")
                    header("Sec-Fetch-Site", "same-origin")
                    removeHeader("Sec-Fetch-User")
                    removeHeader("Upgrade-Insecure-Requests")
                }
                if (preferences.cookies && url.toHttpUrl().host in setOf("instagram.com", "www.instagram.com")) {
                    val session = CookieRules.header(com.plugin.runtime.PluginDownloadUtil.cookieContent(preferences.cookieProfileId), url.toHttpUrl())
                    if (session.isNotEmpty()) header("Cookie", session.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }
            .build())
        calls[taskId] = call
        return try {
            call.execute().use { response ->
                check(response.isSuccessful) { context.getString(R.string.loc_media_fetch_failed) }
                currentCoroutineContext().ensureActive()
                response.body?.string().orEmpty() to response.request.url.toString()
            }
        } finally { calls.remove(taskId, call) }
    }

    /** 기존 공개 문서 파서를 공유하며 응답 크기를 제한합니다. */
    internal fun documents(html: String): Sequence<JsonElement> =
        if (html.length > 16_000_000) emptySequence() else listOfNotNull(
            runCatching { Json.parseToJsonElement(html) }.getOrNull()).asSequence() +
            Regex("""<script\b[^>]*>(\{.*?\})</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(html).mapNotNull { runCatching { Json.parseToJsonElement(it.groupValues[1]) }.getOrNull() }

    /** 공개 문서에서 요청한 계정의 실제 사진만 찾고 중첩 깊이를 제한합니다. */
    internal fun parseProfilePicture(html: String, username: String): String? {
        /** 다른 게시글 작성자나 로그인 안내 이미지와 요청한 계정을 혼동하지 않습니다. */
        fun picture(node: JsonElement, depth: Int = 0): String? {
            if (depth > 64) return null
            if (node is JsonObject) {
                if ((node["username"] as? JsonPrimitive)?.contentOrNull?.equals(username, true) == true) {
                    val hd = ((node["hd_profile_pic_url_info"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (SocialPostMetadataStore.validProfilePicture(hd)) return hd
                    listOf("profile_pic_url_hd", "profile_pic_url").forEach { key ->
                        val url = (node[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (SocialPostMetadataStore.validProfilePicture(url)) return url
                    }
                }
                return node.values.firstNotNullOfOrNull { picture(it, depth + 1) }
            }
            // Relay 응답은 계정 JSON을 문자열에 한 번 더 감싸 전달하기도 합니다.
            if (node is JsonPrimitive && node.isString) {
                val nested = node.content.trim()
                if (nested.startsWith('{') || nested.startsWith('['))
                    runCatching { Json.parseToJsonElement(nested) }.getOrNull()?.let { return picture(it, depth + 1) }
            }
            return (node as? JsonArray)?.firstNotNullOfOrNull { picture(it, depth + 1) }
        }
        return documents(html).firstNotNullOfOrNull { picture(it) }
    }

    /** 공개 페이지에 계정 사진이 없으면 동일 쿠키·프록시의 프로필 JSON 응답으로 보완합니다. */
    suspend fun profilePicture(username: String, preferences: DownloadUtil.DownloadPreferences): String? =
        withContext(Dispatchers.IO) {
            require(username.matches(Regex("[A-Za-z0-9._]{1,30}")) && username !in setOf(".", ".."))
            val taskId = "profile-picture:" + java.util.UUID.randomUUID()
            try {
                val page = try { publicPage("https://www.instagram.com/$username/", preferences, taskId).first }
                    catch (_: Exception) { currentCoroutineContext().ensureActive(); "" }
                parseProfilePicture(page, username) ?: parseProfilePicture(publicPage(
                    "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username", preferences, taskId,
                    jsonResponse = true).first, username)
            }
            finally { cancel(taskId) }
        }

}
