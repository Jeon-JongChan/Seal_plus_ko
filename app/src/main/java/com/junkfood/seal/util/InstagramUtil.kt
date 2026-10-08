package com.junkfood.seal.util

import android.media.MediaScannerConnection
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.util.FileUtil.getCookiesFile
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** 단일 게시물 또는 계정 목록에서 선택 가능한 이미지·영상 정보입니다. */
@Serializable
data class InstagramMedia(
    val id: String,
    val postUrl: String,
    val url: String,
    val thumbnail: String,
    val image: Boolean,
    val title: String,
    val author: String,
    val duration: Double = 0.0,
    val carousel: Boolean = false,
) {
    /** 기존 대기열·이력에서 사용할 메타데이터로 변환합니다. */
    fun videoInfo(): VideoInfo =
        VideoInfo(
            id = id,
            title = "$author [$id]",
            uploader = author,
            duration = duration,
            originalUrl = url,
            webpageUrl = if (carousel) "$postUrl#seal-media=$id" else postUrl,
            thumbnail = thumbnail,
            extractor = "instagram",
            extractorKey = "Instagram",
            ext = if (image) "jpg" else "mp4",
        )
}

/** 로그인 쿠키 사용을 명시적으로 선택한 경우에만 인스타그램 API에 쿠키를 전달합니다. */
object InstagramUtil {
    private val client =
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    private val calls = ConcurrentHashMap<String, Call>()
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /** 직접 이미지·목록 요청에도 사용자가 지정한 프록시와 IPv4 설정을 적용합니다. */
    private fun clientFor(preferences: DownloadUtil.DownloadPreferences): OkHttpClient {
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
                        "인스타그램 요청은 인증 없는 HTTP·SOCKS 프록시를 지원합니다. 프록시 설정을 확인하세요."
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
                val parsed = url.toHttpUrl()
                parsed.host in setOf("instagram.com", "www.instagram.com") &&
                    parsed.pathSegments.firstOrNull().orEmpty().isNotEmpty()
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
    private fun cookies(preferences: DownloadUtil.DownloadPreferences): Map<String, String> {
        if (!preferences.cookies) return emptyMap()
        val file = context.getCookiesFile()
        if (!file.isFile) return emptyMap()
        val now = System.currentTimeMillis() / 1000
        return file
            .readLines()
            .map { it.removePrefix("#HttpOnly_").split('\t') }
            .filter { fields ->
                fields.size >= 7 &&
                    fields[0].removePrefix(".") in setOf("instagram.com", "www.instagram.com") &&
                    (fields[4].toLongOrNull()?.let { it == 0L || it > now } == true)
            }
            .associate { it[5] to it[6] }
    }

    /** 리다이렉트·권한·속도 제한 오류를 숨기지 않고 고정된 인스타그램 호스트만 조회합니다. */
    private suspend fun request(
        endpoint: String,
        parameters: Map<String, String>,
        preferences: DownloadUtil.DownloadPreferences,
        taskId: String,
        post: Boolean = false,
    ): JsonObject {
        currentCoroutineContext().ensureActive()
        val url =
            "https://www.instagram.com/api/v1/$endpoint"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    if (!post) parameters.forEach { (key, value) -> addQueryParameter(key, value) }
                }
                .build()
        val cookie = cookies(preferences)
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", preferences.userAgentString.ifBlank { USER_AGENT })
                .header("Referer", "https://www.instagram.com/")
                .header("X-IG-App-ID", "936619743392459")
                .header("X-Requested-With", "XMLHttpRequest")
                .apply {
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
                    cookie["csrftoken"]?.let { header("X-CSRFToken", it) }
                }
                .build()
        val call = clientFor(preferences).newCall(request)
        calls[taskId] = call
        try {
            return call.execute().use { response ->
                check(response.isSuccessful) {
                    when (response.code) {
                        429 -> "인스타그램 요청 제한에 도달했습니다. 잠시 후 다시 시도하세요."
                        401,
                        403,
                        302 -> "접근 권한을 확인할 수 없습니다. 필요한 경우 쿠키 적용을 선택하고 다시 시도하세요."
                        else -> "인스타그램 조회 실패 (${response.code})"
                    }
                }
                val result = Json.parseToJsonElement(response.body!!.string()).jsonObject
                check(result.text("status") != "fail") {
                    "인스타그램에서 요청을 거부했습니다. 로그인 상태·접근 권한을 확인하세요."
                }
                currentCoroutineContext().ensureActive()
                result
            }
        } finally {
            calls.remove(taskId, call)
        }
    }

