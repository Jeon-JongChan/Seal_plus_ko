package com.plugin.compatibility

import com.junkfood.seal.util.DownloadUtil.DownloadPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy

/**
 * 플러그인 전용 다운로드 설정 메타데이터입니다.
 * 원본 DownloadPreferences 모델을 오염시키지 않고 플러그인 확장 옵션을 제공합니다.
 */
@kotlinx.serialization.Serializable
data class PluginPreferencesRecord(
    val videoContainer: String = "mp4",
    val mediaSelection: Int = 0,
    val downloadAllMedia: Boolean = false,
    val imageResolution: Int = 0,
    val classifyMedia: Boolean = false,
    val classificationTarget: Int = 0,
    val classificationUpperFirst: Boolean = false,
    val selectedFolder: DownloadFolder? = null,
    val websiteFolderTarget: Int = 0,
    val qualityThresholdMbps: Int = 4,
    val transcodeAv1: Boolean = false,
    val keepAv1Original: Boolean = false,
    val av1Crf: Int = 32,
    val av1Preset: Int = 8,
    val cookieProfileId: Int = -1,
    val cookieProfileExplicit: Boolean = false,
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
)

// 이미 설치된 Guava의 약한 식별 키로 동일 값의 요청을 구별하고 소멸한 화면 설정을 회수합니다.
private val preferenceOptionsMap = com.google.common.collect.MapMaker().weakKeys()
    .makeMap<DownloadPreferences, PluginPreferencesRecord>()
private val defaultPluginOptions = PluginPreferencesRecord()

/** 플러그인 옵션을 DownloadPreferences에 연결합니다. */
fun DownloadPreferences.attachPluginOptions(record: PluginPreferencesRecord): DownloadPreferences {
    preferenceOptionsMap[this] = record
    return this
}

/** 등록하지 않은 원본 요청의 기본 옵션은 매번 생성하지 않고 공유합니다. */
private fun DownloadPreferences.getRecord(): PluginPreferencesRecord =
    preferenceOptionsMap[this] ?: defaultPluginOptions

/** 대기열 백업에 연결할 요청 당시 확장 설정이며 연결되지 않은 구형 요청은 null입니다. */
fun DownloadPreferences.storedPluginOptions(): PluginPreferencesRecord? = preferenceOptionsMap[this]

/** 기본 모델 필드를 바꿀 때 요청에 연결한 확장 옵션도 새 사본에 보존합니다. */
fun DownloadPreferences.copyPreservingPluginOptions(
    update: DownloadPreferences.() -> DownloadPreferences,
): DownloadPreferences = update().attachPluginOptions(getRecord())

/** 원본 화면의 요청에만 저장된 확장 옵션을 보완하고 이미 선택한 옵션은 유지합니다. */
fun DownloadPreferences.withStoredPluginOptions(): DownloadPreferences =
    if (preferenceOptionsMap.containsKey(this)) this
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
/** 하위 폴더 분류 대상입니다: 0은 둘 다, 1은 비디오, 2는 이미지입니다. */
val DownloadPreferences.classificationUpperFirst: Boolean get() = getRecord().classificationUpperFirst
val DownloadPreferences.selectedFolder: DownloadFolder? get() = getRecord().selectedFolder
val DownloadPreferences.classificationTarget: Int get() = getRecord().classificationTarget
/** 웹사이트별 폴더 대상입니다: 0은 둘 다, 1은 동영상, 2는 이미지입니다. */
val DownloadPreferences.websiteFolderTarget: Int get() = getRecord().websiteFolderTarget

/** 원본 웹사이트 설정과 별도 적용 대상을 함께 검사합니다. */
fun DownloadPreferences.useWebsiteFolder(image: Boolean): Boolean =
    selectedFolder == null && subdirectoryExtractor && (websiteFolderTarget == 0 || websiteFolderTarget == if (image) 2 else 1)
val DownloadPreferences.qualityThresholdMbps: Int get() = getRecord().qualityThresholdMbps
val DownloadPreferences.transcodeAv1: Boolean get() = getRecord().transcodeAv1
val DownloadPreferences.keepAv1Original: Boolean get() = getRecord().keepAv1Original
val DownloadPreferences.av1Crf: Int get() = getRecord().av1Crf
val DownloadPreferences.av1Preset: Int get() = getRecord().av1Preset
val DownloadPreferences.cookieProfileExplicit: Boolean get() = getRecord().cookieProfileExplicit
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

/** 플러그인 옵션을 복사하여 갱신합니다. */
fun DownloadPreferences.copyPluginOptions(
    videoContainer: String = this.videoContainer,
    mediaSelection: Int = this.mediaSelection,
    downloadAllMedia: Boolean = this.downloadAllMedia,
    imageResolution: Int = this.imageResolution,
    classifyMedia: Boolean = this.classifyMedia,
    classificationTarget: Int = this.classificationTarget,
    classificationUpperFirst: Boolean = this.classificationUpperFirst,
    selectedFolder: DownloadFolder? = this.selectedFolder,
    websiteFolderTarget: Int = this.websiteFolderTarget,
    qualityThresholdMbps: Int = this.qualityThresholdMbps,
    transcodeAv1: Boolean = this.transcodeAv1,
    keepAv1Original: Boolean = this.keepAv1Original,
    av1Crf: Int = this.av1Crf,
    av1Preset: Int = this.av1Preset,
    cookieProfileId: Int = this.cookieProfileId,
    cookieProfileExplicit: Boolean = this.cookieProfileExplicit,
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
): DownloadPreferences {
    val newRecord = PluginPreferencesRecord(
        videoContainer = videoContainer,
        mediaSelection = mediaSelection,
        downloadAllMedia = downloadAllMedia,
        imageResolution = imageResolution,
        classifyMedia = classifyMedia,
        classificationTarget = classificationTarget,
        classificationUpperFirst = classificationUpperFirst,
        selectedFolder = selectedFolder,
        websiteFolderTarget = websiteFolderTarget,
        qualityThresholdMbps = qualityThresholdMbps,
        transcodeAv1 = transcodeAv1,
        keepAv1Original = keepAv1Original,
        av1Crf = av1Crf,
        av1Preset = av1Preset,
        cookieProfileId = cookieProfileId,
        cookieProfileExplicit = cookieProfileExplicit,
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
    )
    val copy = this.copy()
    copy.attachPluginOptions(newRecord)
    return copy
}

