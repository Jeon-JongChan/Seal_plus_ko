package com.plugin.runtime

import com.plugin.compatibility.PluginPreferences.QUICK_COOKIE_PROFILE
import com.plugin.compatibility.PluginPreferences.DOWNLOAD_ALL_MEDIA
import com.plugin.compatibility.PluginPreferences.IMAGE_FOLDER_NAME
import com.plugin.compatibility.PluginPreferences.VIDEO_FOLDER_NAME
import com.plugin.compatibility.PluginPreferences.IMAGE_TIERS
import com.plugin.compatibility.PluginPreferences.VIDEO_TIERS
import com.plugin.compatibility.PluginPreferences.IMAGE_RESOLUTION
import com.plugin.compatibility.PluginPreferences.CLASSIFY_MEDIA
import com.plugin.compatibility.PluginPreferences.QUALITY_THRESHOLD
import com.plugin.compatibility.PluginPreferences.TRANSCODE_AV1
import com.plugin.compatibility.PluginPreferences.KEEP_AV1_ORIGINAL
import com.plugin.compatibility.PluginPreferences.VIDEO_CONTAINER
import com.plugin.compatibility.PluginPreferences.AV1_CRF
import com.plugin.compatibility.PluginPreferences.AV1_PRESET
import com.plugin.compatibility.PluginPreferences.CLASSIFICATION_RULES

import com.junkfood.seal.util.*
import com.junkfood.seal.util.DownloadUtil.DownloadPreferences

import com.plugin.tiktok.TikTokUtil
import com.plugin.instagram.InstagramUtil
import com.plugin.compatibility.MediaLibrary
import com.plugin.compatibility.MediaRules
import com.plugin.compatibility.*
import com.plugin.compatibility.copyPluginOptions
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.OPEN_READONLY
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.CheckResult
import com.junkfood.seal.App
import com.junkfood.seal.App.Companion.audioDownloadDir
import com.junkfood.seal.App.Companion.context
import com.junkfood.seal.App.Companion.videoDownloadDir
import com.junkfood.seal.Downloader
import com.junkfood.seal.Downloader.onProcessEnded
import com.junkfood.seal.Downloader.onProcessStarted
import com.junkfood.seal.Downloader.onTaskEnded
import com.junkfood.seal.Downloader.onTaskError
import com.junkfood.seal.Downloader.onTaskStarted
import com.junkfood.seal.Downloader.toNotificationId
import com.junkfood.seal.R
import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.database.objects.DownloadedVideoInfo
import com.junkfood.seal.database.objects.VideoQuality
import com.junkfood.seal.ui.page.settings.network.Cookie
import com.junkfood.seal.util.FileUtil.getArchiveFile
import com.junkfood.seal.util.FileUtil.getConfigFile
import com.junkfood.seal.util.FileUtil.getExternalTempDir
import com.junkfood.seal.util.FileUtil.getFileName
import com.junkfood.seal.util.FileUtil.getSdcardTempDir
import com.plugin.compatibility.CompatibilityFiles.moveFilesToSdcard
import com.junkfood.seal.util.PreferenceUtil.COOKIE_HEADER
import com.junkfood.seal.util.PreferenceUtil.getBoolean
import com.junkfood.seal.util.PreferenceUtil.getInt
import com.junkfood.seal.util.PreferenceUtil.getString
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 확장 다운로드 옵션과 소셜·미디어 후처리를 원본 엔진과 별도로 제공합니다. */
object PluginDownloadUtil {


    /** 원본 설정을 만든 뒤 확장 설정을 읽고 활성 스위치에 맞춰 사본을 구성합니다. */
    fun configure(base: DownloadPreferences): DownloadPreferences {
        val classification = MediaRules.parseClassificationJson(CLASSIFICATION_RULES.getString())
        val explicitCookie = PluginPreferences.EXPLICIT_COOKIE_PROFILE.getInt(Int.MIN_VALUE)
        return com.plugin.PluginState.preferences(base.copyPluginOptions(
                    videoContainer = MediaRules.videoContainer(VIDEO_CONTAINER.getString()),
                    // 이전 이미지·영상 필터를 새 다운로드에 적용하지 않고 분석 결과로 자동 분기합니다.
                    mediaSelection = 0,
                    downloadAllMedia = DOWNLOAD_ALL_MEDIA.getBoolean(),
                    imageFolderName = MediaRules.optionalFolderName(IMAGE_FOLDER_NAME.getString("Images"), "Images"),
                    videoFolderName = MediaRules.optionalFolderName(VIDEO_FOLDER_NAME.getString("Videos"), "Videos"),
                    platformFolders = false,
                    imageTiers = MediaRules.withDisabledTiers(classification?.first
                        ?: IMAGE_TIERS.getString(MediaRules.DEFAULT_IMAGE_TIERS), MediaRules.DISABLED_IMAGE_TIERS.getString()),
                    videoTiers = MediaRules.withDisabledTiers(classification?.second
                        ?.let(MediaRules::currentVideoTiers)
                        ?: MediaRules.currentVideoTiers(VIDEO_TIERS.getString(MediaRules.DEFAULT_VIDEO_TIERS)), MediaRules.DISABLED_VIDEO_TIERS.getString()),
                    codecRatios = MediaRules.DEFAULT_CODEC_RATIOS,
                    imageResolution = IMAGE_RESOLUTION.getInt(),
                    classifyMedia = CLASSIFY_MEDIA.getBoolean(),
                    classificationUpperFirst = com.plugin.compatibility.PluginPreferences.CLASSIFICATION_UPPER_FIRST.getBoolean(),
                    classificationTarget = com.plugin.compatibility.PluginPreferences.CLASSIFICATION_TARGET.getInt().coerceIn(0, 2),
                    websiteFolderTarget = com.plugin.compatibility.PluginPreferences.WEBSITE_FOLDER_TARGET.getInt().coerceIn(0, 2),
                    qualityThresholdMbps = QUALITY_THRESHOLD.getInt(4).coerceIn(1, 100),
                    transcodeAv1 = TRANSCODE_AV1.getBoolean(),
                    keepAv1Original = KEEP_AV1_ORIGINAL.getBoolean(),
                    av1Crf = AV1_CRF.getInt(32).coerceIn(20, 50),
                    av1Preset = MediaRules.av1Preset(AV1_PRESET.getInt(8)),
                    cookieProfileId = if (explicitCookie != Int.MIN_VALUE) explicitCookie else QUICK_COOKIE_PROFILE.getInt(-1),
                    cookieProfileExplicit = explicitCookie != Int.MIN_VALUE,
    ))
    }

