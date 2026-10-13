package com.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.plugin.image.ImageDownloadPlugin
import com.plugin.listdownload.ListDownloadPlugin
import com.plugin.media.MediaSettingsPlugin
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel

/** 앱에 함께 빌드되는 기능 플러그인의 메뉴와 화면 진입점입니다. */
interface FeaturePlugin {
    val id: PluginId
    val route: String
    val titleResource: Int
    val icon: ImageVector

    /** 공통 탐색·다운로드 동작은 호스트 앱의 콜백으로 전달합니다. */
    @Composable
    fun Content(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit)

    /** 저장된 이미지를 처리하는 플러그인만 뷰어의 공통 동작에 참여합니다. */
    @Composable
    fun ImageAction(path: String, onSaved: (String, Boolean) -> Unit) {}

    /** 게시글 이미지 전체 동작은 저장 원본 경로도 전달해 각 페이지를 정확히 갱신합니다. */
    @Composable
    fun PostImageAction(paths: List<String>, onSaved: (String, String, Boolean) -> Unit) {}
}

// 플러그인을 이 목록에 등록하면 메뉴와 화면 경로에 함께 연결됩니다.
val featurePlugins: List<FeaturePlugin> = listOf(ImageDownloadPlugin, ListDownloadPlugin, MediaSettingsPlugin,
    com.plugin.library.LibraryPlugin, com.plugin.interaction.InteractionPlugin,
    com.plugin.enhancement.ImageEnhancementPlugin,
    com.plugin.instagram.FrequentAccountsPlugin,
    com.plugin.instagram.PostInfoPlugin, com.plugin.folders.DownloadFoldersPlugin,
    com.plugin.faces.FaceSortPlugin)
