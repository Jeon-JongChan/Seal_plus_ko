package com.junkfood.seal.ui.page.downloadv2.configure

import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.Action
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel.SheetState
import com.junkfood.seal.util.DownloadUtil
import com.plugin.compatibility.InstagramMedia

/**
 * 플러그인 전용 다이얼로그 상태 및 액션 정의입니다.
 * 원본 DownloadDialogViewModel.kt를 수정하지 않고 확장 시트 상태와 소셜 액션을 제공합니다.
 */

/** 인스타그램/소셜 미디어 선택 시트 상태 */
data class InstagramSelectionState(
    val items: List<InstagramMedia>,
    val preferences: DownloadUtil.DownloadPreferences,
    val accountList: Boolean = false,
)

/** 호환성을 위한 타입 별칭 */
typealias InstagramSelection = InstagramSelectionState
typealias InspectInstagram = InspectInstagramAction
typealias DownloadInstagram = DownloadInstagramAction

/** 인스타그램/소셜 미디어 분석 액션 */
data class InspectInstagramAction(
    val url: String,
    val preferences: DownloadUtil.DownloadPreferences,
    val urls: List<String> = listOf(url),
)

/** 인스타그램/소셜 미디어 다운로드 액션 */
data class DownloadInstagramAction(
    val items: List<InstagramMedia>,
    val preferences: DownloadUtil.DownloadPreferences,
)

