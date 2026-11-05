package com.clawmate.shared

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Grants one delivered Codex file to an external viewer or to the document picker.
 *
 * Follows the same least-privilege rule as [CaptureProvider]: only files under the Codex
 * session directory are reachable, the name must not contain separators, and the resolved
 * location is re-checked so a crafted path cannot escape the root.
 *
 * URI shape: `content://<applicationId>.codex/<sessionDigest>/<artifactId>/<fileName>`
 */
class CodexArtifactProvider : ContentProvider() {

    override fun onCreate() = true

    private fun root(): File = File(requireNotNull(context).filesDir, "codex/sessions").canonicalFile

    private fun file(uri: Uri): File {
        val segments = uri.pathSegments
        require(segments.size == 3) { "Unsupported artifact path" }
        val (session, artifact, name) = segments
        require(SESSION.matches(session) && ARTIFACT.matches(artifact)) { "Unsupported artifact id" }
        require(NAME.matches(name) && name != "." && name != "..") { "Unsupported file name" }
        val root = root()
        val target = File(File(File(root, session), artifact), name).canonicalFile
        require(target.isFile && target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            "Artifact outside the delivery root"
        }
        return target
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Delivered files are read-only" }
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = when (file(uri).name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "txt", "md" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        args: Array<out String>?,
        order: String?,
    ): Cursor {
        val file = file(uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
            ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns.toTypedArray()).apply {
            addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else file.name })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unsupported")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    companion object {
        private val SESSION = Regex("^[0-9a-f]{64}$")
        private val ARTIFACT = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val NAME = Regex("^[^/\\\\:*?\"<>|\\x00-\\x1f]{1,240}$")

        /** Builds the content URI for a delivered file, or `null` when it is not deliverable. */
        fun uriFor(context: android.content.Context, path: String): Uri? {
            val root = File(context.filesDir, "codex/sessions").canonicalFile
            val target = File(path).canonicalFile
            if (!target.isFile || !target.canonicalPath.startsWith(root.canonicalPath + File.separator)) return null
            val relative = target.canonicalPath.removePrefix(root.canonicalPath + File.separator).split(File.separator)
            if (relative.size != 3) return null
            return Uri.parse("content://${context.packageName}.codex/" +
                relative.joinToString("/") { Uri.encode(it) })
        }
    }
}
