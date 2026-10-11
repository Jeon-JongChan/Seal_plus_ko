package com.plugin.tiktok

import com.plugin.compatibility.InstagramMedia
import com.plugin.instagram.InstagramUtil
import com.junkfood.seal.util.*
import com.junkfood.seal.App
import com.junkfood.seal.R
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

/** 틱톡 공개 게시물의 이미지 목록만 보완하고 영상은 기존 엔진으로 처리합니다. */
object TikTokUtil {
    /** 공식 게시물·공유 링크만 자동 분석 대상으로 허용합니다. */
    fun accepts(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.port in setOf(-1, 80, 443) &&
            when (uri.host?.lowercase()) {
                "vm.tiktok.com", "vt.tiktok.com" -> uri.path.orEmpty().trim('/').isNotEmpty()
                "www.tiktok.com", "tiktok.com" -> Regex("^/(?:@[^/]+|share)/(?:video|photo)/[0-9]+/?$").matches(uri.path.orEmpty()) || uri.path.orEmpty().startsWith("/t/")
                else -> false
            }
    }.getOrDefault(false)

    /** 이미지 저장에 필요한 틱톡 CDN만 허용하며 유사 도메인과 인증 정보는 거부합니다. */
    fun safeMediaUrl(url: String): Boolean = runCatching {
        val parsed = url.toHttpUrl()
        parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() &&
            listOf("tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com", "ibytedtos.com", "byteoversea.com", "tiktok.com", "tiktokv.com")
                .any { parsed.host == it || parsed.host.endsWith(".$it") }
    }.getOrDefault(false)

