package com.plugin

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.plugin.image.ImageDownloadPlugin
import com.plugin.listdownload.ListDownloadPlugin
import com.plugin.media.MediaSettingsPlugin
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel

/** 앱에 함께 빌드되는 기능 플러그인의 메뉴와 화면 진입점입니다. */
abstract class FeaturePlugin {
    abstract val id: PluginId
    abstract val route: String
    abstract val titleResource: Int
    abstract val icon: ImageVector

    /** 공통 탐색·다운로드 동작은 호스트 앱의 콜백으로 전달합니다. */
    @Composable
    fun Content(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        // 비활성 상태의 오래된 탐색 경로는 기능 실행을 시작하지 않습니다.
        if (PluginState.isEnabled(id)) RenderContent(onBack, onAction)
    }

    /** 플러그인은 고정된 공통 진입 절차 뒤에 자체 화면·전략만 제공합니다. */
    @Composable
    protected abstract fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit)

    /** 저장된 이미지를 처리하는 플러그인만 뷰어의 공통 동작에 참여합니다. */
    @Composable
    open fun ImageAction(path: String, onSaved: (String, Boolean) -> Unit) {}

    /** 게시글 이미지 전체 동작은 저장 원본 경로도 전달해 각 페이지를 정확히 갱신합니다. */
    @Composable
    open fun PostImageAction(paths: List<String>, onSaved: (String, String, Boolean) -> Unit) {}

    /** 영상 전략은 이미지 플러그인을 켜지 않고도 공통 뷰어에서 실행할 수 있습니다. */
    @Composable
    open fun VideoAction(path: String, onSaved: (String, Boolean) -> Unit) {}
}

// 플러그인을 이 목록에 등록하면 메뉴와 화면 경로에 함께 연결됩니다.
val featurePlugins: List<FeaturePlugin> get() = PluginFactory.all

/** 등록·조회·활성 전략 선택을 담당하며 플러그인 인스턴스는 재생성하지 않습니다. */
object PluginFactory {
    val all: List<FeaturePlugin> by lazy { listOf(ImageDownloadPlugin, ListDownloadPlugin, MediaSettingsPlugin,
    com.plugin.library.LibraryPlugin, com.plugin.interaction.InteractionPlugin,
    com.plugin.enhancement.ImageEnhancementPlugin,
    com.plugin.videoenhancement.VideoEnhancementPlugin,
    com.plugin.instagram.FrequentAccountsPlugin,
    com.plugin.instagram.PostInfoPlugin, com.plugin.folders.DownloadFoldersPlugin,
    com.plugin.faces.FaceSortPlugin, com.plugin.sharemenu.ShareMenuPlugin) }

    /** 설정 화면도 같은 등록 목록에서 기능의 파사드를 찾습니다. */
    fun create(id: PluginId): FeaturePlugin? = all.firstOrNull { it.id == id }

    /** 메뉴와 공통 작업 연결은 활성 플러그인의 전략만 사용합니다. */
    fun enabled(): List<FeaturePlugin> = all.filter { PluginState.isEnabled(it.id) }
}