    /** 쿠키 프로필·미디어 설정은 등록한 작업의 사본을 기준으로 적용합니다. */
    fun handles(preferences: DownloadPreferences): Boolean =
        preferences.cookies || preferences.selectedFolder != null || preferences.socialProcessing || preferences.mediaProcessing || preferences.reuseExisting || preferences.cookieProfileId >= 0

    /** CookieScheme의 독립 플러그인 처리를 제공합니다. */
    object CookieScheme {
        const val NAME = "name"
        const val VALUE = "value"
        const val SECURE = "is_secure"
        const val EXPIRY = "expires_utc"
        const val HOST = "host_key"
        const val PATH = "path"
    }

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    private const val TAG = "DownloadUtil"

    const val BASENAME = "%(title).200B"

    const val EXTENSION = ".%(ext)s"

    private const val ID = "[%(id)s]"

    private const val CLIP_TIMESTAMP = "%(section_start)d-%(section_end)d"

    const val OUTPUT_TEMPLATE_DEFAULT = BASENAME + EXTENSION

    const val OUTPUT_TEMPLATE_ID = "$BASENAME $ID$EXTENSION"

    private const val OUTPUT_TEMPLATE_CLIPS = "$BASENAME [$CLIP_TIMESTAMP]$EXTENSION"

    private const val OUTPUT_TEMPLATE_CHAPTERS =
        "chapter:$BASENAME/%(section_number)d - %(section_title).200B$EXTENSION"

    private const val OUTPUT_TEMPLATE_SPLIT = "$BASENAME/$OUTPUT_TEMPLATE_DEFAULT"

    private const val PLAYLIST_TITLE_SUBDIRECTORY_PREFIX = "%(playlist)s/"

    private const val CROP_ARTWORK_COMMAND =
        """--ppa "ffmpeg: -c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"""""

