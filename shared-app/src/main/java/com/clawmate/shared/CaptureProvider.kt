package com.clawmate.shared

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Grants the selected system camera one temporary file, never the app files tree. */
class CaptureProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment.orEmpty()
        require(uri.pathSegments.size == 1 && Regex("[0-9a-f-]{36}\\.jpg").matches(name))
        val root = File(requireNotNull(context).cacheDir, "chat-camera").canonicalFile
        return File(root, name).canonicalFile.also { require(it.parentFile == root && it.isFile) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
        val file = file(uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns.toTypedArray()).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else file.name }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unsupported")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}
