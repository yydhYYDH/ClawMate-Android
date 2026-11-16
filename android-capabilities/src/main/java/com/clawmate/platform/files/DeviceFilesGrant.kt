package com.clawmate.platform.files

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/** 用户授权给 Mika 读取的目录（SAF tree URI），只保存在本机。 */
object DeviceFilesGrant {
    private const val PREFS = "clawmate-device-files"
    private const val KEY = "tree"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): Uri? = prefs(context).getString(KEY, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun write(context: Context, uri: Uri?) {
        prefs(context).edit().apply { if (uri == null) remove(KEY) else putString(KEY, uri.toString()) }.apply()
    }
}
