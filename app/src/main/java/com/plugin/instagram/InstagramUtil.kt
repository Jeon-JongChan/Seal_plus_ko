package com.plugin.instagram

import com.plugin.compatibility.InstagramMedia

import com.plugin.compatibility.selectedFolder
import com.plugin.compatibility.useWebsiteFolder
import com.plugin.compatibility.copyPreservingPluginOptions
import com.plugin.compatibility.*
import com.plugin.tiktok.TikTokUtil
import com.plugin.compatibility.MediaLibrary
import com.plugin.compatibility.MediaRules
import com.junkfood.seal.util.*
import com.junkfood.seal.R
import android.media.MediaScannerConnection
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.FileUtil.getSdcardTempDir
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** 로그인 쿠키 사용을 명시적으로 선택한 경우에만 인스타그램 API에 쿠키를 전달합니다. */
object InstagramUtil {
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    // ponytail: 계정별 제한을 알 수 없어 모든 목록 요청을 6초 간격으로 보냅니다. 처리량이 필요하면 서버 응답에 맞춰 조정합니다.
    private val requestGate = Mutex()
    private var lastRequestAt = 0L
    private val calls = ConcurrentHashMap<String, Call>()
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val PUBLIC_PAGE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 직접 이미지·목록 요청에도 사용자가 지정한 프록시와 IPv4 설정을 적용합니다. */
    internal fun clientFor(preferences: DownloadUtil.DownloadPreferences): OkHttpClient {
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

    /** 처리 가능한 인스타그램 게시물·계정 주소인지 확인합니다. */
    fun accepts(url: String): Boolean =
        runCatching {
                MediaRules.usesSocialMediaApi(url, false)
            }
            .getOrDefault(false)

    /** HTTP 요청을 취소합니다. */
    fun cancel(taskId: String): Boolean =
        calls[taskId]?.let {
            it.cancel()
            true
        } ?: false

    /** 공용 응답에서 객체를 안전하게 읽습니다. */
    private fun JsonObject.obj(key: String): JsonObject =
        this[key] as? JsonObject ?: JsonObject(emptyMap())

    /** 공용 응답에서 배열을 안전하게 읽습니다. */
    private fun JsonObject.array(key: String): JsonArray =
        this[key] as? JsonArray ?: JsonArray(emptyList())

    /** 문자열·숫자 식별자를 문자열로 읽습니다. */
    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 쿠키 파일에서 만료되지 않은 인스타그램 쿠키만 읽습니다. */
    internal fun cookies(preferences: DownloadUtil.DownloadPreferences, host: String = "www.instagram.com", path: String = "/"): Map<String, String> {
        if (!preferences.cookies) return emptyMap()
        return com.plugin.compatibility.CookieRules.header(com.plugin.runtime.PluginDownloadUtil.cookieContent(preferences.cookieProfileId),
            ("https://$host$path").toHttpUrl())
    }

    /** 리다이렉트·권한·속도 제한 오류를 숨기지 않고 고정된 인스타그램 호스트만 조회합니다. */
    internal suspend fun request(
        endpoint: String,
        parameters: Map<String, String>,
        preferences: DownloadUtil.DownloadPreferences,
        taskId: String,
        post: Boolean = false,
        graphql: Boolean = false,
        httpClient: OkHttpClient = clientFor(preferences),
        referer: String = "https://www.instagram.com/",
        csrfToken: String? = null,
    ): JsonObject {
        currentCoroutineContext().ensureActive()
        val url =
            (if (graphql) "https://www.instagram.com/api/graphql" else "https://www.instagram.com/api/v1/$endpoint")
                .toHttpUrl()
                .newBuilder()
                .apply {
                    if (!post) parameters.forEach { (key, value) -> addQueryParameter(key, value) }
                }
                .build()
        val cookie = cookies(preferences, url.host, url.encodedPath)
        if (preferences.cookies) check(!cookie["sessionid"].isNullOrBlank()) {
            com.junkfood.seal.App.context.getString(R.string.loc_instagram_cookie_missing)
        }
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", preferences.userAgentString.ifBlank { PUBLIC_PAGE_USER_AGENT })
                .header("Referer", referer)
                .header("X-IG-App-ID", "936619743392459")
                .header("X-Requested-With", "XMLHttpRequest")
                // 실제 세션에서 받지 않은 claim을 보내면 유효한 로그인 쿠키도 거부될 수 있습니다.
                .header("Accept", "*/*")
                .apply {
                    if (graphql) {
                        header("X-FB-Friendly-Name", "PolarisLoggedOutDesktopWWWPostRootContentQuery")
                        parameters["lsd"]?.takeIf { it.isNotBlank() }?.let { header("X-FB-LSD", it) }
                    }
                    if (post)
                        post(
                            FormBody.Builder()
                                .apply { parameters.forEach { (key, value) -> add(key, value) } }
                                .build()
                        )
                    if (cookie.isNotEmpty())
                        header(
                            "Cookie",
                            cookie.entries.joinToString("; ") { "${it.key}=${it.value}" },
                        )
                    (cookie["csrftoken"] ?: csrfToken)?.let { header("X-CSRFToken", it) }
                }
                .build()
        requestGate.withLock {
            val elapsed = android.os.SystemClock.elapsedRealtime() - lastRequestAt
            if (lastRequestAt != 0L && elapsed < 6000L) delay(6000L - elapsed)
            lastRequestAt = android.os.SystemClock.elapsedRealtime()
        }
        val call = httpClient.newCall(request)
        calls[taskId] = call
        try {
            return call.execute().use { response ->
                check(response.isSuccessful) {
                    when (response.code) {
                        429 -> response.header("Retry-After")?.toLongOrNull()?.let {
                            context.getString(R.string.loc_instagram_retry_after, it)
                        } ?: context.getString(R.string.loc_instagram_rate_limited)
                        401,
                        403,
                        302 -> com.junkfood.seal.App.context.getString(R.string.loc_instagram_access_denied)
                        else -> com.junkfood.seal.App.context.getString(R.string.loc_instagram_fetch_failed, response.code)
                    }
                }
                val body = response.body?.string().orEmpty()
                val result = runCatching { Json.parseToJsonElement(body.removePrefix("for (;;);")).jsonObject }.getOrElse {
                    error(com.junkfood.seal.App.context.getString(R.string.loc_instagram_auth_page))
                }
                check(result.text("status") != "fail") {
                    when (result.text("message")) {
                        "login_required" -> com.junkfood.seal.App.context.getString(R.string.loc_instagram_login_required)
                        "challenge_required", "checkpoint_required" -> com.junkfood.seal.App.context.getString(R.string.loc_instagram_challenge_required)
                        "feedback_required", "Please wait a few minutes before you try again." -> com.junkfood.seal.App.context.getString(R.string.loc_instagram_temporarily_limited)
                        else -> com.junkfood.seal.App.context.getString(R.string.loc_instagram_request_denied)
                    }
                }
                currentCoroutineContext().ensureActive()
                result
            }
        } finally {
            calls.remove(taskId, call)
        }
    }

    /** 공개 페이지의 내장 데이터와 로그인 없는 조회를 사용하며 로그인 쿠키를 강제하지 않습니다. */
    private suspend fun fetchPublicPost(code: String, preferences: DownloadUtil.DownloadPreferences, taskId: String): List<InstagramMedia> {
        val pageUrl = "https://www.instagram.com/p/$code/"
        val anonymousCookies = mutableListOf<Cookie>()
        // 로그인 쿠키 저장소와 분리하고 이번 게시물 요청 동안만 서버가 발급한 세션을 유지합니다.
        val anonymousClient = clientFor(preferences).newBuilder().cookieJar(object : CookieJar {
            /** 같은 이름·도메인·경로의 서버 쿠키만 최신 값으로 교체합니다. */
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
                cookies.forEach { cookie ->
                    anonymousCookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                    anonymousCookies.add(cookie)
                }
            }
            /** 요청 호스트에 일치하는 만료되지 않은 익명 쿠키만 전송합니다. */
            override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
                anonymousCookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        }).build()
        val html = publicPage(pageUrl, preferences, taskId, anonymousClient).first
        val embedded = parsePublicPage(html, code, preferences.imageResolution, MediaRules.videoHeight(preferences.videoResolution), collectMetadata = true)
        if (embedded.isNotEmpty()) return embedded
        val lsd = Regex("""\["LSD",\[\],\{"token":"([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
        val response = request("", mapOf("lsd" to lsd, "fb_api_caller_class" to "RelayModern",
            "fb_api_req_friendly_name" to "PolarisLoggedOutDesktopWWWPostRootContentQuery",
            "server_timestamps" to "true", "doc_id" to "27130156389949648",
            "variables" to buildJsonObject { put("media_id", MediaRules.instagramId(code)) }.toString()),
            preferences, taskId, post = true, graphql = true, httpClient = anonymousClient, referer = pageUrl,
            csrfToken = anonymousCookies.firstOrNull { it.name == "csrftoken" && it.matches(pageUrl.toHttpUrl()) }?.value)
        val post = publicPost(response) ?: error(context.getString(R.string.loc_instagram_access_denied))
        return parsePosts(JsonArray(listOf(normalizePublicPost(post, code))), preferences.imageResolution,
            MediaRules.videoHeight(preferences.videoResolution), collectMetadata = true).also {
            check(it.isNotEmpty()) { context.getString(R.string.loc_instagram_no_media) }
        }
    }

    /** 기존 공개 문서 요청과 취소 처리를 인스타그램·틱톡에서 함께 사용합니다. */
    internal suspend fun publicPage(url: String, preferences: DownloadUtil.DownloadPreferences, taskId: String,
        httpClient: OkHttpClient = clientFor(preferences)): Pair<String, String> {
        currentCoroutineContext().ensureActive()
        val call = httpClient.newCall(Request.Builder()
            .url(url)
            // 단순 페이지 요청에 없던 문서 탐색 헤더를 전달해 공개 게시물 데이터를 조회합니다.
            .header("User-Agent", preferences.userAgentString.ifBlank { PUBLIC_PAGE_USER_AGENT })
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .apply {
                if (preferences.cookies && url.toHttpUrl().host in setOf("instagram.com", "www.instagram.com")) {
                    val session = cookies(preferences, url.toHttpUrl().host, url.toHttpUrl().encodedPath)
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

    /** 공개 페이지의 JSON 스크립트만 읽으며 로그인 HTML을 미디어로 오인하지 않습니다. */
    internal fun parsePublicPage(html: String, code: String, imageResolution: Int, videoResolution: Int, collectMetadata: Boolean = false): List<InstagramMedia> {
        return publicDocuments(html)
            .mapNotNull { publicPost(it) }.firstOrNull()?.let {
                parsePosts(JsonArray(listOf(normalizePublicPost(it, code))), imageResolution, videoResolution, collectMetadata)
            }.orEmpty()
    }

    /** 크기를 제한한 공개 문서의 JSON 스크립트를 기존 게시물·프로필 파서가 함께 사용합니다. */
    private fun publicDocuments(html: String): Sequence<JsonElement> =
        if (html.length > 16_000_000) emptySequence() else
            Regex("""<script\b[^>]*>(\{.*?\})</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(html).mapNotNull { runCatching { Json.parseToJsonElement(it.groupValues[1]) }.getOrNull() }

    /** 로그인 없는 프로필 격자의 게시물 링크를 읽으며 미리보기 이미지를 원본 파일로 사용하지 않습니다. */
    internal fun parsePublicProfilePage(html: String, username: String): Pair<List<String>, Boolean> {
        val connection = publicDocuments(html).mapNotNull {
            publicPost(it, keys = listOf("polaris_ordered_timeline_connection", "edge_owner_to_timeline_media",
                "xdt_api__v1__feed__user_timeline_graphql_connection"))
        }.firstOrNull() ?: return emptyList<String>() to false
        val urls = connection.array("edges").mapNotNull { edge ->
            val node = (edge as? JsonObject)?.obj("node") ?: return@mapNotNull null
            val owner = node.obj("user").text("username").ifEmpty { node.obj("owner").text("username") }
            if (owner.isNotEmpty() && !owner.equals(username, ignoreCase = true)) return@mapNotNull null
            val code = node.text("code").ifEmpty { node.text("shortcode") }
            if (!code.matches(Regex("[A-Za-z0-9_-]+"))) return@mapNotNull null
            "https://www.instagram.com/${if (node.text("product_type") == "clips") "reel" else "p"}/$code/"
        }.distinct()
        return urls to ((connection.obj("page_info")["has_next_page"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    /** 프로필 문서의 실제 게시물을 기존 조회로 분석하며 쿠키는 사용자가 켠 경우에만 사용합니다. */
    internal suspend fun fetchProfile(username: String, preferences: DownloadUtil.DownloadPreferences, taskId: String): List<InstagramMedia> {
        val (urls, more) = parsePublicProfilePage(publicPage("https://www.instagram.com/$username/", preferences, taskId).first, username)
        check(urls.isNotEmpty()) { context.getString(R.string.loc_public_profile_unavailable) }
        val result = mutableListOf<InstagramMedia>()
        var firstError: Throwable? = null
        var inspected = 0
        for (url in urls) {
            currentCoroutineContext().ensureActive()
            // 기존 단일 게시물 함수가 사용자의 쿠키 사용·미사용 선택에 맞는 조회 경로를 결정합니다.
            runCatching { fetch(url, preferences, taskId) }
                .onSuccess { media -> result += media.map { it.copy(reel = it.reel || "/reel/" in url) }; inspected++ }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    if (firstError == null) firstError = error
                }
        }
        if (result.isEmpty()) throw (firstError ?: IllegalStateException(context.getString(R.string.loc_public_profile_unavailable)))
        // 공개 격자는 전체 계정 목록이 아닐 수 있으므로 조회한 범위를 전체로 오인하지 않도록 안내합니다.
        if (more || inspected < urls.size) ToastUtil.makeToastSuspend(context.getString(R.string.loc_public_profile_partial, inspected))
        return result.distinctBy { it.id }
    }

    /** 공개 응답에서 게시물 객체만 찾아 기존 REST 미디어 파서에 전달합니다. */
    private fun publicPost(element: JsonElement, depth: Int = 0,
        keys: List<String> = listOf("if_not_gated_logged_out", "shortcode_media", "xdt_shortcode_media")): JsonObject? {
        if (depth > 64) return null
        return when (element) {
            is JsonObject -> keys
                .firstNotNullOfOrNull { (element[it] as? JsonObject)?.takeIf { it.isNotEmpty() } }
                ?: element.values.firstNotNullOfOrNull { publicPost(it, depth + 1, keys) }
            is JsonArray -> element.firstNotNullOfOrNull { publicPost(it, depth + 1, keys) }
            else -> null
        }
    }

    /** 공개 GraphQL 응답을 기존 이미지·영상 후보 선택 형식으로 맞춥니다. */
    private fun normalizePublicPost(post: JsonObject, code: String): JsonObject {
        val legacy = post.containsKey("display_url") || post.containsKey("edge_sidecar_to_children")
        if (!legacy) return JsonObject(post + ("code" to JsonPrimitive(code)))
        /** 기존 파서가 사용할 단일 공개 미디어 후보를 생성합니다. */
        fun media(node: JsonObject): JsonObject = buildJsonObject {
            put("pk", node.text("id")); put("video_duration", node["video_duration"] ?: JsonPrimitive(0))
            val dimensions = node.obj("dimensions")
            val image = buildJsonObject { put("url", node.text("display_url")); put("width", dimensions["width"] ?: JsonPrimitive(0)); put("height", dimensions["height"] ?: JsonPrimitive(0)) }
            put("image_versions2", buildJsonObject { put("candidates", JsonArray(listOf(image))) })
            if (node.text("video_url").isNotEmpty()) put("video_versions", JsonArray(listOf(JsonObject(image + ("url" to JsonPrimitive(node.text("video_url")))))))
        }
        return JsonObject(media(post) + buildJsonObject {
            put("code", code); put("user", post.obj("owner"))
            put("edge_media_to_caption", post.obj("edge_media_to_caption"))
            val children = post.obj("edge_sidecar_to_children").array("edges").mapNotNull { (it as? JsonObject)?.get("node") as? JsonObject }
            if (children.isNotEmpty()) put("carousel_media", JsonArray(children.map { media(it) }))
        })
    }

    /** 단일 게시물 또는 공개 프로필 문서에 표시된 게시물을 페이지 순서로 조회합니다. */
    suspend fun fetch(
        url: String,
        preferences: DownloadUtil.DownloadPreferences,
        taskId: String,
    ): List<InstagramMedia> =
        withContext(Dispatchers.IO) {
            if (TikTokUtil.accepts(url)) return@withContext TikTokUtil.fetch(url, preferences, taskId)
            require(accepts(url)) { com.junkfood.seal.App.context.getString(R.string.loc_instagram_invalid_url) }
            val segments = url.toHttpUrl().pathSegments.filter { it.isNotEmpty() }
            if (segments.first() in setOf("p", "reel", "reels", "tv")) {
                    val code = segments.getOrNull(1) ?: error(com.junkfood.seal.App.context.getString(R.string.loc_instagram_invalid_post))
                    if (!preferences.cookies) return@withContext fetchPublicPost(code, preferences, taskId)
                    val response =
                        request(
                            "media/${runCatching { MediaRules.instagramId(code) }.getOrElse { error(context.getString(R.string.loc_instagram_invalid_post)) }}/info/",
                            emptyMap(),
                            preferences,
                            taskId,
                        )
                    parsePosts(
                        response.array("items"),
                        preferences.imageResolution,
                        MediaRules.videoHeight(preferences.videoResolution),
                        collectMetadata = true,
                    )
                } else {
                    val username = segments.first().removePrefix("@")
                    require(Regex("[A-Za-z0-9._]{1,30}").matches(username)) { com.junkfood.seal.App.context.getString(R.string.loc_instagram_invalid_account) }
                    fetchProfile(username, preferences, taskId)
                }
                .also { check(it.isNotEmpty()) { com.junkfood.seal.App.context.getString(R.string.loc_instagram_no_media) } }
        }

    /** REST 게시물 응답의 캐러셀을 이미지·영상 파일 단위로 펼칩니다. */
    internal fun parsePosts(
        posts: JsonArray,
        imageResolution: Int,
        videoResolution: Int,
        collectMetadata: Boolean = false,
    ): List<InstagramMedia> =
        posts.flatMap { element ->
            val post = element as? JsonObject ?: return@flatMap emptyList()
            val code = post.text("code")
            if (code.isEmpty()) return@flatMap emptyList()
            val author =
                post.obj("user").text("username").takeIf {
                    it.matches(Regex("[A-Za-z0-9._]{1,30}")) && it !in setOf(".", "..")
                } ?: "Instagram"
            // 순수 파서는 저장소를 초기화하지 않고 실제 조회에서만 플러그인 정보를 저장합니다.
            if (collectMetadata) SocialPostMetadataStore.collectInstagramPost(post)
            val children =
                post.array("carousel_media").takeIf { it.isNotEmpty() } ?: JsonArray(listOf(post))
            children.mapNotNull { child ->
                val item = child as? JsonObject ?: return@mapNotNull null
                val id = item.text("pk").ifEmpty { item.text("id").substringBefore('_') }
                if (!id.matches(Regex("[0-9]+"))) return@mapNotNull null
                val videos = item.array("video_versions").mapNotNull { it as? JsonObject }
                val images =
                    item.obj("image_versions2").array("candidates").mapNotNull { it as? JsonObject }
                val image = videos.isEmpty()
                val candidates = if (image) images else videos
                val resolution = if (image) imageResolution else videoResolution
                val ordered =
                    candidates.sortedByDescending {
                        it.text("width").toLongOrNull().orZero() *
                            it.text("height").toLongOrNull().orZero()
                    }
                val selected =
                    if (resolution > 0)
                        ordered.firstOrNull {
                            it.text("height").toIntOrNull().orZero() <= resolution
                        } ?: ordered.lastOrNull()
                    else ordered.firstOrNull()
                val mediaUrl = selected?.text("url") ?: return@mapNotNull null
                if (!safeMediaUrl(mediaUrl)) return@mapNotNull null
                InstagramMedia(
                    id,
                    "https://www.instagram.com/${if (post.text("product_type") == "clips") "reel" else "p"}/$code/",
                    mediaUrl,
                    images.firstOrNull()?.text("url").orEmpty(),
                    image,
                    post.obj("caption").text("text").take(160).ifEmpty { "$author · $code" },
                    author,
                    item.text("video_duration").toDoubleOrNull() ?: 0.0,
                    carousel = children.size > 1,
                    // 공개 응답의 첫 원본 후보에는 크기가 없을 수 있어 게시물 원본 크기로 보충합니다.
                    // 축소·잘라낸 다른 후보에 원본 크기를 적용하면 실제 파일과 달라지므로 보충하지 않습니다.
                    width = selected.text("width").toIntOrNull() ?:
                        item.text("original_width").toIntOrNull()?.takeIf { selected == candidates.firstOrNull() } ?: 0,
                    height = selected.text("height").toIntOrNull() ?:
                        item.text("original_height").toIntOrNull()?.takeIf { selected == candidates.firstOrNull() } ?: 0,
                    pageIndex = children.indexOf(child), pageCount = children.size,
                    reel = post.text("product_type") == "clips",
                )
            }
        }

    /** 정수 메타데이터 누락을 0으로 처리합니다. */
    private fun Long?.orZero(): Long = this ?: 0

    /** 해상도 메타데이터 누락을 0으로 처리합니다. */
    private fun Int?.orZero(): Int = this ?: 0

    /** 미디어 다운로드는 HTTPS의 인스타그램 CDN으로 제한합니다. */
    internal fun safeMediaUrl(url: String): Boolean =
        runCatching {
                val parsed = url.toHttpUrl()
                parsed.isHttps &&
                    (parsed.host.endsWith(".cdninstagram.com") ||
                        parsed.host.endsWith(".fbcdn.net") || TikTokUtil.safeMediaUrl(url)) &&
                    parsed.username.isEmpty() &&
                    parsed.password.isEmpty()
            }
            .getOrDefault(false)

    /** 만료될 수 있는 CDN 주소를 재조회하고 선택한 이미지는 안전하게 저장합니다. */
    suspend fun download(
        media: InstagramMedia,
        preferences: DownloadUtil.DownloadPreferences,
        taskId: String,
        progress: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // 분석 직후의 CDN 주소는 그대로 사용해 다운로드마다 로그인 API를 다시 호출하지 않습니다.
                val current = if (safeMediaUrl(media.url)) media else
                    fetch(media.postUrl, preferences, taskId).firstOrNull { it.id == media.id }
                        ?: error(com.junkfood.seal.App.context.getString(R.string.loc_instagram_selected_missing))
                val info = current.copy(pageIndex = media.pageIndex, pageCount = media.pageCount).videoInfo()
                val taskContext = currentCoroutineContext()
                /** 새 파일과 중복 재사용 파일 모두 실제 화질 및 게시물 페이지 수를 갱신합니다. */
                fun recordImages(paths: List<String>) {
                    if (!preferences.privateMode) paths.forEach { path ->
                        kotlinx.coroutines.runBlocking {
                            com.plugin.compatibility.PluginMediaMetadataStore.save(
                                path,
                                postPageIndex = info.postPageIndex,
                                postPageCount = info.postPageCount,
                            )
                            com.plugin.compatibility.PluginDatabaseUtil.insertInfoImmediately(DownloadedVideoInfo(0, current.title, current.author,
                                info.webpageUrl!!, current.thumbnail, path, info.extractorKey))
                        }
                    }
                }
                MediaLibrary.withDownloadLock(MediaRules.identity(info.webpageUrl!!)) {
                    val existing = MediaLibrary.findExisting(info, preferences)
                    if (existing.isNotEmpty() && current.image)
                        return@withDownloadLock if (preferences.privateMode) emptyList()
                        else existing.also { recordImages(it) }
                    if (!current.image)
                        return@withDownloadLock DownloadUtil.downloadVideo(
                                videoInfo = info,
                                taskId = taskId,
                                downloadPreferences =
                                    preferences.copyPreservingPluginOptions { copy(
                                        cookies = false,
                                        newTitle = info.title,
                                        downloadPlaylist = false,
                                        subdirectoryPlaylistTitle = false,
                                        subdirectoryExtractor = preferences.useWebsiteFolder(image = false),
                                    ) },
                                progressCallback = progress,
                            )
                            .getOrThrow()
                    val defaultRoot = if (preferences.sdcard) context.getSdcardTempDir(current.id)
                        else File(if (preferences.privateDirectory) App.privateDownloadDir else App.videoDownloadDir)
                    val root = com.plugin.compatibility.DownloadFolders.outputRoot(preferences, defaultRoot)
                    val platformName = if (current.platform == "tiktok") "TikTok" else "Instagram"
                    // 원본 웹사이트 설정이 켜지고 이미지가 적용 대상일 때만 플랫폼 폴더를 만듭니다.
                    val base = if (preferences.useWebsiteFolder(image = true)) root.resolve(platformName) else root
                    val directory = if (preferences.selectedFolder != null || preferences.imageFolderName.isBlank()) base
                        else base.resolve(MediaRules.folderName(preferences.imageFolderName, "Images"))
                    check(directory.isDirectory || directory.mkdirs()) { com.junkfood.seal.App.context.getString(R.string.loc_image_folder_create_failed) }
                    val call =
                        clientFor(preferences)
                            .newCall(
                                Request.Builder()
                                    .url(current.url)
                                    .header(
                                        "User-Agent",
                                        preferences.userAgentString.ifBlank { USER_AGENT },
                                    )
                                    .build()
                            )
                    calls[taskId] = call
                    try {
                        call.execute().use { response ->
                            check(response.isSuccessful) { com.junkfood.seal.App.context.getString(R.string.loc_image_download_failed, response.code) }
                            val body = response.body ?: error(com.junkfood.seal.App.context.getString(R.string.loc_image_response_empty))
                            val extension =
                                when (body.contentType()?.subtype) {
                                    "jpeg" -> "jpg"
                                    "png" -> "png"
                                    "webp" -> "webp"
                                    "avif" -> "avif"
                                    else -> error(com.junkfood.seal.App.context.getString(R.string.loc_image_response_invalid))
                                }
                            val target =
                                directory.resolve("${current.author} [${current.id}].$extension")
                            check(!target.exists()) { com.junkfood.seal.App.context.getString(R.string.loc_image_already_exists) }
                            val temporary =
                                directory.resolve(".${current.id}-${taskId.hashCode()}.part")
                            try {
                                body.byteStream().use { input ->
                                    temporary.outputStream().use { output ->
                                        val buffer = ByteArray(8192)
                                        var copied = 0L
                                        while (true) {
                                            taskContext.ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            output.write(buffer, 0, count)
                                            copied += count
                                            progress?.invoke(
                                                if (body.contentLength() > 0)
                                                    (copied * 100f / body.contentLength())
                                                        .coerceAtMost(100f)
                                                else 0f,
                                                0,
                                                com.junkfood.seal.App.context.getString(R.string.loc_image_downloading),
                                            )
                                        }
                                    }
                                }
                                check(temporary.length() > 0) { com.junkfood.seal.App.context.getString(R.string.loc_image_file_empty) }
                                val bounds =
                                    android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                android.graphics.BitmapFactory.decodeFile(
                                    temporary.absolutePath,
                                    bounds,
                                )
                                check(bounds.outWidth > 0 && bounds.outHeight > 0) {
                                    com.junkfood.seal.App.context.getString(R.string.loc_image_file_invalid)
                                }
                                check(temporary.renameTo(target)) { com.junkfood.seal.App.context.getString(R.string.loc_image_save_failed) }
                            } finally {
                                if (temporary.exists()) temporary.delete()
                            }
                            val processed = MediaLibrary.finish(listOf(target.absolutePath), preferences.copyPluginOptions(transcodeAv1 = false),
                                taskId, mediaRoot = root)
                            val paths =
                                if (preferences.sdcard)
                                    com.plugin.compatibility.CompatibilityFiles.moveFilesToSdcard(
                                            tempPath = defaultRoot,
                                            sdcardUri = preferences.sdcardUri,
                                        )
                                        .getOrThrow()
                                else
                                    processed.also {
                                        MediaScannerConnection.scanFile(
                                            context,
                                            it.toTypedArray(),
                                            null,
                                            null,
                                        )
                                    }
                            recordImages(paths)
                            paths
                        }
                    } finally {
                        calls.remove(taskId, call)
                    }
                }
            }
        }
}
