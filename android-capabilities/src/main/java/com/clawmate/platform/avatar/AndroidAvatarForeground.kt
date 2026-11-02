package com.clawmate.platform.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.clawmate.platform.LocalImageAccess
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.net.URI
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class AvatarForegroundRgba(val width: Int, val height: Int, val pixels: ByteArray)

/** Local inference/decode only. Shared ArkTS owns crop, PNG, thumbnail and persistence. */
class AndroidAvatarForeground(private val context: Context) {
    fun prepare(uri: String, segment: Boolean): AvatarForegroundRgba {
        val checked = URI(LocalImageAccess.validate(uri, listOf(context.filesDir, context.cacheDir)))
        require(checked.scheme == "file") { "分身分割只接受已保存的私有图片" }
        val file = File(checked)
        require(file.length() in 1..16_777_216) { "分身图片为空或超过16MB，请重新生成" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "分身图片尺寸无效" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("无法解码分身图片")
        val owned = linkedSetOf(decoded)
        var pending: com.google.android.gms.tasks.Task<SegmentationMask>? = null
        var releaseEngine: (() -> Unit)? = null
        try {
            val largest = maxOf(decoded.width, decoded.height)
            val bitmap = if (largest <= 512) decoded else Bitmap.createScaledBitmap(decoded,
                (decoded.width * 512 / largest).coerceAtLeast(1), (decoded.height * 512 / largest).coerceAtLeast(1), true).also { owned += it }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val confidence = if (segment) {
                val engine = Segmentation.getClient(SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
                releaseEngine = { engine.close() }
                val task = engine.process(InputImage.fromBitmap(bitmap, 0))
                pending = task
                val mask = Tasks.await(task, 30, TimeUnit.SECONDS)
                require(mask.width == bitmap.width && mask.height == bitmap.height) { "分割蒙版尺寸不匹配" }
                FloatArray(mask.width * mask.height).also { mask.buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(it) }
            } else null // Backfill thumbnail from an already transparent cached foreground.
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val argb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return AvatarForegroundRgba(bitmap.width, bitmap.height, ForegroundPixels.rgba(argb, confidence))
        } finally {
            val cleanup = { releaseEngine?.invoke(); owned.forEach { it.recycle() } }
            // ML Kit work cannot be cancelled. Keep its input alive after timeout/interruption;
            // NativeOperations discards any late result and the task frees its resources on completion.
            val task = pending
            if (task != null && !task.isComplete) task.addOnCompleteListener { cleanup() } else cleanup()
        }
    }
}
