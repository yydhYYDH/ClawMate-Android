package com.clawmate.platform.mika

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.wangzan101.mika.core.vision.ImageOps
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [ImageOps] 的 Android 实现：截图、缩图、交给视觉模型前的压缩、框坐标与亮度判断。
 *
 * 只做字节进字节出的处理，不持有 Context，也不读写文件；视觉模型与坐标定位共用同一份实现。
 * 框坐标与 Mika 读屏元素同一口径（0–999 归一化，[l, t, r, b]）。
 */
internal object AndroidImageOps : ImageOps {
    override fun size(img: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(img, 0, img.size, bounds)
        return bounds.outWidth.coerceAtLeast(0) to bounds.outHeight.coerceAtLeast(0)
    }

    override fun aHash(img: ByteArray): Long {
        val small = decode(img) { bitmap ->
            Bitmap.createScaledBitmap(bitmap, 8, 8, true).also { if (it !== bitmap) bitmap.recycle() }
        } ?: return 0L
        val pixels = IntArray(64)
        small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        small.recycle()
        var sum = 0L
        val gray = IntArray(64) { index ->
            val color = pixels[index]
            (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
        }
        gray.forEach { sum += it }
        val mean = sum / 64.0
        var hash = 0L
        gray.forEachIndexed { index, value -> if (value >= mean) hash = hash or (1L shl index) }
        return hash
    }

    override fun toJpeg(img: ByteArray, maxSide: Int, quality: Int): ByteArray {
        val decoded = decode(img, maxSide) { bitmap ->
            val side = max(bitmap.width, bitmap.height)
            if (side <= maxSide) {
                bitmap
            } else {
                val factor = maxSide.toFloat() / side
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).roundToInt().coerceAtLeast(1),
                    (bitmap.height * factor).roundToInt().coerceAtLeast(1), true)
                    .also { if (it !== bitmap) bitmap.recycle() }
            }
        } ?: return img
        return encode(decoded, Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100)) ?: img
    }

    override fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int, strokePx: Int): ByteArray {
        if (box.size != 4) return img
        val decoded = decode(img) { bitmap ->
            val rect = Rect(
                toPixel(box[0], bitmap.width) - expandPx,
                toPixel(box[1], bitmap.height) - expandPx,
                toPixel(box[2], bitmap.width) + expandPx,
                toPixel(box[3], bitmap.height) + expandPx,
            )
            Canvas(bitmap).drawRect(rect, Paint().apply {
                style = Paint.Style.STROKE
                color = Color.RED
                strokeWidth = strokePx.toFloat().coerceAtLeast(1f)
                isAntiAlias = true
            })
            bitmap
        } ?: return img
        return encode(decoded, Bitmap.CompressFormat.PNG, 100) ?: img
    }

    override fun crop(img: ByteArray, box: IntArray): ByteArray {
        if (box.size != 4) return img
        val decoded = decode(img) { bitmap ->
            val rect = Rect(
                toPixel(box[0], bitmap.width),
                toPixel(box[1], bitmap.height),
                toPixel(box[2], bitmap.width),
                toPixel(box[3], bitmap.height),
            )
            if (rect.width() <= 0 || rect.height() <= 0) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
                    .also { if (it !== bitmap) bitmap.recycle() }
            }
        } ?: return img
        return encode(decoded, Bitmap.CompressFormat.PNG, 100) ?: img
    }

    override fun scale(img: ByteArray, factor: Double): ByteArray {
        if (factor <= 0 || factor == 1.0) return img
        val decoded = decode(img) { bitmap ->
            Bitmap.createScaledBitmap(bitmap,
                (bitmap.width * factor).roundToInt().coerceAtLeast(1),
                (bitmap.height * factor).roundToInt().coerceAtLeast(1), true)
                .also { if (it !== bitmap) bitmap.recycle() }
        } ?: return img
        return encode(decoded, Bitmap.CompressFormat.PNG, 100) ?: img
    }

    override fun pixels(img: ByteArray): IntArray {
        val decoded = decode(img) ?: return IntArray(0)
        val result = IntArray(decoded.width * decoded.height)
        decoded.getPixels(result, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        decoded.recycle()
        return result
    }

    override fun isMostlyBlack(img: ByteArray): Boolean {
        val decoded = decode(img) ?: return false
        val stepX = max(1, decoded.width / 24)
        val stepY = max(1, decoded.height / 24)
        var dark = 0
        var total = 0
        var y = 0
        val row = IntArray(1)
        while (y < decoded.height) {
            var x = 0
            while (x < decoded.width) {
                decoded.getPixels(row, 0, 1, x, y, 1, 1)
                val color = row[0]
                val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                if (luminance < 16) dark++
                total++
                x += stepX
            }
            y += stepY
        }
        decoded.recycle()
        return total > 0 && dark * 100 / total >= 95
    }

    /** 解码并按需降采样；[transform] 返回的位图由调用方负责回收。解码失败时为 null。 */
    private inline fun decode(img: ByteArray, maxSide: Int = Int.MAX_VALUE, transform: (Bitmap) -> Bitmap = { it }): Bitmap? {
        if (img.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(img, 0, img.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        if (maxSide != Int.MAX_VALUE) {
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(img, 0, img.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true }) ?: return null
        return transform(decoded)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray? = try {
        ByteArrayOutputStream().use { out ->
            if (bitmap.compress(format, quality, out)) out.toByteArray() else null
        }
    } finally {
        bitmap.recycle()
    }

    private fun toPixel(normalized: Int, size: Int): Int = (normalized.coerceIn(0, 999) * (size - 1) / 999.0).roundToInt()
}
