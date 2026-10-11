package com.junkfood.seal.database.objects

import androidx.room.Ignore
import com.plugin.compatibility.MediaRules
import kotlinx.serialization.Serializable

/** 실제 영상 파일에서 읽어 저장하는 화질 정보이며 코덱은 MIME 이름입니다. */
@Serializable
data class VideoQuality(
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Double = 0.0,
    val duration: Double = 0.0,
    val bytes: Long = 0,
    val codec: String = "",
) {
    /** 음원을 포함한 파일 전체의 평균 비트레이트이며 영상 트랙 비트레이트와 구분합니다. */
    @get:Ignore
    val averageMbps: Double? get() = MediaRules.bitrateMbps(bytes, duration)
}
