package com.plugin.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.InputStream

/** 파일을 변경하지 않고 방향·비율을 보존한 소프트웨어 비트맵만 만듭니다. */
object FaceImages {
    /** SAF와 일반 파일을 같은 읽기 경로로 연결합니다. */
    private fun open(context: Context, path: String): InputStream =
        if (path.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(path))
            ?: error("이미지를 열 수 없습니다.") else File(path).inputStream()

    /** 전체 프레임을 분석하지 않는 애니메이션은 얼굴 없음으로 오판해 이동하지 않습니다. */
    fun decode(context: Context, path: String, maximum: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = if (path.startsWith("content://")) ImageDecoder.createSource(context.contentResolver, Uri.parse(path))
                else ImageDecoder.createSource(File(path))
            val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                val (width, height) = FaceRules.analysisSize(info.size.width, info.size.height, maximum)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(width, height)
                decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            }
            check(drawable is BitmapDrawable) { "움직이는 이미지는 자동 이동에서 제외합니다." }
            return drawable.bitmap
        }
        check(!path.endsWith(".gif", true)) { "이 Android 버전에서는 GIF 자동 이동을 지원하지 않습니다." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, path).use { BitmapFactory.decodeStream(it, null, bounds) }
        val (width, height) = FaceRules.analysisSize(bounds.outWidth, bounds.outHeight, maximum)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val decoded = open(context, path).use { BitmapFactory.decodeStream(it, null,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
            ?: error("이미지를 읽을 수 없습니다.")
        val resized = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (resized !== decoded) decoded.recycle()
        val orientation = runCatching { open(context, path).use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        if (transform.isIdentity) return resized
        return Bitmap.createBitmap(resized, 0, 0, resized.width, resized.height, transform, true).also {
            if (it !== resized) resized.recycle()
        }
    }
}
