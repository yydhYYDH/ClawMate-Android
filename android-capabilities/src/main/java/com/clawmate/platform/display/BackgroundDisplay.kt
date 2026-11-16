package com.clawmate.platform.display

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.view.Surface
import java.io.Closeable

/** A display belongs to one approved launch and one app process. */
class BackgroundDisplay private constructor(val id: Int, private val host: IDisplayHost) : Closeable {
    val connected: Boolean get() = host.asBinder().isBinderAlive

    internal fun showOn(surface: Surface?) {
        runCatching { host.setPreviewSurface(id, surface) }
    }

    override fun close() {
        runCatching { host.close(id) }
    }

    companion object {
        @Volatile private var host: IDisplayHost? = null
        private val lifetime = Binder()
        val available: Boolean get() = host?.asBinder()?.isBinderAlive == true

        @Synchronized
        internal fun attach(remote: IBinder): IBinder {
            check(!available) { "后台屏助手已连接" }
            val proxy = IDisplayHost.Stub.asInterface(remote)
            remote.linkToDeath({
                synchronized(this) { if (host?.asBinder() === remote) host = null }
            }, 0)
            host = proxy
            return lifetime
        }

        /** Call after the user approves this exact launch, off the UI thread. */
        fun open(approved: Intent): BackgroundDisplay {
            val service = checkNotNull(host?.takeIf { it.asBinder().isBinderAlive }) { "请先连接 ClawMate 后台屏助手" }
            val id = service.open(approved)
            check(id > 0) { "后台屏创建失败" }
            return BackgroundDisplay(id, service)
        }
    }
}

/** Entry for an explicitly authorized adb shell helper on this device. */
class BackgroundDisplayProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000 && method == "attach") { "仅 Android 调试 shell 可以注册后台屏助手" }
        val remote = requireNotNull(extras?.getBinder("host"))
        return Bundle().apply { putBinder("lifetime", BackgroundDisplay.attach(remote)) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