    /** 기존 공개 페이지 요청·프록시·쿠키 선택·취소를 재사용하고 영상 조회는 기존 함수를 사용합니다. */
    suspend fun fetch(url: String, preferences: DownloadUtil.DownloadPreferences, taskId: String): List<InstagramMedia> {
        require(accepts(url))
        val result = runCatching {
            var verificationCookies = emptyMap<String, String>()
            var anonymousVideoCookie = ""
            val client = InstagramUtil.clientFor(preferences).newBuilder().followRedirects(true).followSslRedirects(false)
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    // 공유 주소가 외부 사이트로 이동해도 쿠키나 요청을 전달하지 않습니다.
                    require(request.url.isHttps && request.url.host in setOf("www.tiktok.com", "tiktok.com", "vm.tiktok.com", "vt.tiktok.com"))
                    val cookies = InstagramUtil.cookies(preferences, request.url.host) + verificationCookies
                    chain.proceed(request.newBuilder().apply {
                        if (cookies.isNotEmpty()) header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    }.build()).also { response ->
                        // 로그인 쿠키 대신 공개 페이지가 발급한 영상 CDN용 익명 값 하나만 전달합니다.
                        okhttp3.Cookie.parseAll(response.request.url, response.headers)
                            .firstOrNull { it.name == "tt_chain_token" && it.domain == "tiktok.com" }
                            ?.let { anonymousVideoCookie = it.value }
                    }
                }.build()
            val pageUrl = url.replace("http://", "https://").replace("/photo/", "/video/")
            var (html, finalUrl) = InstagramUtil.publicPage(pageUrl, preferences, taskId, client)
            verificationCookies = solvePublicChallenge(html)
            if (verificationCookies.isNotEmpty()) {
                // 익명 브라우저 확인 값은 이번 조회에만 사용하고 사용자 쿠키 저장소에는 저장하지 않습니다.
                val verified = InstagramUtil.publicPage(finalUrl, preferences, taskId, client)
                html = verified.first
                finalUrl = verified.second
            }
            parsePublicPage(html, finalUrl).map {
                if (it.image) it else it.copy(anonymousVideoCookie = anonymousVideoCookie)
            }.also { check(it.isNotEmpty()) { App.context.getString(R.string.loc_media_fetch_failed) } }
        }
        return result.getOrElse { error ->
            if (error is CancellationException) throw error
            // 기존 엔진으로 가능한 영상은 공개 페이지 실패 때문에 다운로드를 막지 않습니다.
            val info = DownloadUtil.fetchVideoInfoFromUrl(url, taskKey = taskId, preferences = preferences).getOrThrow()
            listOf(InstagramMedia(info.id, info.webpageUrl ?: url, info.originalUrl ?: url,
                info.thumbnail.orEmpty(), false, info.title, info.uploader.orEmpty(), info.duration ?: 0.0,
                width = info.width?.toInt() ?: 0, height = info.height?.toInt() ?: 0, platform = "tiktok"))
        }
    }

    /** 공개 페이지의 SHA-256 브라우저 확인을 제한된 횟수로 처리하며 로그인 정보를 만들지 않습니다. */
    internal suspend fun solvePublicChallenge(html: String): Map<String, String> {
        /** 확인 응답의 고정 식별자에서 클래스 속성만 읽습니다. */
        fun value(id: String): String {
            val tag = Regex("""<[^>]+\bid=["']${Regex.escape(id)}["'][^>]*>""").find(html)?.value ?: return ""
            return Regex("""\bclass=["']([^"']*)["']""").find(tag)?.groupValues?.get(1).orEmpty()
        }
        val encoded = value("cs").takeIf { it.length in 1..8192 } ?: return emptyMap()
        val data = runCatching { Json.parseToJsonElement(encoded.decodeBase64()?.utf8().orEmpty()).jsonObject }.getOrNull() ?: return emptyMap()
        val base = data.obj("v").text("a").decodeBase64()?.toByteArray() ?: return emptyMap()
        val expected = data.obj("v").text("c").decodeBase64()?.toByteArray() ?: return emptyMap()
        if (base.size > 256 || expected.size != 32) return emptyMap()
        val name = value("wci")
        if (!Regex("[A-Za-z0-9_]{1,64}").matches(name)) return emptyMap()
        val digest = MessageDigest.getInstance("SHA-256")
        // ponytail: 기존 추출기와 같은 100만 회 상한입니다. 더 어려운 확인은 기존 영상 엔진의 오류로 안내합니다.
        for (number in 0..1_000_000) {
            if (number % 1024 == 0) currentCoroutineContext().ensureActive()
            val nonce = number.toString().toByteArray(Charsets.UTF_8)
            digest.update(base)
            if (digest.digest(nonce).contentEquals(expected)) {
                val solved = JsonObject(data + ("d" to JsonPrimitive(nonce.toByteString().base64())))
                val result = mutableMapOf(name to solved.toString().toByteArray(Charsets.UTF_8).toByteString().base64())
                val requestName = value("rci")
                val requestValue = value("rs")
                if (Regex("[A-Za-z0-9_]{1,64}").matches(requestName) && requestValue.isNotEmpty() &&
                    requestValue.none { it <= ' ' || it == ';' }) result[requestName] = requestValue
                return result
            }
        }
        return emptyMap()
    }

    /** 공개 JSON의 실제 사진 목록을 읽고 배경음악·영상 커버는 별도 페이지로 세지 않습니다. */
    internal fun parsePublicPage(html: String, url: String): List<InstagramMedia> {
        if (html.length > 16_000_000) return emptyList()
        val script = Regex("""<script\b[^>]*\bid=["']__UNIVERSAL_DATA_FOR_REHYDRATION__["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(script).jsonObject }.getOrNull() ?: return emptyList()
        val item = root.obj("__DEFAULT_SCOPE__").obj("webapp.video-detail").obj("itemInfo").obj("itemStruct")
        val id = item.text("id").takeIf { it.matches(Regex("[0-9]+")) } ?: return emptyList()
        val requestedId = Regex("/(?:video|photo)/([0-9]+)").find(url)?.groupValues?.get(1)
        if (requestedId != null && requestedId != id) return emptyList()
        val author = item.obj("author").text("uniqueId").takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) && it !in setOf(".", "..") } ?: "TikTok"
        val postUrl = if (author == "TikTok") url.substringBefore('?').substringBefore('#').replace("/photo/", "/video/")
            else "https://www.tiktok.com/@$author/video/$id"
        val title = item.text("desc").take(160).ifBlank { "$author · $id" }
        val images = item.obj("imagePost")["images"] as? JsonArray ?: JsonArray(emptyList())
        if (images.isNotEmpty()) return images.mapIndexedNotNull { index, element ->
            val image = element as? JsonObject ?: return@mapIndexedNotNull null
            val source = (image.obj("imageURL")["urlList"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.firstOrNull { safeMediaUrl(it) }
                ?: return@mapIndexedNotNull null
            InstagramMedia("$id${index.toString().padStart(3, '0')}", postUrl, source, source, true, title, author,
                carousel = images.size > 1, width = image.text("imageWidth").toIntOrNull() ?: 0,
                height = image.text("imageHeight").toIntOrNull() ?: 0, platform = "tiktok")
        }
        val video = item.obj("video")
        val source = video.text("playAddr").ifBlank { video.text("downloadAddr") }
        if (!safeMediaUrl(source)) return emptyList()
        return listOf(InstagramMedia(id, postUrl, source, video.text("cover"), false, title, author,
            video.text("duration").toDoubleOrNull() ?: 0.0, width = video.text("width").toIntOrNull() ?: 0,
            height = video.text("height").toIntOrNull() ?: 0, platform = "tiktok"))
    }

    /** 누락된 JSON 객체를 빈 객체로 읽습니다. */
    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

    /** 문자열·숫자 메타데이터를 읽습니다. */
    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
