package com.plugin.compatibility

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 화면 크기로 제한한 이미지·영상 첫 프레임과 인스타그램 실행을 제공합니다. */
object WallpaperHelper {
    /** 홈 화면 대상입니다. */
    const val TARGET_HOME = 0
    /** 잠금 화면 대상입니다. */
    const val TARGET_LOCK = 1
    /** 홈·잠금 화면 동시 대상입니다. */
    const val TARGET_BOTH = 2

    /** 실제 배경화면은 미리보기와 같은 중앙 채우기 이미지로 적용합니다. */
    suspend fun setWallpaper(context: Context, bitmap: Bitmap, target: Int): Boolean = withContext(Dispatchers.IO) {
        require(target in TARGET_HOME..TARGET_BOTH)
        try {
            val manager = WallpaperManager.getInstance(context)
            if (!manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) return@withContext false
            val flag = when (target) {
                TARGET_HOME -> WallpaperManager.FLAG_SYSTEM
                TARGET_LOCK -> WallpaperManager.FLAG_LOCK
                else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
            manager.setBitmap(bitmap, null, true, flag)
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
    }

    /** 원본·Coil 캐시 비트맵을 변경하지 않고 화면 크기의 소유 가능한 사본을 반환합니다. */
    suspend fun loadBitmap(context: Context, pathOrUrl: String, requestedWidth: Int = context.resources.displayMetrics.widthPixels, requestedHeight: Int = context.resources.displayMetrics.heightPixels): Bitmap? = withContext(Dispatchers.IO) {
        val width = requestedWidth.coerceIn(1, 2048)
        val height = requestedHeight.coerceIn(1, 2048)
        var owned: Bitmap? = null
        try {
            val video = if (pathOrUrl.startsWith("content://")) context.contentResolver.getType(Uri.parse(pathOrUrl))?.startsWith("video/") == true
                else MediaRules.mediaKind(pathOrUrl.substringBefore('?')) == 1 && !pathOrUrl.startsWith("http")
            val source = if (video) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    val uri = if (pathOrUrl.startsWith("content://") || pathOrUrl.startsWith("file://")) Uri.parse(pathOrUrl) else Uri.fromFile(File(pathOrUrl))
                    retriever.setDataSource(context, uri)
                    owned = if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
                        else {
                            // 구형 API는 축소 디코딩이 없어 큰 영상의 원본 프레임을 읽지 않습니다.
                            val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0
                            val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0
                            if (w * h !in 1..4_194_304L) return@withContext null
                            retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    owned
                } finally { retriever.release() }
            } else {
                val request = coil.request.ImageRequest.Builder(context).data(pathOrUrl).allowHardware(false)
                    .size(width, height).scale(coil.size.Scale.FILL).build()
                val drawable = (coil.Coil.imageLoader(context).execute(request) as? coil.request.SuccessResult)?.drawable
                (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            } ?: return@withContext null
            centerCrop(source, width, height)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
        catch (_: OutOfMemoryError) { null }
        finally { owned?.recycle() }
    }

    /** 미리보기의 중앙 채우기 비율을 적용하고 공유 캐시와 별개의 비트맵을 만듭니다. */
    internal fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        require(width in 1..2048 && height in 1..2048)
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val left = (width - source.width * scale) / 2
        val top = (height - source.height * scale) / 2
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(source, null, RectF(left, top, left + source.width * scale, top + source.height * scale), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    /** 다른 앱 스킴·유사 도메인을 실행하지 않도록 인스타그램 HTTP 주소만 허용합니다. */
    fun isInstagramUrl(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme in listOf("https", "http") && uri.host?.lowercase()?.removePrefix("www.") in listOf("instagram.com", "m.instagram.com")
    }.getOrDefault(false)

    /** 공식 앱을 먼저 실행하고 없으면 같은 게시글을 브라우저로 엽니다. */
    fun openInstagramUrl(context: Context, url: String) {
        if (!isInstagramUrl(url)) return
        val uri = Uri.parse(url)
        try { context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.instagram.android").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        catch (_: SecurityException) { }
    }
}
