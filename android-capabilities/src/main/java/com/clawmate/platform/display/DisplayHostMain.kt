package com.clawmate.platform.display

import android.content.AttributionSource
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** app_process entry for a user-authorized adb shell. Android still checks display/launch permissions. */
object DisplayHostMain {
    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"

    @JvmStatic
    fun main(args: Array<String>) {
        check(Process.myUid() == SHELL_UID && Build.VERSION.SDK_INT >= 34)
        val pkg = args.single()
        Looper.prepareMainLooper()
        val thread = Class.forName("android.app.ActivityThread")
        val instance = thread.getMethod("systemMain").invoke(null)
        val system = thread.getDeclaredMethod("getSystemContext").invoke(instance) as Context
        val shell = system.createPackageContext(SHELL_PACKAGE, 0)
        val uid = shell.packageManager.getPackageUid(pkg, 0)
        val handler = Handler(Looper.myLooper()!!)
        val host = DisplayHost(shell, uid, handler)
        val lifetime = handOff(pkg, uid, host)
        lifetime.linkToDeath({ handler.post { host.release(); exitProcess(0) } }, 0)
        println("ClawMate background display host connected")
        Looper.loop()
    }

    // Like Android's content shell client, use an external provider handle from the shell process.
    private fun handOff(pkg: String, uid: Int, host: IBinder): IBinder {
        val authority = "$pkg.background-display"
        val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = requireNotNull(manager.javaClass.getMethod("getContentProviderExternal",
            String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(manager, authority, 0, token, "ClawMateDisplay"))
        try {
            val info = holder.javaClass.getField("info").get(holder) as ProviderInfo
            check(info.packageName == pkg && info.applicationInfo.uid == uid &&
                info.applicationInfo.sourceDir == System.getProperty("java.class.path")) { "后台屏助手 APK 与目标应用不匹配" }
            val provider = holder.javaClass.getField("provider").get(holder)
            val call = provider.javaClass.getMethod("call", AttributionSource::class.java,
                String::class.java, String::class.java, String::class.java, Bundle::class.java)
            val caller = AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
            val reply = call.invoke(provider, caller, authority, "attach", null, Bundle().apply { putBinder("host", host) }) as Bundle
            return requireNotNull(reply.getBinder("lifetime"))
        } finally {
            manager.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java,
                IBinder::class.java, Int::class.javaPrimitiveType).invoke(manager, authority, token, 0)
        }
    }
}

private class DisplayHost(
    private val shell: Context, private val allowedUid: Int, private val handler: Handler,
) : IDisplayHost.Stub() {
    private var display: VirtualDisplay? = null
    private var frames: ImageReader? = null
    private val expiry = Runnable { release() }

    @Synchronized
    override fun open(target: Intent): Int {
        enforceOwner()
        check(display == null) { "已有后台屏会话，请先停止" }
        val pkg = requireNotNull(target.component?.packageName ?: target.`package`)
        val data = target.data
        require((target.action == Intent.ACTION_MAIN && data == null) ||
            (target.action == Intent.ACTION_VIEW && data?.isHierarchical == true &&
                data.scheme !in setOf(null, "content", "file", "intent") && data.toString().length <= 2048)) {
            "后台屏不支持该启动方式"
        }
        val launch = Intent(target.action, data).setPackage(pkg)
        if (target.action == Intent.ACTION_MAIN) launch.addCategory(Intent.CATEGORY_LAUNCHER)
        target.component?.let { launch.component = it }
        val resolved = checkNotNull(launch.resolveActivity(shell.packageManager)) { "目标应用没有可用入口" }
        check(resolved.packageName == pkg)

        // These AOSP flags require the shell's trusted-display permission.
        val trusted = 1 shl 10
        val destroyOnRemoval = 1 shl 8
        val ownFocus = 1 shl 14
        val preventFocusSteal = 1 shl 16
        val manager = shell.getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { manager.getDisplay(0).getRealMetrics(it) }
        val buffer = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        frames = buffer
        buffer.setOnImageAvailableListener({ reader -> runCatching { reader.acquireLatestImage()?.close() } }, handler)
        try {
            val surface = checkNotNull(manager.createVirtualDisplay("ClawMate background", metrics.widthPixels, metrics.heightPixels,
                metrics.densityDpi, buffer.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    trusted or destroyOnRemoval or ownFocus or preventFocusSteal))
            display = surface
            handler.postDelayed(expiry, 125_000)
            val id = surface.display.displayId
            val command = mutableListOf("/system/bin/am", "start", "--display", id.toString(),
                "-a", launch.action!!, "-n", resolved.flattenToString(),
                "-f", (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK).toString())
            data?.let { command.addAll(listOf("-d", it.toString())) }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            try {
                check(process.waitFor(6, TimeUnit.SECONDS)) { "后台屏启动应用超时" }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                check(process.exitValue() == 0 && "Error" !in output && "Warning:" !in output) { "系统拒绝在后台屏启动目标应用" }
            } finally { process.destroy() }
            return id
        } catch (failure: Exception) {
            release()
            throw failure
        }
    }

    @Synchronized
    override fun close(displayId: Int) {
        enforceOwner()
        if (display?.display?.displayId == displayId) release()
    }

    private fun enforceOwner() {
        check(Binder.getCallingUid() == allowedUid) { "后台屏会话不属于调用方" }
    }

    @Synchronized
    fun release() {
        handler.removeCallbacks(expiry)
        display?.release()
        display = null
        frames?.close()
        frames = null
    }
}
