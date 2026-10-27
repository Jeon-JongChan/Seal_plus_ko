package com.plugin.person

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.junkfood.seal.ui.page.downloadv2.configure.DownloadDialogViewModel
import com.plugin.FeaturePlugin
import com.plugin.PluginId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 예시 사진을 이용한 인물 폴더 분류를 공통 플러그인 탐색에 연결합니다. */
object PersonPlugin : FeaturePlugin() {
    override val id = PluginId.PERSON_AI
    override val route = "person_comparison"
    override val titleResource = R.string.plugin_person_ai
    override val icon = Icons.Outlined.Face

    /** 원본 화면을 수정하지 않고 앱의 공통 플러그인 탐색에 참여합니다. */
    @Composable
    override fun RenderContent(onBack: () -> Unit, onAction: (DownloadDialogViewModel.Action) -> Unit) {
        PersonSortPage(onBack)
    }
}

/** 영구 캐시의 얼굴 썸네일은 보이는 항목만 원본에서 복원합니다. */
@Composable
internal fun FacePreview(face: PersonSample, description: String) {
    val context = LocalContext.current.applicationContext
    // 페이지 재조회로 객체가 바뀌어도 같은 원본·트랙·표본의 썸네일은 다시 디코딩하지 않습니다.
    val preview by produceState<android.graphics.Bitmap?>(face.preview,face.sourceUri,face.fingerprint,face.trackId,face.representativeMs,face.landmarks.contentHashCode()) {
        if (value == null) value = withContext(Dispatchers.IO) {
            try { PersonAnalyzer.preview(context,face) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }
    val image = preview
    if (image != null) Image(image.asImageBitmap(),description,Modifier.size(80.dp))
    else Icon(Icons.Outlined.Face,description,Modifier.size(80.dp))
}
