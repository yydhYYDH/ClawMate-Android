package com.clawmate.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.clawmate.app.chat.cloud.ChatImageBounds
import com.clawmate.app.gallery.ocr.OcrBitmapDecode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PreparedChatImage(val dataUrl: String, val width: Int, val height: Int, val byteCount: Int) {
    override fun toString(): String = "PreparedChatImage(${width}x$height, bytes=$byteCount)"
}

/** Explicit content-grant/private-file decode, orientation correction, white flattening and metadata-free JPEG. */
class AndroidChatImagePreparer(context: Context) {
    private val context = context.applicationContext

    suspend fun prepare(uriText: String): PreparedChatImage = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val uri = Uri.parse(uriText)
        require(uri.scheme == "content" || uri.scheme == "file") { "请通过系统选图器选择本机图片" }
        if (uri.scheme == "file") {
            val path = File(uri.path ?: "").canonicalFile.toPath()
            require(listOf(context.filesDir, context.cacheDir).any { path.startsWith(it.canonicalFile.toPath()) }) {
                "文件图片必须位于应用自己的存储目录，请重新选择图片"
            }
        }
        fun open() = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取图片，请重新选择并授权")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000L) {
            "图片无效或像素过大，请选择较小图片"
        }
        val sample = OcrBitmapDecode.sampleFactor(bounds.outWidth, bounds.outHeight, ChatImageBounds.MAX_DIMENSION)
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val owned = linkedSetOf<Bitmap>()
        try {
            val decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: throw IOException("无法解码图片")
            owned += decoded
            currentCoroutineContext().ensureActive()
            val orientation = runCatching { open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
                .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val transform = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            val oriented = if (transform.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also { owned += it }
            val largest = maxOf(oriented.width, oriented.height)
            val sized = if (largest <= ChatImageBounds.MAX_DIMENSION) oriented else Bitmap.createScaledBitmap(oriented,
                (oriented.width.toLong() * ChatImageBounds.MAX_DIMENSION / largest).toInt().coerceAtLeast(1),
                (oriented.height.toLong() * ChatImageBounds.MAX_DIMENSION / largest).toInt().coerceAtLeast(1), true).also { owned += it }
            val flat = Bitmap.createBitmap(sized.width, sized.height, Bitmap.Config.ARGB_8888).also { owned += it }
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(sized, 0f, 0f, null) }
            var quality = 85
            var encoded: ByteArray
            do {
                currentCoroutineContext().ensureActive()
                encoded = ByteArrayOutputStream().use { output ->
                    if (!flat.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw IOException("图片压缩失败")
                    output.toByteArray()
                }
                quality -= 10
            } while (encoded.size > ChatImageBounds.MAX_IMAGE_BYTES && quality >= 35)
            require(encoded.size <= ChatImageBounds.MAX_IMAGE_BYTES) { "压缩后的图片仍过大，请选择较小图片" }
            currentCoroutineContext().ensureActive()
            PreparedChatImage("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(encoded), flat.width, flat.height, encoded.size)
        } finally {
            owned.forEach { if (!it.isRecycled) it.recycle() }
        }
    }
}