    /** 단일 게시물 또는 접근 가능한 계정 게시물 전체를 페이지 순서로 조회합니다. */
    suspend fun fetch(
        url: String,
        preferences: DownloadUtil.DownloadPreferences,
        taskId: String,
    ): List<InstagramMedia> =
        withContext(Dispatchers.IO) {
            require(accepts(url)) { "올바른 인스타그램 주소를 입력하세요." }
            val segments = url.toHttpUrl().pathSegments.filter { it.isNotEmpty() }
            if (segments.first() in setOf("p", "reel", "reels", "tv")) {
                    val code = segments.getOrNull(1) ?: error("게시물 주소가 올바르지 않습니다.")
                    val response =
                        request(
                            "media/${MediaRules.instagramId(code)}/info/",
                            emptyMap(),
                            preferences,
                            taskId,
                        )
                    parsePosts(
                        response.array("items"),
                        preferences.imageResolution,
                        MediaRules.videoHeight(preferences.videoResolution),
                    )
                } else {
                    val username = segments.first().removePrefix("@")
                    require(Regex("[A-Za-z0-9._]{1,30}").matches(username)) { "계정 이름을 확인하세요." }
                    val profile =
                        request(
                                "users/web_profile_info/",
                                mapOf("username" to username),
                                preferences,
                                taskId,
                            )
                            .obj("data")
                            .obj("user")
                    val userId = profile.text("id").ifEmpty { profile.text("pk") }
                    require(userId.matches(Regex("[0-9]+"))) { "계정을 조회하지 못했습니다." }
                    val result = mutableListOf<InstagramMedia>()
                    val cursors = mutableSetOf<String>()
                    var cursor = ""
                    do {
                        delay(6000)
                        val parameters = mutableMapOf("count" to "30")
                        if (cursor.isNotEmpty()) parameters["max_id"] = cursor
                        val page = request("feed/user/$userId/", parameters, preferences, taskId)
                        result +=
                            parsePosts(
                                page.array("items"),
                                preferences.imageResolution,
                                MediaRules.videoHeight(preferences.videoResolution),
                            )
                        val more = (page["more_available"] as? JsonPrimitive)?.booleanOrNull == true
                        cursor = if (more) page.text("next_max_id") else ""
                        check(!more || cursor.isNotEmpty() && cursors.add(cursor)) {
                            "인스타그램 목록 페이지가 반복되거나 누락됐습니다. 다시 시도하세요."
                        }
                    } while (cursor.isNotEmpty())
                    // 프로필 격자에 표시되지 않는 릴스도 별도 목록에서 읽습니다.
                    cursors.clear()
                    do {
                        delay(6000)
                        val parameters =
                            mutableMapOf(
                                "target_user_id" to userId,
                                "page_size" to "50",
                                "include_feed_video" to "true",
                            )
                        if (cursor.isNotEmpty()) parameters["max_id"] = cursor
                        val page =
                            request("clips/user/", parameters, preferences, taskId, post = true)
                        val posts =
                            JsonArray(
                                page.array("items").mapNotNull { (it as? JsonObject)?.get("media") }
                            )
                        result +=
                            parsePosts(
                                posts,
                                preferences.imageResolution,
                                MediaRules.videoHeight(preferences.videoResolution),
                            )
                        val paging = page.obj("paging_info")
                        val more =
                            (paging["more_available"] as? JsonPrimitive)?.booleanOrNull == true
                        cursor = if (more) paging.text("max_id") else ""
                        check(!more || cursor.isNotEmpty() && cursors.add(cursor)) {
                            "릴스 목록 페이지가 반복되거나 누락됐습니다."
                        }
                    } while (cursor.isNotEmpty())
                    result.distinctBy { it.id }
                }
                .also { check(it.isNotEmpty()) { "다운로드 가능한 영상·이미지를 찾지 못했습니다." } }
        }

