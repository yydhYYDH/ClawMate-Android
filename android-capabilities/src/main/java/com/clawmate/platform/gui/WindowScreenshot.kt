package com.clawmate.platform.gui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class WindowScreenshotResult(val dataUrl: String, val width: Int, val height: Int)

/** Own activity pixels only: no display projection, shell, other apps or disk writes. */
class WindowScreenshot(private val activity: Activity, private val allowed: () -> Boolean) {
    fun capture(): WindowScreenshotResult {
        check(Looper.myLooper() != Looper.getMainLooper())
        val latch = CountDownLatch(1)
        val discarded = AtomicBoolean(false)
        var bitmap: Bitmap? = null
        var status = PixelCopy.ERROR_UNKNOWN
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            if (discarded.get()) return@post
            try {
                check(allowed() && !activity.isFinishing && !activity.isDestroyed)
                check(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
                val decor = activity.window.decorView
                check(decor.hasWindowFocus() && decor.width > 0 && decor.height > 0)
                // ArkUI-X renders through a SurfaceView; copying only decor loses
                // that layer. Never substitute another window/display on failure.
                fun surfaces(view: View): List<SurfaceView> = when(view) {
                    is SurfaceView -> if(view.isShown && view.holder.surface.isValid) listOf(view) else emptyList()
                    is ViewGroup -> (0 until view.childCount).flatMap { surfaces(view.getChildAt(it)) }
                    else -> emptyList()
                }
                val surface = surfaces(decor).maxByOrNull { it.width.toLong()*it.height }
                val width = surface?.width ?: decor.width
                val height = surface?.height ?: decor.height
                check(width in 1..10000 && height in 1..10000)
                val scale = minOf(1.0, 1280.0/maxOf(width,height))
                val target = Bitmap.createBitmap(maxOf(1,(width*scale).toInt()),maxOf(1,(height*scale).toInt()),Bitmap.Config.ARGB_8888)
                bitmap = target
                val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
                    status = result
                    if (discarded.get()) target.recycle()
                    latch.countDown()
                }
                if(surface != null) PixelCopy.request(surface,target,listener,handler)
                else PixelCopy.request(activity.window,target,listener,handler)
            } catch (_: Exception) { latch.countDown() }
        }
        try {
            check(latch.await(5,TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) { "无法截取当前应用界面，请返回前台重试" }
            check(allowed()) { "页面已关闭，截图已丢弃" }
            val image = checkNotNull(bitmap)
            val bytes = ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.JPEG,80,it)) }.toByteArray()
            check(bytes.size in 1..1000000) { "截图超出大小限制" }
            return WindowScreenshotResult("data:image/jpeg;base64,"+java.util.Base64.getEncoder().encodeToString(bytes),image.width,image.height)
        } finally {
            discarded.set(true)
            if(latch.count == 0L) bitmap?.let { if(!it.isRecycled) it.recycle() }
        }
    }
}
