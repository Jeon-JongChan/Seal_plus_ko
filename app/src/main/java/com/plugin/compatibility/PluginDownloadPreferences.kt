package com.plugin.compatibility

import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import java.util.concurrent.ConcurrentHashMap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy

/**
 * 플러그인 전용 다운로드 설정 메타데이터입니다.
 * 원본 DownloadPreferences 모델을 오염시키지 않고 플러그인 확장 옵션 22개를 제공합니다.
 */
data class PluginPreferencesRecord(
    val videoContainer: String = "mp4",
    val mediaSelection: Int = 0,
    val downloadAllMedia: Boolean = false,
    val imageResolution: Int = 0,
    val classifyMedia: Boolean = false,
    val qualityThresholdMbps: Int = 4,
    val transcodeAv1: Boolean = false,
    val keepAv1Original: Boolean = false,
    val av1Crf: Int = 32,
    val av1Preset: Int = 8,
    val cookieProfileId: Int = -1,
    val imageFolderName: String = "Images",
    val videoFolderName: String = "Videos",
    val platformFolders: Boolean = false,
    val imageTiers: String = "",
    val videoTiers: String = "",
    val codecRatios: String = "",
    val mediaProcessing: Boolean = false,
    val reuseExisting: Boolean = false,
    val queueEnhancements: Boolean = false,
    val socialProcessing: Boolean = false,
    val listProcessing: Boolean = false,
)

private val preferenceOptionsMap = ConcurrentHashMap<Int, PluginPreferencesRecord>()

private fun DownloadPreferences.prefKey(): Int = System.identityHashCode(this)

/** 플러그인 옵션을 DownloadPreferences에 연결합니다. */
fun DownloadPreferences.attachPluginOptions(record: PluginPreferencesRecord): DownloadPreferences {
    preferenceOptionsMap[prefKey()] = record
    return this
}

private fun DownloadPreferences.getRecord(): PluginPreferencesRecord =
    preferenceOptionsMap[prefKey()] ?: PluginPreferencesRecord()

/** 기본 모델 필드를 바꿀 때 요청에 연결한 확장 옵션도 새 사본에 보존합니다. */
fun DownloadPreferences.copyPreservingPluginOptions(
    update: DownloadPreferences.() -> DownloadPreferences,
): DownloadPreferences = update().attachPluginOptions(getRecord())

/** 원본 화면의 요청에만 저장된 확장 옵션을 보완하고 이미 선택한 옵션은 유지합니다. */
fun DownloadPreferences.withStoredPluginOptions(): DownloadPreferences =
    if (preferenceOptionsMap.containsKey(prefKey())) this
    else com.plugin.runtime.PluginDownloadUtil.configure(this)

/** 원본 저장값과 활성 플러그인 설정을 함께 읽습니다. */
fun createPluginPreferences(): DownloadPreferences =
    com.plugin.runtime.PluginDownloadUtil.configure(DownloadPreferences.createFromPreferences())

/** 원본 필드가 같아도 확장 옵션이 바뀐 새 사본은 화면에 반영합니다. */
fun pluginPreferencesState(initial: DownloadPreferences): MutableState<DownloadPreferences> =
    mutableStateOf(initial, referentialEqualityPolicy())

val DownloadPreferences.videoContainer: String get() = getRecord().videoContainer
val DownloadPreferences.mediaSelection: Int get() = getRecord().mediaSelection
val DownloadPreferences.downloadAllMedia: Boolean get() = getRecord().downloadAllMedia
val DownloadPreferences.imageResolution: Int get() = getRecord().imageResolution
val DownloadPreferences.classifyMedia: Boolean get() = getRecord().classifyMedia
val DownloadPreferences.qualityThresholdMbps: Int get() = getRecord().qualityThresholdMbps
val DownloadPreferences.transcodeAv1: Boolean get() = getRecord().transcodeAv1
val DownloadPreferences.keepAv1Original: Boolean get() = getRecord().keepAv1Original
val DownloadPreferences.av1Crf: Int get() = getRecord().av1Crf
val DownloadPreferences.av1Preset: Int get() = getRecord().av1Preset
val DownloadPreferences.cookieProfileId: Int get() = getRecord().cookieProfileId
val DownloadPreferences.imageFolderName: String get() = getRecord().imageFolderName
val DownloadPreferences.videoFolderName: String get() = getRecord().videoFolderName
val DownloadPreferences.platformFolders: Boolean get() = getRecord().platformFolders
val DownloadPreferences.imageTiers: String get() = getRecord().imageTiers
val DownloadPreferences.videoTiers: String get() = getRecord().videoTiers
val DownloadPreferences.codecRatios: String get() = getRecord().codecRatios
val DownloadPreferences.mediaProcessing: Boolean get() = getRecord().mediaProcessing
val DownloadPreferences.reuseExisting: Boolean get() = getRecord().reuseExisting
val DownloadPreferences.queueEnhancements: Boolean get() = getRecord().queueEnhancements
val DownloadPreferences.socialProcessing: Boolean get() = getRecord().socialProcessing
val DownloadPreferences.listProcessing: Boolean get() = getRecord().listProcessing

/** 플러그인 옵션을 복사하여 갱신합니다. */
fun DownloadPreferences.copyPluginOptions(
    videoContainer: String = this.videoContainer,
    mediaSelection: Int = this.mediaSelection,
    downloadAllMedia: Boolean = this.downloadAllMedia,
    imageResolution: Int = this.imageResolution,
    classifyMedia: Boolean = this.classifyMedia,
    qualityThresholdMbps: Int = this.qualityThresholdMbps,
    transcodeAv1: Boolean = this.transcodeAv1,
    keepAv1Original: Boolean = this.keepAv1Original,
    av1Crf: Int = this.av1Crf,
    av1Preset: Int = this.av1Preset,
    cookieProfileId: Int = this.cookieProfileId,
    imageFolderName: String = this.imageFolderName,
    videoFolderName: String = this.videoFolderName,
    platformFolders: Boolean = this.platformFolders,
    imageTiers: String = this.imageTiers,
    videoTiers: String = this.videoTiers,
    codecRatios: String = this.codecRatios,
    mediaProcessing: Boolean = this.mediaProcessing,
    reuseExisting: Boolean = this.reuseExisting,
    queueEnhancements: Boolean = this.queueEnhancements,
    socialProcessing: Boolean = this.socialProcessing,
    listProcessing: Boolean = this.listProcessing,
): DownloadPreferences {
    val newRecord = PluginPreferencesRecord(
        videoContainer = videoContainer,
        mediaSelection = mediaSelection,
        downloadAllMedia = downloadAllMedia,
        imageResolution = imageResolution,
        classifyMedia = classifyMedia,
        qualityThresholdMbps = qualityThresholdMbps,
        transcodeAv1 = transcodeAv1,
        keepAv1Original = keepAv1Original,
        av1Crf = av1Crf,
        av1Preset = av1Preset,
        cookieProfileId = cookieProfileId,
        imageFolderName = imageFolderName,
        videoFolderName = videoFolderName,
        platformFolders = platformFolders,
        imageTiers = imageTiers,
        videoTiers = videoTiers,
        codecRatios = codecRatios,
        mediaProcessing = mediaProcessing,
        reuseExisting = reuseExisting,
        queueEnhancements = queueEnhancements,
        socialProcessing = socialProcessing,
        listProcessing = listProcessing,
    )
    val copy = this.copy()
    copy.attachPluginOptions(newRecord)
    return copy
}

