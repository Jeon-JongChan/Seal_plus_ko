package com.plugin.queue

/** 전용 대기열 화면의 조작 요청입니다. */
sealed interface UiAction {
    /** 완료 파일을 엽니다. */
    data class OpenFile(val filePath: String?) : UiAction

    /** 완료 파일을 공유합니다. */
    data class ShareFile(val filePath: String?) : UiAction

    /** 썸네일 주소를 엽니다. */
    data class OpenThumbnailURL(val url: String) : UiAction

    /** 다운로드 주소를 복사합니다. */
    data object CopyVideoURL : UiAction

    /** 영상 주소를 엽니다. */
    data class OpenVideoURL(val url: String) : UiAction

    /** 실행 중인 작업을 취소합니다. */
    data object Cancel : UiAction

    /** 대기열의 작업을 제거합니다. */
    data object Delete : UiAction

    /** 완료 파일 삭제를 전용 화면에서 처리합니다. */
    data object DeleteFiles : UiAction

    /** 중단된 작업을 재개합니다. */
    data object Resume : UiAction

    /** 오류 내용을 복사합니다. */
    data class CopyErrorReport(val throwable: Throwable) : UiAction

    /** 배경화면 설정 대화상자를 엽니다. */
    data class OpenWallpaper(val url: String = "", val filePath: String? = null) : UiAction

    /** 인스타그램 링크를 앱 또는 웹으로 엽니다. */
    data class OpenInstagram(val url: String) : UiAction
}