    /** REST 게시물 응답의 캐러셀을 이미지·영상 파일 단위로 펼칩니다. */
    internal fun parsePosts(
        posts: JsonArray,
        imageResolution: Int,
        videoResolution: Int,
    ): List<InstagramMedia> =
        posts.flatMap { element ->
            val post = element as? JsonObject ?: return@flatMap emptyList()
            val code = post.text("code")
            if (code.isEmpty()) return@flatMap emptyList()
            val author =
                post.obj("user").text("username").takeIf {
                    it.matches(Regex("[A-Za-z0-9._]{1,30}")) && it !in setOf(".", "..")
                } ?: "Instagram"
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
                    "https://www.instagram.com/p/$code/",
                    mediaUrl,
                    images.firstOrNull()?.text("url").orEmpty(),
                    image,
                    post.obj("caption").text("text").take(160).ifEmpty { "$author · $code" },
                    author,
                    item.text("video_duration").toDoubleOrNull() ?: 0.0,
                    carousel = children.size > 1,
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
                        parsed.host.endsWith(".fbcdn.net")) &&
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
                val current =
                    fetch(media.postUrl, preferences, taskId).firstOrNull { it.id == media.id }
                        ?: error("게시물에서 선택한 파일을 찾지 못했습니다.")
                val info = current.videoInfo()
                val taskContext = currentCoroutineContext()
                MediaLibrary.withDownloadLock(MediaRules.identity(info.webpageUrl!!)) {
                    val existing = MediaLibrary.findExisting(info, preferences)
                    if (existing.isNotEmpty() && current.image)
                        return@withDownloadLock if (preferences.privateMode) emptyList()
                        else existing
                    if (!current.image)
                        return@withDownloadLock DownloadUtil.downloadVideo(
                                videoInfo = info,
                                taskId = taskId,
                                downloadPreferences =
                                    preferences.copy(
                                        cookies = false,
                                        newTitle = info.title,
                                        downloadPlaylist = false,
                                        subdirectoryPlaylistTitle = false,
                                    ),
                                progressCallback = progress,
                            )
                            .getOrThrow()
                    val directory =
                        if (preferences.sdcard) context.getSdcardTempDir(current.id)
                        else
                            File(
                                    if (preferences.privateDirectory) App.privateDownloadDir
                                    else App.videoDownloadDir
                                )
                                .resolve("Instagram/이미지")
                    check(directory.isDirectory || directory.mkdirs()) { "이미지 저장 폴더를 만들지 못했습니다." }
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
                            check(response.isSuccessful) { "이미지 다운로드 실패 (${response.code})" }
                            val body = response.body ?: error("이미지 응답이 비어 있습니다.")
                            val extension =
                                when (body.contentType()?.subtype) {
                                    "jpeg" -> "jpg"
                                    "png" -> "png"
                                    "webp" -> "webp"
                                    "avif" -> "avif"
                                    else -> error("이미지가 아닌 응답을 받았습니다.")
                                }
                            val target =
                                directory.resolve("${current.author} [${current.id}].$extension")
                            check(!target.exists()) { "동일 이미지 파일이 이미 있습니다." }
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
                                                "이미지 다운로드 중",
                                            )
                                        }
                                    }
                                }
                                check(temporary.length() > 0) { "이미지 파일이 비어 있습니다." }
                                val bounds =
                                    android.graphics.BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                    }
                                android.graphics.BitmapFactory.decodeFile(
                                    temporary.absolutePath,
                                    bounds,
                                )
                                check(bounds.outWidth > 0 && bounds.outHeight > 0) {
                                    "정상적인 이미지 파일이 아닙니다."
                                }
                                check(temporary.renameTo(target)) { "이미지 저장에 실패했습니다." }
                            } finally {
                                if (temporary.exists()) temporary.delete()
                            }
                            val paths =
                                if (preferences.sdcard)
                                    FileUtil.moveFilesToSdcard(
                                            tempPath = directory,
                                            sdcardUri = preferences.sdcardUri,
                                        )
                                        .getOrThrow()
                                else
                                    listOf(target.absolutePath).also {
                                        MediaScannerConnection.scanFile(
                                            context,
                                            it.toTypedArray(),
                                            null,
                                            null,
                                        )
                                    }
                            if (!preferences.privateMode)
                                paths.forEach { path ->
                                    kotlinx.coroutines.runBlocking {
                                        DatabaseUtil.insertInfoImmediately(
                                            DownloadedVideoInfo(
                                                0,
                                                current.title,
                                                current.author,
                                                info.webpageUrl!!,
                                                current.thumbnail,
                                                path,
                                                "Instagram",
                                            )
                                        )
                                    }
                                }
                            paths
                        }
                    } finally {
                        calls.remove(taskId, call)
                    }
                }
            }
        }
}