    @CheckResult
    /** getPlaylistOrVideoInfo의 분리된 다운로드 처리를 수행합니다. */
    fun getPlaylistOrVideoInfo(
        playlistURL: String,
        downloadPreferences: DownloadPreferences = com.plugin.compatibility.createPluginPreferences(),
    ): Result<YoutubeDLInfo> =
        YoutubeDL.runCatching {
            withCookieSnapshot(downloadPreferences) { cookieFile ->
            ToastUtil.makeToastSuspend(context.getString(R.string.fetching_playlist_info))
            val request = YoutubeDLRequest(playlistURL)
            with(request) {
                //            addOption("--compat-options", "no-youtube-unavailable-videos")
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("-o", BASENAME)
                addOption("-R", "1")
                addOption("--socket-timeout", "5")
                downloadPreferences.run {
                    if (extractAudio) {
                        addOption("-x")
                    }
                    applyFormatSorter(this, toFormatSorter())
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    if (cookies) {
                        enableCookies(userAgentString, checkNotNull(cookieFile))
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                }
            }
            execute(request, playlistURL).out.run {
                val playlistInfo = jsonFormat.decodeFromString<PlaylistResult>(this)
                if (playlistInfo.type != "playlist") {
                    jsonFormat.decodeFromString<VideoInfo>(this)
                } else playlistInfo
            }
            }
        }

    @CheckResult
    /** getVideoInfo의 분리된 다운로드 처리를 수행합니다. */
    private fun getVideoInfo(
        request: YoutubeDLRequest,
        taskKey: String? = null,
    ): Result<VideoInfo> =
        request.runCatching {
            val response: YoutubeDLResponse =
                YoutubeDL.getInstance().execute(request, taskKey, null)
            jsonFormat.decodeFromString(response.out)
        }

    @CheckResult
    /** fetchVideoInfoFromUrl의 분리된 다운로드 처리를 수행합니다. */
    fun fetchVideoInfoFromUrl(
        url: String,
        playlistIndex: Int? = null,
        taskKey: String? = null,
        preferences: DownloadPreferences = com.plugin.compatibility.createPluginPreferences(),
    ): Result<VideoInfo> {
        return withCookieSnapshotResult<VideoInfo>(preferences) { cookieFile ->
        with(preferences) {
            val request =
                YoutubeDLRequest(url).apply {
                    addOption("-o", BASENAME)
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    if (extractAudio) {
                        addOption("-x")
                    }
                    applyFormatSorter(this@with, toFormatSorter())
                    if (cookies) {
                        enableCookies(userAgentString, checkNotNull(cookieFile))
                    }
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    /*            if (debug) {
                        addOption("-v")
                    }*/
                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    if (playlistIndex != null) {
                        addOption("--playlist-items", playlistIndex)
                        addOption("--dump-json")
                    } else {
                        addOption("--dump-single-json")
                    }
                    addOption("-R", "1")
                    addOption("--no-playlist")
                    addOption("--socket-timeout", "5")
                }
            return getVideoInfo(request, taskKey)
        }
        }
    }

    /** 전체 쿠키는 매번 WebView에서, 명시적 프로필은 매번 해당 DB 레코드에서 최신 값을 읽습니다. */
    fun cookieContent(profileId: Int = -1, fresh: Boolean = false): String {
        // 재시도·게시물 다중 페이지는 짧은 시간에 같은 쿠키를 반복 요청하므로 5초 캐시로 WebView flush·DB 스캔을 줄입니다.
        val now = android.os.SystemClock.elapsedRealtime()
        if (!fresh) cookieCache[profileId]?.let { (at, cached) -> if (now - at < COOKIE_CACHE_MS) return cached }
        return readCookieContent(profileId).also { cookieCache[profileId] = now to it }
    }

    private val cookieCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, String>>()
    private const val COOKIE_CACHE_MS = 5_000L

    /** 캐시 없이 쿠키를 WebView·DB에서 직접 읽어 정규화합니다. */
    private fun readCookieContent(profileId: Int): String {
        val content = if (profileId < 0) getCookiesContentFromDatabase().getOrThrow() else {
            val profile = kotlinx.coroutines.runBlocking { DatabaseUtil.getCookieById(profileId) }
                ?: error(context.getString(R.string.loc_cookie_login_hint))
            val host = java.net.URI(profile.url).host ?: error(context.getString(R.string.loc_cookie_login_hint))
            profile.content.ifBlank {
                getCookieListFromDatabase().getOrThrow().filter {
                    val domain = it.domain.removePrefix(".")
                    host.equals(domain, ignoreCase = true) || host.lowercase().endsWith(".${domain.lowercase()}")
                }.toCookiesFileContent()
            }
        }
        return CookieRules.normalized(content)
    }

    /** UI 준비 파일도 검증 후 원자적으로 교체하고 실패하면 이전 파일을 보존합니다. */
    @Synchronized
    fun prepareCookies(profileId: Int = -1): java.io.File {
        val content = cookieContent(profileId, fresh = true)
        val file = CompatibilityFiles.cookiesFile(context, profileId)
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(content.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        return file
    }

    /** 병렬 요청의 쿠키 파일을 분리하고 완료·실패·조기 반환에서도 이번 요청 사본만 정리합니다. */
    private inline fun <T> withCookieSnapshot(preferences: DownloadPreferences, block: (File?) -> T): T {
        val file = if (preferences.cookies) File.createTempFile("request-cookies-", ".txt", context.cacheDir) else null
        try { file?.writeText(cookieContent(preferences.cookieProfileId)); return block(file) }
        finally { file?.delete() }
    }

    /** 준비 실패도 다운로드 상태의 정상적인 오류 결과로 전달합니다. */
    private inline fun <T> withCookieSnapshotResult(preferences: DownloadPreferences, block: (File?) -> Result<T>): Result<T> =
        runCatching { withCookieSnapshot(preferences) { block(it).getOrThrow() } }

    /** yt-dlp가 갱신하는 쿠키도 다른 요청이나 사용자의 원본 프로필 파일에 쓰지 않습니다. */
    private fun YoutubeDLRequest.enableCookies(userAgentString: String, file: File): YoutubeDLRequest =
        this.addOption("--cookies", file.absolutePath).apply {
            if (userAgentString.isNotEmpty()) addOption("--add-header", "User-Agent:$userAgentString")
        }

    /** YoutubeDLRequest.enableProxy의 독립 플러그인 처리를 제공합니다. */
    private fun YoutubeDLRequest.enableProxy(proxyUrl: String): YoutubeDLRequest =
        this.addOption("--proxy", proxyUrl)

    /** YoutubeDLRequest.useDownloadArchive의 분리된 다운로드 처리를 수행합니다. */
    private fun YoutubeDLRequest.useDownloadArchive(): YoutubeDLRequest =
        this.addOption("--download-archive", context.getArchiveFile().absolutePath)

    @CheckResult
    /** getCookieListFromDatabase의 분리된 다운로드 처리를 수행합니다. */
    fun getCookieListFromDatabase(): Result<List<Cookie>> = runCatching {
        CookieManager.getInstance().run {
            if (!hasCookies()) throw Exception("There is no cookies in the database!")
            flush()
        }
        SQLiteDatabase.openDatabase(
                context.dataDir.resolve("app_webview/Default/Cookies").absolutePath,
                null,
                OPEN_READONLY,
            )
            .run {
                val projection =
                    arrayOf(
                        CookieScheme.HOST,
                        CookieScheme.EXPIRY,
                        CookieScheme.PATH,
                        CookieScheme.NAME,
                        CookieScheme.VALUE,
                        CookieScheme.SECURE,
                    )
                val cookieList = mutableListOf<Cookie>()
                // 암호화된 값은 호스트·경로별로 한 번만 WebView에서 조회합니다.
                val headerCache = HashMap<String, List<String>>()
                query("cookies", projection, null, null, null, null, null).run {
                    while (moveToNext()) {
                        val expiry = getLong(getColumnIndexOrThrow(CookieScheme.EXPIRY))
                        val name = getString(getColumnIndexOrThrow(CookieScheme.NAME))
                        val storedValue = getString(getColumnIndexOrThrow(CookieScheme.VALUE))
                        val path = getString(getColumnIndexOrThrow(CookieScheme.PATH))
                        val secure = getLong(getColumnIndexOrThrow(CookieScheme.SECURE)) == 1L
                        val hostKey = getString(getColumnIndexOrThrow(CookieScheme.HOST))

                        if (hostKey.isBlank()) continue
                        val host = hostKey.removePrefix(".")
                        val value = storedValue.ifEmpty {
                            headerCache.getOrPut("$host$path") {
                                CookieManager.getInstance().getCookie("https://$host$path").orEmpty().split(';').map(String::trim)
                            }.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "").orEmpty()
                        }
                        cookieList.add(
                            Cookie(
                                domain = host,
                                name = name,
                                value = value,
                                path = path,
                                secure = secure,
                                expiry = CookieRules.webViewExpiry(expiry),
                                includeSubdomains = hostKey.startsWith("."),
                            )
                        )
                    }
                    close()
                }
                close()
                cookieList
            }
    }

    /** List의 분리된 다운로드 처리를 수행합니다. */
    fun List<Cookie>.toCookiesFileContent(): String =
        this.fold(StringBuilder(COOKIE_HEADER)) { acc, cookie ->
                acc.append(cookie.toNetscapeCookieString()).append("\n")
            }
            .toString()

    /** getCookiesContentFromDatabase의 독립 플러그인 처리를 제공합니다. */
    fun getCookiesContentFromDatabase(): Result<String> =
        getCookieListFromDatabase().mapCatching { it.toCookiesFileContent() }

    /** YoutubeDLRequest.enableAria2c의 분리된 다운로드 처리를 수행합니다. */
    private fun YoutubeDLRequest.enableAria2c(): YoutubeDLRequest =
        this.addOption("--downloader", "libaria2c.so")

    /** YoutubeDLRequest.addOptionsForVideoDownloads의 독립 플러그인 처리를 제공합니다. */
    private fun YoutubeDLRequest.addOptionsForVideoDownloads(
        downloadPreferences: DownloadPreferences
    ): YoutubeDLRequest =
        this.apply {
            downloadPreferences.run {
                addOption("--add-metadata")
                addOption("--no-embed-info-json")
                if (formatIdString.isNotEmpty()) {
                    addOption("-f", formatIdString)
                    if (mergeAudioStream) {
                        addOption("--audio-multistreams")
                    }
                } else {
                    applyFormatSorter(this, toFormatSorter())
                }
                if (downloadSubtitle) {
                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    subtitleLanguage
                        .takeIf { it.isNotEmpty() }
                        ?.let { addOption("--sub-langs", it) }
                    if (embedSubtitle) {
                        addOption("--embed-subs")
                        if (keepSubtitle) {
                            addOption("--write-subs")
                        }
                    } else {
                        addOption("--write-subs")
                    }
                    when (convertSubtitle) {
                        CONVERT_ASS -> addOption("--convert-subs", "ass")
                        CONVERT_SRT -> addOption("--convert-subs", "srt")
                        CONVERT_VTT -> addOption("--convert-subs", "vtt")
                        CONVERT_LRC -> addOption("--convert-subs", "lrc")
                        else -> {}
                    }
                }
                // 확장자만 바꾸지 않고 실제 스트림 컨테이너를 변환합니다.
                val container = if (mergeToMkv) "mkv" else MediaRules.videoContainer(videoContainer)
                addOption("--remux-video", container)
                addOption("--merge-output-format", container)
                if (embedThumbnail) {
                    addOption("--embed-thumbnail")
                }
                if (videoClips.isEmpty()) addOption("--embed-chapters")
            }
        }

    @CheckResult
    /** DownloadPreferences.toAudioFormatSorter의 분리된 다운로드 처리를 수행합니다. */
    private fun DownloadPreferences.toAudioFormatSorter(): String =
        this.run {
            if (!useCustomAudioPreset) return@run ""
            val format =
                when (audioFormat) {
                    M4A -> "acodec:aac"
                    OPUS -> "acodec:opus"
                    else -> ""
                }
            val quality =
                when (audioQuality) {
                    HIGH -> "abr~192"
                    MEDIUM -> "abr~128"
                    LOW -> "abr~64"
                    else -> ""
                }
            return@run connectWithDelimiter(format, quality, delimiter = ",")
        }

    @CheckResult
    /** DownloadPreferences.toVideoFormatSorter의 분리된 다운로드 처리를 수행합니다. */
    private fun DownloadPreferences.toVideoFormatSorter(): String =
        this.run {
            val format =
                when (videoFormat) {
                    FORMAT_COMPATIBILITY -> "proto,vcodec:h264,ext"
                    FORMAT_QUALITY ->
                        if (supportAv1HardwareDecoding) {
                            "vcodec:av01"
                        } else {
                            "vcodec:vp9.2"
                        }

                    else -> ""
                }
            val res =
                when (videoResolution) {
                    1 -> "res:2160"
                    2 -> "res:1440"
                    3 -> "res:1080"
                    4 -> "res:720"
                    5 -> "res:480"
                    6 -> "res:360"
                    7 -> "+res"
                    else -> ""
                }
            val sorter =
                if (videoFormat == FORMAT_COMPATIBILITY) {
                    connectWithDelimiter(format, res, delimiter = ",")
                } else {
                    connectWithDelimiter(res, format, delimiter = ",")
                }
            return@run sorter
        }

    /** YoutubeDLRequest.applyFormatSorter의 분리된 다운로드 처리를 수행합니다. */
    private fun YoutubeDLRequest.applyFormatSorter(
        preferences: DownloadPreferences,
        sorter: String,
    ) =
        preferences.run {
            if (formatSorting && sortingFields.isNotEmpty()) addOption("-S", sortingFields)
            else if (sorter.isNotEmpty()) addOption("-S", sorter) else {}
        }

    @CheckResult
    /** DownloadPreferences.toFormatSorter의 분리된 다운로드 처리를 수행합니다. */
    fun DownloadPreferences.toFormatSorter(): String =
        connectWithDelimiter(
            this.toVideoFormatSorter(),
            this.toAudioFormatSorter(),
            delimiter = ",",
        )

    /** YoutubeDLRequest.addOptionsForAudioDownloads의 독립 플러그인 처리를 제공합니다. */
    private fun YoutubeDLRequest.addOptionsForAudioDownloads(
        id: String,
        preferences: DownloadPreferences,
        playlistUrl: String,
    ): YoutubeDLRequest =
        this.apply {
            with(preferences) {
                addOption("-x")
                if (downloadSubtitle) {
                    addOption("--write-subs")

                    if (autoSubtitle) {
                        addOption("--write-auto-subs")
                        if (!autoTranslatedSubtitles) {
                            addOption("--extractor-args", "youtube:skip=translated_subs")
                        }
                    }
                    subtitleLanguage
                        .takeIf { it.isNotEmpty() }
                        ?.let { addOption("--sub-langs", it) }
                    when (convertSubtitle) {
                        CONVERT_ASS -> addOption("--convert-subs", "ass")
                        CONVERT_SRT -> addOption("--convert-subs", "srt")
                        CONVERT_VTT -> addOption("--convert-subs", "vtt")
                        CONVERT_LRC -> addOption("--convert-subs", "lrc")
                        else -> {}
                    }
                }
                if (formatIdString.isNotEmpty()) {
                    addOption("-f", formatIdString)
                    if (mergeAudioStream) {
                        addOption("--audio-multistreams")
                    }
                } else if (convertAudio) {
                    when (audioConvertFormat) {
                        CONVERT_MP3 -> {
                            addOption("--audio-format", "mp3")
                        }

                        CONVERT_M4A -> {
                            addOption("--audio-format", "m4a")
                        }
                    }
                } else {
                    applyFormatSorter(preferences, toAudioFormatSorter())
                }

                if (embedMetadata) {
                    addOption("--embed-metadata")
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")

                    if (cropArtwork) {
                        val configFile = context.getConfigFile(id)
                        FileUtil.writeContentToFile(CROP_ARTWORK_COMMAND, configFile)
                        addOption("--config", configFile.absolutePath)
                    }
                }
                addOption("--parse-metadata", "%(release_year,upload_date)s:%(meta_date)s")

                if (playlistUrl.isNotEmpty()) {
                    addOption("--parse-metadata", "%(album,playlist,title)s:%(meta_album)s")
                    addOption("--parse-metadata", "%(track_number,playlist_index)d:%(meta_track)s")
                } else {
                    addOption("--parse-metadata", "%(album,title)s:%(meta_album)s")
                }
            }
        }

    /** 최종 파일 정보와 AV1 변환 전 화질을 함께 저장합니다. */
    private fun insertInfoIntoDownloadHistory(
        videoInfo: VideoInfo,
        filePaths: List<String>,
        sourceQualities: Map<String, VideoQuality> = emptyMap(),
    ): List<String> =
        filePaths.onEach {
            kotlinx.coroutines.runBlocking {
                com.plugin.compatibility.PluginDatabaseUtil.insertInfoImmediately(videoInfo.toDownloadedVideoInfo(videoPath = it, sourceQualities = sourceQualities))
            }
        }

    /** SAF 이동 후에도 파일 이름으로 변환 전 화질을 연결합니다. */
    private fun VideoInfo.toDownloadedVideoInfo(
        id: Int = 0,
        videoPath: String,
        sourceQualities: Map<String, VideoQuality> = emptyMap(),
    ): DownloadedVideoInfo =
        this.run {
            val key = if (videoPath.startsWith("content://"))
                androidx.documentfile.provider.DocumentFile.fromSingleUri(context, android.net.Uri.parse(videoPath))?.name
                else java.io.File(videoPath).name
            val srcQuality = sourceQualities[key]
            com.plugin.compatibility.PluginMediaMetadataStore.save(
                videoPath = videoPath,
                sourceQuality = srcQuality,
                postPageIndex = postPageIndex,
                postPageCount = postPageCount,
            )
            DownloadedVideoInfo(
                id = id,
                videoTitle = title,
                videoAuthor = uploader ?: channel ?: uploaderId.toString(),
                videoUrl = webpageUrl ?: originalUrl.toString(),
                thumbnailUrl = thumbnail.toHttpsUrl(),
                videoPath = videoPath,
                extractor = extractorKey,
            )
        }

    /** 나눈 영상도 각 파일의 최종 화질과 변환 전 화질을 보관합니다. */
    private fun insertSplitChapterIntoHistory(videoInfo: VideoInfo, filePaths: List<String>,
        sourceQualities: Map<String, VideoQuality> = emptyMap()) =
        filePaths.onEach {
            kotlinx.coroutines.runBlocking {
                com.plugin.compatibility.PluginDatabaseUtil.insertInfoImmediately(
                    videoInfo
                        .toDownloadedVideoInfo(videoPath = it, sourceQualities = sourceQualities)
                        .copy(videoTitle = it.getFileName())
                )
            }
        }

    @CheckResult
    /** 같은 링크의 기존 파일을 재사용하고 동시 중복 다운로드를 방지합니다. */
    fun downloadVideo(
        videoInfo: VideoInfo? = null,
        playlistUrl: String = "",
        playlistItem: Int = 0,
        taskId: String,
        downloadPreferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>> {
        if (videoInfo == null) return Result.failure(IllegalArgumentException(com.junkfood.seal.App.context.getString(R.string.loc_video_info_missing)))
        // 확장이 꺼진 새 요청은 원본 다운로드 경로로 바로 전달합니다.
        if (!downloadPreferences.reuseExisting) return downloadVideoInternal(videoInfo, playlistUrl, playlistItem, taskId, downloadPreferences, progressCallback)
        return com.plugin.media.reuseOrDownload(videoInfo, downloadPreferences, taskId, progressCallback,
            download = { downloadVideoInternal(videoInfo, playlistUrl, playlistItem, taskId, downloadPreferences, progressCallback) },
            recordHistory = ::insertInfoIntoDownloadHistory)
    }

    /** 기존 영상 다운로드 요청을 실행하고 실제 출력 파일에 후처리를 적용합니다. */
    private fun downloadVideoInternal(
        videoInfo: VideoInfo? = null,
        playlistUrl: String = "",
        playlistItem: Int = 0,
        taskId: String,
        downloadPreferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>> {
        return withCookieSnapshotResult<List<String>>(downloadPreferences) { cookieFile ->
        if (videoInfo == null)
            return Result.failure(Throwable(context.getString(R.string.fetch_info_error_msg)))

        with(downloadPreferences) {
            val url =
                playlistUrl.ifEmpty {
                    videoInfo.originalUrl
                        ?: videoInfo.webpageUrl
                        ?: return Result.failure(
                            Throwable(context.getString(R.string.fetch_info_error_msg))
                        )
                }
            val request = YoutubeDLRequest(url)
            val outputFiles =
                context.getConfigFile("files-${taskId.hashCode()}").apply { writeText("") }
            val pathBuilder = StringBuilder()
            val outputBuilder = StringBuilder()

            request
                .apply {
                    addCommands(
                        listOf("--print-to-file", "after_move:filepath", outputFiles.absolutePath)
                    )
                    addOption("--no-mtime")
                    // 소셜 CDN의 긴 서명 쿼리 대신 이미 분석한 게시물 정보를 파일명에 사용합니다.
                    if ((videoInfo.extractorKey == "Instagram" && InstagramUtil.safeMediaUrl(url)) ||
                        (videoInfo.extractorKey == "TikTok" && TikTokUtil.safeMediaUrl(url))) {
                        addCommands(listOf("--replace-in-metadata", "id", ".+", videoInfo.id))
                        addCommands(listOf("--replace-in-metadata", "title", ".+", videoInfo.title))
                    }
                    // 틱톡 공개 CDN은 게시물 Referer가 없으면 같은 영상 주소에도 403을 반환합니다.
                    videoInfo.webpageUrl?.takeIf {
                        videoInfo.extractorKey == "TikTok" && TikTokUtil.accepts(it)
                    }?.let {
                        addOption("--referer", it)
                        if (TikTokUtil.safeMediaUrl(url)) {
                            // 공개 페이지의 영상 전용 익명 쿠키만 허용하고 사용자 로그인 쿠키는 복사하지 않습니다.
                            videoInfo.httpHeaders["Cookie"]?.takeIf { header ->
                                header.startsWith("tt_chain_token=") && header.length <= 4096 &&
                                    (';' !in header && '\r' !in header && '\n' !in header)
                            }?.let { header -> addOption("--add-header", "Cookie:$header") }
                        }
                    }
                    //                addOption("-v")
                    if (cookies) {
                        enableCookies(userAgentString, checkNotNull(cookieFile))
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    if (proxy) {
                        enableProxy(proxyUrl)
                    }
                    if (forceIpv4) {
                        addOption("-4")
                    }
                    if (debug) {
                        addOption("-v")
                    }
                    if (useDownloadArchive) {
                        val archiveFile = context.getArchiveFile()
                        val archiveFileContent = archiveFile.readText()
                        if (archiveFileContent.contains("${videoInfo.extractor} ${videoInfo.id}")) {
                            return Result.failure(
                                YoutubeDLException(
                                    context.getString(R.string.download_archive_error)
                                )
                            )
                        } else {
                            useDownloadArchive()
                        }
                    }

                    if (rateLimit && maxDownloadRate.isNumberInRange(1, 1000000)) {
                        addOption("-r", "${maxDownloadRate}K")
                    }

                    if (playlistItem != 0 && downloadPlaylist) {
                        addOption("--playlist-items", playlistItem)
                        if (subdirectoryPlaylistTitle && !videoInfo.playlist.isNullOrEmpty()) {
                            outputBuilder.append(PLAYLIST_TITLE_SUBDIRECTORY_PREFIX)
                        }
                        //                    addOption("--compat-options",
                        // "no-youtube-unavailable-videos")
                    } else {
                        addOption("--no-playlist")
                    }

                    if (aria2c) {
                        enableAria2c()
                    } else if (concurrentFragments > 1) {
                        addOption("--concurrent-fragments", concurrentFragments)
                    }

                    if (extractAudio || (videoInfo.vcodec == "none")) {
                        if (privateDirectory) pathBuilder.append(App.privateDownloadDir)
                        else pathBuilder.append(audioDownloadDir)
                        addOptionsForAudioDownloads(
                            id = videoInfo.id,
                            preferences = downloadPreferences,
                            playlistUrl = playlistUrl,
                        )
                    } else {
                        if (privateDirectory) pathBuilder.append(App.privateDownloadDir)
                        else pathBuilder.append(videoDownloadDir)
                        addOptionsForVideoDownloads(downloadPreferences)
                    }
                    val selectedRoot = DownloadFolders.outputRoot(downloadPreferences,
                        if (sdcard) context.getSdcardTempDir(videoInfo.id) else File(pathBuilder.toString()))
                    if (!sdcard) { pathBuilder.clear(); pathBuilder.append(selectedRoot.path) }
                    if (sponsorBlock) {
                        addOption("--sponsorblock-remove", sponsorBlockCategory)
                    }

                    if (createThumbnail) {
                        addOption("--write-thumbnail")
                        addOption("--convert-thumbnails", "png")
                    }
                    if (downloadPreferences.useWebsiteFolder(image = false)) {
                        pathBuilder.append("/${videoInfo.extractorKey}")
                    }

                    if (sdcard) {
                        addOption("-P", selectedRoot.absolutePath)
                    } else {
                        addOption("-P", pathBuilder.toString())
                    }

                    videoClips.forEach {
                        addOption(
                            "--download-sections",
                            "*%d-%d".format(locale = Locale.US, it.start, it.end),
                        )
                    }
                    if (newTitle.isNotEmpty()) {
                        addCommands(listOf("--replace-in-metadata", "title", ".+", newTitle))
                    }
                    if (Build.VERSION.SDK_INT > 23 && !sdcard)
                        addOption("-P", "temp:" + getExternalTempDir())

                    if (splitByChapter) {
                        addOption("-o", OUTPUT_TEMPLATE_CHAPTERS)
                        addOption("--split-chapters")
                    }

                    val output =
                        if (splitByChapter) {
                            OUTPUT_TEMPLATE_SPLIT
                        } else if (videoClips.isEmpty()) {
                            // 빈 출력 인자는 다음 yt-dlp 옵션을 파일명으로 소비하므로 기존 기본값을 사용합니다.
                            outputTemplate.ifBlank { OUTPUT_TEMPLATE_ID }
                        } else {
                            OUTPUT_TEMPLATE_CLIPS
                        }

                    addOption("-o", outputBuilder.append(output).toString())

                    for (s in request.buildCommand()) Log.d(TAG, s)
                }
                .runCatching {
                    YoutubeDL.getInstance()
                        .execute(request = this, processId = taskId, callback = progressCallback)
                }
                .onFailure { th ->
                    return if (
                        sponsorBlock &&
                            th.message?.contains("Unable to communicate with SponsorBlock API") ==
                                true
                    ) {
                        th.printStackTrace()
                        onFinishDownloading(
                            preferences = this,
                            videoInfo = videoInfo,
                            downloadPath = pathBuilder.toString(),
                            sdcardUri = sdcardUri,
                            taskId = taskId,
                            outputPaths = outputFiles.readLines(),
                            progress = progressCallback,
                        )
                    } else Result.failure(th)
                }
            return onFinishDownloading(
                preferences = this,
                videoInfo = videoInfo,
                downloadPath = pathBuilder.toString(),
                sdcardUri = sdcardUri,
                taskId = taskId,
                outputPaths = outputFiles.readLines(),
                progress = progressCallback,
            )
        }
        }
    }

    /** 완료된 실제 출력 경로에 후처리를 적용하고 저장 위치와 이력을 확정합니다. */
    private fun onFinishDownloading(
        preferences: DownloadPreferences,
        videoInfo: VideoInfo,
        downloadPath: String,
        sdcardUri: String,
        taskId: String,
        outputPaths: List<String>,
        progress: ((Float, Long, String) -> Unit)?,
    ): Result<List<String>> =
        preferences.run {
            val actualPaths = outputPaths.map { it.trim() }.filter { java.io.File(it).isFile }
            val sourceQualities = mutableMapOf<String, VideoQuality>()
            val processed =
                runCatching { MediaLibrary.finish(actualPaths, preferences, taskId, progress,
                    mediaRoot = DownloadFolders.outputRoot(preferences, if (sdcard) context.getSdcardTempDir(videoInfo.id) else File(
                        if (privateDirectory) App.privateDownloadDir else if (extractAudio || videoInfo.vcodec == "none") audioDownloadDir else videoDownloadDir)),
                    onOutput = { path, quality ->
                        if (quality != null) sourceQualities[java.io.File(path).name] = quality
                    }) }
                    .getOrElse {
                        return Result.failure(it)
                    }
            val fileName =
                preferences.newTitle.ifEmpty {
                    videoInfo.filename
                        ?: videoInfo.requestedDownloads?.firstOrNull()?.filename
                        ?: videoInfo.title
                }

            Log.d(TAG, "onFinishDownloading: $fileName")
            if (sdcard) {
                moveFilesToSdcard(
                        sdcardUri = sdcardUri,
                        tempPath = context.getSdcardTempDir(videoInfo.id),
                    )
                    .onSuccess {
                        if (privateMode) {
                            return Result.success(emptyList())
                        } else if (splitByChapter) {
                            insertSplitChapterIntoHistory(videoInfo, it, sourceQualities)
                        } else {
                            insertInfoIntoDownloadHistory(videoInfo, it, sourceQualities)
                        }
                    }
            } else {
                (processed.takeIf { it.isNotEmpty() }
                        ?: FileUtil.scanFileToMediaLibraryPostDownload(
                            title = fileName,
                            downloadDir = downloadPath,
                        ))
                    .run {
                        if (privateMode) Result.success(emptyList())
                        else
                            Result.success(
                                if (splitByChapter) {
                                    insertSplitChapterIntoHistory(videoInfo, this, sourceQualities)
                                } else {
                                    insertInfoIntoDownloadHistory(videoInfo, this, sourceQualities)
                                }
                            )
                    }
            }
        }

    @CheckResult
    /** executeCustomCommandTask의 분리된 다운로드 처리를 수행합니다. */
    fun executeCustomCommandTask(
        urlString: String,
        taskId: String,
        template: CommandTemplate,
        preferences: DownloadPreferences,
        progressCallback: ((Float, Long, String) -> Unit),
    ): Result<YoutubeDLResponse> {
        return withCookieSnapshotResult<YoutubeDLResponse>(preferences) { cookieFile ->
        val urlList = urlString.split(Regex("[\n ]")).filter { it.isNotBlank() }

        val request =
            with(preferences) {
                YoutubeDLRequest(urlList).apply {
                    commandDirectory.takeIf { it.isNotEmpty() }?.let { addOption("-P", it) }
                    addOption("--newline")
                    if (aria2c) {
                        enableAria2c()
                    }
                    if (useDownloadArchive) {
                        useDownloadArchive()
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    addOption(
                        "--config-locations",
                        FileUtil.writeContentToFile(template.template, context.getConfigFile())
                            .absolutePath,
                    )
                    if (cookies) {
                        enableCookies(userAgentString, checkNotNull(cookieFile))
                    }
                }
            }

        return runCatching {
            YoutubeDL.getInstance()
                .execute(request = request, processId = taskId, callback = progressCallback)
        }
        }
    }

    /** executeCommandInBackground의 분리된 다운로드 처리를 수행합니다. */
    suspend fun executeCommandInBackground(
        url: String,
        template: CommandTemplate = PreferenceUtil.getTemplate(),
        downloadPreferences: DownloadPreferences = com.plugin.compatibility.createPluginPreferences(),
    ) {
        withCookieSnapshot(downloadPreferences) { cookieFile ->
        downloadPreferences.run {
            val taskId = Downloader.makeKey(url = url, templateName = template.name)
            val notificationId = taskId.toNotificationId()
            val urlList = url.split(Regex("[\n ]")).filter { it.isNotBlank() }

            ToastUtil.makeToastSuspend(context.getString(R.string.start_execute))
            val request =
                YoutubeDLRequest(urlList).apply {
                    commandDirectory.takeIf { it.isNotEmpty() }?.let { addOption("-P", it) }
                    addOption("--newline")
                    if (aria2c) {
                        enableAria2c()
                    }
                    if (useDownloadArchive) {
                        useDownloadArchive()
                    }
                    if (restrictFilenames) {
                        addOption("--restrict-filenames")
                    }
                    addOption(
                        "--config-locations",
                        FileUtil.writeContentToFile(template.template, context.getConfigFile())
                            .absolutePath,
                    )
                    if (cookies) {
                        enableCookies(userAgentString, checkNotNull(cookieFile))
                    }
                }

            onProcessStarted()
            withContext(Dispatchers.Main) { onTaskStarted(template, url) }
            runCatching {
                    val response =
                        YoutubeDL.getInstance().execute(request = request, processId = taskId) {
                            progress,
                            _,
                            text ->
                            NotificationUtil.makeNotificationForCustomCommand(
                                notificationId = notificationId,
                                taskId = taskId,
                                progress = progress.toInt(),
                                templateName = template.name,
                                taskUrl = url,
                                text = text,
                            )
                            Downloader.updateTaskOutput(
                                template = template,
                                url = url,
                                line = text,
                                progress = progress,
                            )
                        }
                    onTaskEnded(template, url, response.out + "\n" + response.err)
                }
                .onFailure {
                    it.printStackTrace()
                    if (it is YoutubeDL.CanceledException) return@onFailure
                    it.message.run {
                        if (isNullOrEmpty()) onTaskEnded(template, url)
                        else onTaskError(this, template, url)
                    }
                }
            onProcessEnded()
        }
        }
    }

}
