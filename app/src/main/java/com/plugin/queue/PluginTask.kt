package com.plugin.queue

import com.junkfood.seal.database.objects.CommandTemplate
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.Format
import com.junkfood.seal.util.VideoInfo
import com.junkfood.seal.util.toHttpsUrl
import com.plugin.compatibility.InstagramMedia
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.roundToInt

/** 플러그인 대기열이 원본 `Task`와 분리해 보유하는 다운로드 요청입니다. */
@Serializable
data class PluginTask(
    val url: String,
    val type: TypeInfo = TypeInfo.URL,
    val preferences: DownloadUtil.DownloadPreferences,
    val id: String = createId(url, type, preferences),
    val timeCreated: Long = System.currentTimeMillis(),
) : Comparable<PluginTask> {

    /** 요청 생성 순서로 대기열을 정렬합니다. */
    override fun compareTo(other: PluginTask): Int = timeCreated.compareTo(other.timeCreated)

    /** 생성 시간 차이로 같은 요청이 중복 등록되지 않도록 원본 요청의 동등성을 유지합니다. */
    override fun equals(other: Any?): Boolean = other is PluginTask &&
        url == other.url && type == other.type && preferences == other.preferences && id == other.id

    /** 요청의 동등성과 같은 필드만 해시에 포함합니다. */
    override fun hashCode(): Int = arrayOf(url, type, preferences, id).contentHashCode()

    /** 플러그인 전용 입력 유형입니다. */
    @Serializable
    sealed interface TypeInfo {
        /** 재생목록에서 선택된 항목의 순번입니다. */
        @Serializable data class Playlist(val index: Int = 0) : TypeInfo

        /** 사용자 정의 명령의 요청입니다. */
        @Serializable data class CustomCommand(val template: CommandTemplate) : TypeInfo

        /** 일반 URL 다운로드입니다. */
        @Serializable data object URL : TypeInfo

        /** 소셜 게시물의 개별 이미지 또는 영상입니다. */
        @Serializable data class SocialMedia(val media: InstagramMedia) : TypeInfo
    }

    /** 플러그인 엔진의 현재 처리 상태와 화면 표시 정보를 묶습니다. */
    @Serializable
    data class State(
        val downloadState: DownloadState,
        val videoInfo: VideoInfo?,
        val viewState: ViewState,
    )

    /** 플러그인 엔진의 상태 전이와 취소 정보를 정의합니다. */
    @Serializable
    sealed interface DownloadState : Comparable<DownloadState> {
        /** 실행 중인 처리를 취소할 수 있는 상태입니다. */
        interface Cancelable {
            val job: Job
            val taskId: String
            val action: RestartableAction
        }

        /** 실패·취소 상태에서 다시 시작할 동작입니다. */
        interface Restartable {
            val action: RestartableAction
        }

        /** 아직 정보 조회를 시작하지 않았습니다. */
        @Serializable data object Idle : DownloadState

        /** yt-dlp 정보 조회가 진행 중입니다. */
        @Serializable
        data class FetchingInfo(
            @Transient override val job: Job = Job(),
            override val taskId: String,
        ) : DownloadState, Cancelable {
            override val action: RestartableAction = RestartableAction.FetchInfo
        }

        /** 정보 조회를 마치고 다운로드를 기다립니다. */
        @Serializable data object ReadyWithInfo : DownloadState

        /** 파일 다운로드가 진행 중입니다. */
        @Serializable
        data class Running(
            @Transient override val job: Job = Job(),
            override val taskId: String,
            val progress: Float = PROGRESS_INDETERMINATE,
            val progressText: String = "",
        ) : DownloadState, Cancelable {
            override val action: RestartableAction = RestartableAction.Download
        }

        /** 사용자가 취소한 상태입니다. */
        @Serializable
        data class Canceled(override val action: RestartableAction, val progress: Float? = null) :
            DownloadState, Restartable

        /** 처리 중 오류가 발생한 상태입니다. */
        @Serializable
        data class Error(
            @Transient val throwable: Throwable = Throwable(),
            override val action: RestartableAction,
        ) : DownloadState, Restartable

        /** 다운로드가 끝난 출력 파일 목록입니다. */
        @Serializable
        data class Completed(
            val filePath: String?,
            val filePaths: List<String> = listOfNotNull(filePath),
            val missing: Boolean = false,
        ) : DownloadState

        /** 기존 대기열의 실행 우선순위를 유지합니다. */
        override fun compareTo(other: DownloadState): Int = ordinal - other.ordinal

        private val ordinal: Int
            get() = when (this) {
                is Canceled -> 4
                is Error -> 5
                is Completed -> 6
                Idle -> 3
                is FetchingInfo -> 2
                ReadyWithInfo -> 1
                is Running -> 0
            }
    }

    /** 취소·오류 이후 재개할 처리 단계입니다. */
    @Serializable
    sealed interface RestartableAction {
        /** 정보를 다시 조회합니다. */
        @Serializable data object FetchInfo : RestartableAction

        /** 확보한 정보로 다운로드를 다시 시작합니다. */
        @Serializable data object Download : RestartableAction
    }

    /** 플러그인 대기열 카드가 표시하는 메타데이터입니다. */
    @Serializable
    data class ViewState(
        val url: String = "https://www.example.com",
        val title: String = "",
        val uploader: String = "",
        val extractorKey: String = "",
        val duration: Int = 0,
        val fileSizeApprox: Double = .0,
        val thumbnailUrl: String? = null,
        val videoFormats: List<Format>? = null,
        val audioOnlyFormats: List<Format>? = null,
        val isImage: Boolean = false,
        val mediaCount: Int = 1,
        val width: Int = 0,
        val height: Int = 0,
        val imageCount: Int = if (isImage) mediaCount else 0,
        val videoCount: Int = if (isImage) 0 else mediaCount,
        val completedCount: Int = 0,
        val minWidth: Int = width,
        val minHeight: Int = height,
        val averageMbps: Double? = null,
        val originalCodec: String = "",
        val codec: String = "",
        val av1Mode: Int = 0,
    ) {
        companion object {
            /** yt-dlp 결과를 플러그인 대기열 카드 메타데이터로 변환합니다. */
            fun fromVideoInfo(info: VideoInfo): ViewState {
                val formats = info.requestedFormats ?: info.requestedDownloads?.map { it.toFormat() } ?: emptyList()
                return ViewState(
                    url = info.originalUrl.toString(),
                    title = info.title,
                    uploader = info.uploader ?: info.channel ?: info.uploaderId.toString(),
                    extractorKey = info.extractorKey,
                    duration = info.duration?.roundToInt() ?: 0,
                    thumbnailUrl = info.thumbnail.toHttpsUrl(),
                    fileSizeApprox = info.fileSize ?: info.fileSizeApprox ?: .0,
                    videoFormats = formats.filter { it.containsVideo() },
                    audioOnlyFormats = formats.filter { it.isAudioOnly() },
                    isImage = info.ext in IMAGE_EXTENSIONS,
                    width = info.width?.toInt() ?: 0,
                    height = info.height?.toInt() ?: 0,
                )
            }
        }
    }

    companion object {
        private const val PROGRESS_INDETERMINATE = -1f
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif")

        /** 같은 URL·유형·설정 조합을 같은 대기열 요청으로 식별합니다. */
        private fun createId(url: String, type: TypeInfo, preferences: DownloadUtil.DownloadPreferences): String =
            "${url}_${type.id}_${preferences.hashCode()}"

        /** 요청 유형별 구분자를 계산합니다. */
        private val TypeInfo.id: String
            get() = when (this) {
                is TypeInfo.CustomCommand -> "${template.id}_${template.name}"
                is TypeInfo.Playlist -> "$index"
                is TypeInfo.SocialMedia -> media.id
                TypeInfo.URL -> ""
            }
    }
}
