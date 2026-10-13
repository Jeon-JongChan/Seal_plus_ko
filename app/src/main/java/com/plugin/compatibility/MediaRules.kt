package com.plugin.compatibility


import com.junkfood.seal.util.*
import java.io.File
import java.math.BigInteger
import java.net.URI
import kotlinx.serialization.json.*

/** 파일 관리와 게시물 선택에 공통으로 사용하는 순수 계산 규칙입니다. */
object MediaRules {
    /** 얼굴 분류 결과 폴더와 모든 하위 경로를 다른 플러그인 작업에서 제외합니다. */
    fun isNoFacePath(path: String): Boolean {
        val decoded = if (path.startsWith("content://") || path.startsWith("file://"))
            runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path) else path
        return decoded.replace('\\', '/').split('/').any { it.substringAfterLast(':').equals("noface", true) }
    }

    /** 제목과 확장자 사이에 저장된 미디어 ID를 읽습니다. */
    private fun savedSourceId(name: String): String? =
        Regex("\\[([A-Za-z0-9_-]+)](?:[-_]av1)?\\.[^.]+$").find(name)?.groupValues?.get(1)

    /** 같은 파일명 이력 중 URL의 ID로 구분하거나 유일한 원래 경로를 선택합니다. */
    fun reconnectionSource(name: String, candidates: List<com.junkfood.seal.database.objects.DownloadedVideoInfo>): String? {
        if (savedSourceId(name) == null) return null
        val valid = candidates.filter { info ->
            val uri = runCatching { URI(info.videoUrl) }.getOrNull()
            uri?.scheme in setOf("https", "http") && !uri?.host.isNullOrBlank()
        }
        val paths = valid.map { it.videoPath }.distinct()
        // 원본 Seal의 묶음 다운로드는 개별 파일 ID와 요청 URL이 달라도 정상입니다.
        if (paths.size == 1) return paths.single()
        // 여러 폴더에 같은 이름을 저장한 이력은 URL까지 일치하는 유일한 경로만 선택합니다.
        return valid.filter { canReconnect(name, it.videoUrl) }.map { it.videoPath }.distinct().singleOrNull()
    }

    /** 기본 파일명의 게시물 ID가 원래 URL에 포함된 경우에만 자동 연결을 허용합니다. */
    fun canReconnect(name: String, url: String): Boolean {
        val id = savedSourceId(name) ?: return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) return false
        if (uri.host.lowercase() in setOf("instagram.com", "www.instagram.com")) {
            val shortcode = Regex("^/(?:p|reel|tv)/([^/]+)/?$").find(uri.path.orEmpty())?.groupValues?.get(1)
            if (shortcode != null && (shortcode == id || runCatching { instagramId(shortcode) == id }.getOrDefault(false))) return true
        }
        // 제목의 우연한 부분 문자열이 아닌 URL의 독립된 ID만 인정합니다.
        return Regex("(?<![A-Za-z0-9_-])${Regex.escape(id)}(?![A-Za-z0-9_-])")
            .containsMatchIn(listOf(uri.path, uri.query, uri.fragment).joinToString("/"))
    }
    /** 공식 게시물·공유 링크만 자동 분석 대상으로 허용합니다. */
    private fun acceptsTikTok(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.port in setOf(-1, 80, 443) &&
            when (uri.host?.lowercase()) {
                "vm.tiktok.com", "vt.tiktok.com" -> uri.path.orEmpty().trim('/').isNotEmpty()
                "www.tiktok.com", "tiktok.com" -> Regex("^/(?:@[^/]+|share)/(?:video|photo)/[0-9]+/?$").matches(uri.path.orEmpty()) || uri.path.orEmpty().startsWith("/t/")
                else -> false
            }
    }.getOrDefault(false)


    /** 일반 경로와 문서 URI에서 폴더 탭에 사용할 부모 위치를 읽습니다. */
    fun parentFolder(path: String): String = if (path.startsWith("content://"))
        runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path).substringBeforeLast('/', path)
    else File(path).parent.orEmpty()

    /** 설정 폴더와 이력 폴더의 공통 상위 위치에서 탐색을 시작합니다. */
    fun historyFolderRoot(preferred: String, folders: List<String>): String {
        val paths = (folders + preferred).filter { it.isNotBlank() }.distinct()
        if (paths.isEmpty()) return ""
        var root = paths.first().trimEnd('/')
        for (path in paths.drop(1)) {
            while (root.isNotEmpty() && path != root && !path.startsWith(root + "/"))
                root = root.substringBeforeLast('/', "")
        }
        return when {
            root.isEmpty() && paths.all { it.startsWith('/') } -> "/"
            root == "content:" || root == "content:/" -> "" // 서로 다른 문서 공급자는 가상 루트에서 구분합니다.
            else -> root
        }
    }

    /** 현재 폴더의 직접 하위 폴더만 반환하며 이름이 같아도 전체 경로로 구분합니다. */
    fun historyFolderChildren(current: String, folders: List<String>): List<String> {
        if (current.isEmpty()) return folders.map { path ->
            if (path.startsWith('/')) "/" else path.substringBefore("/document/") + "/document"
        }.distinct().sorted()
        val prefix = current.trimEnd('/') + "/"
        return folders.filter { it.startsWith(prefix) }.map { prefix + it.removePrefix(prefix).substringBefore('/') }.distinct().sorted()
    }

    /** 탐색 루트 밖으로 나가지 않도록 상위 이동 경로를 제한합니다. */
    fun historyFolderParent(current: String, root: String): String =
        if (current == root || root.isEmpty() && current.endsWith("/document")) root else current.trimEnd('/').substringBeforeLast('/', "").ifEmpty { if (root == "/") "/" else root }

    /** 파일 확장자와 실제 트랙 MIME로 전체·영상·이미지·오디오 탭을 구분합니다. */
    fun mediaKind(path: String, codec: String = ""): Int = when {
        codec.startsWith("image/") || path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "avif", "gif") -> 2
        codec.startsWith("audio/") || path.substringAfterLast('.').lowercase() in setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "alac") -> 3
        else -> 1
    }

    /** 코덱 MIME를 저장값 변경 없이 사용자에게 읽기 쉬운 이름으로 표시합니다. */
    fun codecLabel(codec: String): String = when (codec.lowercase()) {
        "video/avc", "avc", "h264", "avc1" -> "H.264"
        "video/hevc", "hevc", "h265", "hev1", "hvc1" -> "H.265"
        "video/av01", "av01", "av1" -> "AV1"
        "video/x-vnd.on2.vp9", "vp9", "vp09" -> "VP9"
        "video/x-vnd.on2.vp8", "vp8" -> "VP8"
        else -> codec.removePrefix("image/").removePrefix("audio/").uppercase()
    }

    /** 기존 분류 기준을 편집 가능한 JSON 배열로 변환합니다. */
    fun classificationJson(images: String, videos: String): String {
        val data = buildJsonObject {
            put("images", buildJsonArray { imageTiers(images).orEmpty().forEach { (short, long) ->
                add(buildJsonObject { put("short", short); put("long", long) })
            } })
            put("videos", buildJsonArray { videoTiers(videos).orEmpty().forEach { tier ->
                add(buildJsonObject { put("height", tier.first); put("mbps30", tier.second); put("mbps60", tier.third) })
            } })
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), data)
    }

    /** JSON 구조와 분류 경계를 모두 확인한 뒤 기존 분류 엔진의 입력으로 변환합니다. */
    fun parseClassificationJson(text: String): Pair<String, String>? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.keys == setOf("images", "videos"))
        val images = root.getValue("images").jsonArray.joinToString(",") {
            val item = it.jsonObject
            require(item.keys == setOf("short", "long"))
            "${item.getValue("short").jsonPrimitive.int}x${item.getValue("long").jsonPrimitive.int}"
        }
        val videos = root.getValue("videos").jsonArray.joinToString(",") {
            val item = it.jsonObject
            require(item.keys == setOf("height", "mbps30", "mbps60"))
            "${item.getValue("height").jsonPrimitive.int}:${item.getValue("mbps30").jsonPrimitive.double}:${item.getValue("mbps60").jsonPrimitive.double}"
        }
        require(imageTiers(images) != null && videoTiers(videos) != null)
        images to videos
    }.getOrNull()
    /** 백업·대기열의 잘못된 속도 값은 기존 균형 설정으로 복구합니다. */
    fun av1Preset(value: Int): Int = value.takeIf { it in 0..12 } ?: 8

    const val DISABLED_IMAGE_TIERS = "disabled_image_tiers"
    const val DISABLED_VIDEO_TIERS = "disabled_video_tiers"

    const val DEFAULT_IMAGE_TIERS = "720x1280,1080x1920,1440x2560,2160x3840"
    const val LEGACY_VIDEO_TIERS = "360:0.125:0.188,480:0.313:0.500,720:0.625:0.938,1080:1.000:1.500,1440:2.000:3.000,2160:5.000:7.563"
    const val DEFAULT_VIDEO_TIERS = "360:0.040:0.060,480:0.070:0.105,720:0.140:0.210,1080:0.250:0.375,1440:0.600:0.900,2160:1.300:1.950"
    const val DEFAULT_CODEC_RATIOS = "0.66,0.77,0.55"

    /** 구버전 기본값만 새 표로 교체하고 사용자 기준과 비활성 구간은 보존합니다. */
    fun currentVideoTiers(value: String): String = if (value.substringBefore(';') == LEGACY_VIDEO_TIERS)
        DEFAULT_VIDEO_TIERS + value.substringAfter(';', "").takeIf(String::isNotBlank)?.let { ";$it" }.orEmpty()
    else value

    /** 재분류는 화질 폴더 한 칸만 벗어나며 다운로드 루트와 사용자 부모 폴더를 보존합니다. */
    fun classificationBase(parent: File, root: File): File {
        val directory = parent.canonicalFile
        val boundary = root.canonicalFile
        require(directory.toPath().startsWith(boundary.toPath())) { "다운로드 폴더 밖의 파일은 분류할 수 없습니다." }
        // 정규 경로는 경계 검증에만 사용해 Android의 /data/user/0 별칭과 이력 경로를 유지합니다.
        return if (directory != boundary && isQualityFolder(parent.name)) parent.parentFile!! else parent
    }

    /** 기존 앱에서 생성한 영상·이미지 화질 폴더 이름을 인식합니다. */
    // ponytail: 구버전 생성 기록이 없어 이름으로 판별합니다. 동명 수동 폴더까지 구분하려면 생성 경로 메타데이터가 필요합니다.
    fun isQualityFolder(name: String): Boolean = name.matches(Regex("[0-9]+(p|x[0-9]+)", RegexOption.IGNORE_CASE))

    /** 표의 코덱별 경계를 그대로 사용하며 JSON에서 조정한 H.264 경계도 비례 반영합니다. */
    fun videoThreshold(height: Int, h264: Double, highFps: Boolean, codec: String,
        ratios: String = DEFAULT_CODEC_RATIOS): Double? {
        val column = when (codecLabel(codec)) {
            "H.264" -> 0
            "H.265" -> 1
            "VP9" -> 2
            "AV1" -> 3
            else -> return null
        }
        val values = when (height) {
            360 -> if (highFps) listOf(.060, .045, .045, .030) else listOf(.040, .030, .030, .020)
            480 -> if (highFps) listOf(.105, .075, .083, .053) else listOf(.070, .050, .055, .035)
            720 -> if (highFps) listOf(.210, .143, .165, .120) else listOf(.140, .095, .110, .080)
            1080 -> if (highFps) listOf(.375, .255, .285, .210) else listOf(.250, .170, .190, .140)
            1440 -> if (highFps) listOf(.900, .600, .675, .495) else listOf(.600, .400, .450, .330)
            2160 -> if (highFps) listOf(1.950, 1.275, 1.500, 1.080) else listOf(1.300, .850, 1.000, .720)
            else -> return if (column == 0) h264 else codecRatios(ratios)?.let { h264 * it[column - 1] }
        }
        return h264 * (values[column] / values[0])
    }

    /** 기존 직렬화 필드에 비활성 구간을 함께 넣어 작업 등록 시 설정 사본을 보존합니다. */
    fun withDisabledTiers(tiers: String, disabled: String): String =
        tiers.substringBefore(';') + disabled.takeIf(String::isNotBlank)?.let { ";disabled=$it" }.orEmpty()

    /** 구버전 구간 문자열은 모두 활성 상태로 읽습니다. */
    fun disabledTiers(tiers: String): Set<String> =
        tiers.substringAfter(";disabled=", "").split(',').filter(String::isNotBlank).toSet()

    /** 폴더 한 칸의 이름만 허용하며 경로 이동·제어 문자·플랫폼 예약 문자를 막습니다. */
    fun folderName(value: String, fallback: String): String = value.takeUnless {
        // 이전 설정의 번역된 기본 폴더 이름은 새 다운로드에서 영문 기본값으로 통일합니다.
        fallback == "Images" && it == "이미지"
    }?.takeIf {
        it.isNotBlank() && it.length <= 64 && it == it.trim() && it !in setOf(".", "..") &&
            !it.endsWith('.') && !it.contains(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"))
    } ?: fallback

    /** 빈 이름은 하위 폴더 생략으로 보존하고 잘못된 이름만 기본값으로 복구합니다. */
    fun optionalFolderName(value: String, fallback: String): String =
        if (value.isEmpty()) "" else folderName(value, fallback)

    /** 이미지 기준의 짧은 변과 긴 변을 검증하고 오름차순으로 정리합니다. */
    fun imageTiers(value: String): List<Pair<Int, Int>>? = runCatching {
        value.substringBefore(';').split(',').map { item ->
            val size = item.trim().lowercase().split('x').map { it.toInt() }.sorted()
            require(size.size == 2 && size.all { it in 1..65536 })
            size[0] to size[1]
        }.also { require(it.size in 1..32 && it.distinct().size == it.size) }
            .sortedBy { it.first.toLong() * it.second }
    }.getOrNull()

    /** 영상 기준은 높이:30fps MB/s:60fps MB/s 형식이며 잘못된 값은 저장하지 않습니다. */
    fun videoTiers(value: String): List<Triple<Int, Double, Double>>? = runCatching {
        value.substringBefore(';').split(',').map { item ->
            val parts = item.trim().split(':')
            require(parts.size == 3)
            Triple(parts[0].toInt(), parts[1].toDouble(), parts[2].toDouble()).also {
                require(it.first in 1..16384 && it.second.isFinite() && it.third.isFinite() && it.second > 0 && it.third >= it.second)
            }
        }.sortedBy { it.first }.also { tiers ->
            require(tiers.size in 1..32 && tiers.map { it.first }.distinct().size == tiers.size)
            require(tiers.zipWithNext().all { (a, b) -> a.second < b.second && a.third < b.third })
        }
    }.getOrNull()

    /** H265·VP9·AV1의 H264 대비 압축률을 0보다 크고 1 이하인 값으로 검증합니다. */
    fun codecRatios(value: String): List<Double>? = runCatching {
        value.split(',').map { it.trim().toDouble() }.also {
            require(it.size == 3 && it.all { ratio -> ratio.isFinite() && ratio > 0 && ratio <= 1 })
        }
    }.getOrNull()

    /** 활성 화질 구간의 하한 또는 상한을 선택하고 해당 방향의 범위 밖은 기본 폴더에 둡니다. */
    fun classificationFolder(quality: com.junkfood.seal.database.objects.VideoQuality?,
        images: String = DEFAULT_IMAGE_TIERS, videos: String = DEFAULT_VIDEO_TIERS,
        ratios: String = DEFAULT_CODEC_RATIOS, target: Int = 0, upperFirst: Boolean = true): String? {
        quality ?: return null
        val image = quality.codec.startsWith("image/")
        if ((target == 1 && image) || (target == 2 && !image)) return null
        val short = minOf(quality.width, quality.height)
        val long = maxOf(quality.width, quality.height)
        if (short <= 0) return null
        if (image) {
            val tiers = imageTiers(images) ?: return null
            val enabled = tiers.filter { "${it.first}x${it.second}" !in disabledTiers(images) }
            val tier = if (upperFirst) enabled.lastOrNull { short >= it.first && long >= it.second }
                else enabled.firstOrNull { short <= it.first || long <= it.second }
            return tier?.let { "${it.first}x${it.second}" }
        }
        val mbps = bitrateMbps(quality.bytes, quality.duration)?.div(8) ?: return null
        if (quality.frameRate <= 0 || !quality.frameRate.isFinite() || quality.codec.isBlank() || quality.codec == "unknown") return null
        val tiers = videoTiers(videos) ?: return null
        val enabled = tiers.filter { "${it.first}p" !in disabledTiers(videos) }
        if (enabled.isEmpty() || videoThreshold(enabled.first().first, enabled.first().second,
            quality.frameRate >= 48, quality.codec, ratios) == null) return null
        val tier = if (upperFirst) enabled.lastOrNull { short >= it.first && videoThreshold(it.first,
            if (quality.frameRate >= 48) it.third else it.second, quality.frameRate >= 48, quality.codec, ratios)
                ?.let { threshold -> mbps + 1e-9 >= threshold } == true }
        // 하위 우선은 사용자 지정 구간의 실제 해상도 상한이며 마지막 상한 초과는 분류하지 않습니다.
        else enabled.firstOrNull { short <= it.first }
        return tier?.let { "${it.first}p" }

    }
    /** 확장자를 보존하고 이미 사용 중인 번호를 건너뛰어 두 파일을 모두 유지합니다. */
    fun availableFileName(name: String, occupied: (String) -> Boolean): String {
        if (!occupied(name)) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val extension = name.substring(dot)
        var number = 1
        while (occupied("$base ($number)$extension")) number++
        return "$base ($number)$extension"
    }

    /** 자동 유형에서 명시적인 재생목록 링크를 기존 목록 조회로 보냅니다. */
    fun isPlaylistUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.rawQuery.orEmpty().split('&').any { it.substringBefore('=') == "list" && it.substringAfter('=', "").isNotBlank() } ||
            uri.path.orEmpty().split('/').any { it in setOf("playlist", "playlists") }
    }.getOrDefault(false)
    /** 이미 AV1·VP9인 영상은 대체·복제 모두 재인코딩하지 않습니다. */
    fun needsAv1Encoding(codec: String): Boolean = codec.lowercase() !in
        setOf("video/av01", "video/x-vnd.on2.vp9", "av1", "av01", "vp9", "vp09")
    /** 지원 컨테이너만 허용하고 저장 값이 없거나 손상되면 MP4를 사용합니다. */
    fun videoContainer(value: String): String = value.lowercase().takeIf { it in setOf("mp4", "mkv") } ?: "mp4"

    /** 파일 경로와 file URI를 같은 파일로 비교하되 문서 URI는 그대로 비교합니다. */
    fun sameFilePath(first: String?, second: String): Boolean {
        if (first == null) return false
        if (first == second) return true
        return runCatching {
            /** 일반 경로·file URI만 로컬 파일로 변환합니다. */
            fun localPath(value: String): File? {
                // Windows 드라이브 경로·공백이 있는 일반 경로는 URI로 해석하지 않습니다.
                if (!value.contains("://") && !value.startsWith("file:")) return File(value)
                val uri = URI(value)
                return when (uri.scheme?.lowercase()) {
                    null -> File(value)
                    "file" -> File(uri)
                    else -> null
                }
            }
            val a = localPath(first) ?: return@runCatching false
            val b = localPath(second) ?: return@runCatching false
            a.canonicalFile == b.canonicalFile
        }.getOrDefault(false)
    }

    /** 음원 요청을 제외한 인스타그램 게시물은 종류 선택 없이 먼저 분석합니다. */
    fun usesInstagramMediaApi(url: String, mediaSelection: Int, extractAudio: Boolean): Boolean =
        !extractAudio && runCatching {
            val uri = URI(url)
            uri.scheme?.lowercase() in setOf("http", "https") && uri.userInfo == null &&
                uri.host?.lowercase() in setOf("instagram.com", "www.instagram.com") &&
                uri.port in setOf(-1, 80, 443) &&
                uri.path.orEmpty().trim('/').isNotEmpty()
        }.getOrDefault(false)

    /** 게시물은 혼합 미디어일 때만 선택하며 계정 목록은 명시적 선택을 유지합니다. */
    fun requiresInstagramSelection(urls: List<String>, images: List<Boolean>): Boolean =
        urls.any { instagramAccount(it) != null } || images.distinct().size > 1

    /** 계정 목록은 선택을 유지하고 혼합 게시물만 모두 받기 옵션으로 선택을 생략합니다. */
    fun requiresMediaSelection(urls: List<String>, items: List<InstagramMedia>, downloadAll: Boolean): Boolean =
        !downloadAll && (urls.any { instagramAccount(it) != null } || items.size > 1)

    /** 영상 다운로드 전 인스타그램·틱톡 게시물의 이미지와 영상 항목을 분석합니다. */
    fun usesSocialMediaApi(url: String, extractAudio: Boolean): Boolean =
        !extractAudio && (usesInstagramMediaApi(url, 0, false) || acceptsTikTok(url))

    /** 일반 목록 조회에 전달할 HTTP(S) 주소를 검증하며 인증 정보·잘못된 포트는 거부합니다. */
    fun playlistUrl(input: String): String? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        return uri.toASCIIString().takeIf {
            uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null &&
                (uri.port == -1 || uri.port in 1..65535)
        }
    }

    /** AV1 검증 실패 원인과 Android 없이 사용할 기본 진단 메시지입니다. */
    enum class Av1Failure(val description: String) {
        CANCELED("AV1 conversion canceled. The original was kept."),
        OUTPUT_INVALID("Could not verify the AV1 output file. The original was kept."),
        DURATION_INVALID("AV1 duration verification failed. The original was kept."),
        TARGET_EXISTS("The AV1 output file already exists."),
        SAVE_FAILED("Could not save the verified AV1 file."),
        SOURCE_DELETE_FAILED("The AV1 file was saved, but the original could not be removed."),
    }

    /** 변환 파일 검증 후 선택에 따라 원본을 보존하거나 대체합니다. */
    fun replaceWithAv1(
        source: File,
        temporary: File,
        destination: File,
        duration: Double,
        convertedDuration: Double,
        canceled: Boolean,
        keepOriginal: Boolean = false,
        failureMessage: (Av1Failure) -> String = { it.description },
    ): File {
        check(!canceled) { failureMessage(Av1Failure.CANCELED) }
        check(source.isFile && temporary.isFile && temporary.length() > 0) {
            failureMessage(Av1Failure.OUTPUT_INVALID)
        }
        check(
            duration > 0 &&
                convertedDuration > 0 &&
                kotlin.math.abs(convertedDuration - duration) <= 2.0
        ) {
            failureMessage(Av1Failure.DURATION_INVALID)
        }
        check(!destination.exists()) { failureMessage(Av1Failure.TARGET_EXISTS) }
        // 같은 폴더에서 이름 변경이 성공한 이후에만 원본을 제거합니다.
        check(temporary.renameTo(destination)) { failureMessage(Av1Failure.SAVE_FAILED) }
        if (!keepOriginal) check(source.delete()) { failureMessage(Av1Failure.SOURCE_DELETE_FAILED) }
        return destination
    }

    /** 계정 이름 또는 공식 프로필 주소를 검증하고 저장할 계정 이름으로 정규화합니다. */
    fun instagramAccount(input: String): String? {
        val value = input.trim()
        val account = if (value.startsWith("https://") || value.startsWith("http://")) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.host?.lowercase() !in setOf("instagram.com", "www.instagram.com") ||
                uri.userInfo != null || uri.port !in setOf(-1, 80, 443)) return null
            val parts = uri.path.orEmpty().trim('/').split('/')
            if (parts.size != 1) return null
            parts.single()
        } else value.removePrefix("@")
        // 게시물·로그인 경로와 점만 있는 경로를 계정으로 저장하지 않습니다.
        return account.lowercase().takeIf {
            it.matches(Regex("[a-z0-9._]{1,30}")) &&
                it !in setOf(".", "..", "p", "reel", "reels", "tv", "accounts", "explore", "stories", "direct")
        }
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
                host.endsWith("tiktok.com") && Regex("^/@[^/]+/(video|photo)/[0-9]+$").matches(path) ->
                    "tiktok:${path.substringAfterLast('/')}"
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
                require(digit >= 0) { "Invalid Instagram post URL." }
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
