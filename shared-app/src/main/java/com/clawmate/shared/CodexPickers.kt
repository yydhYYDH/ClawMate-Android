package com.clawmate.shared

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.io.File
import org.json.JSONObject

/**
 * Native pickers used by the Codex page.
 *
 * ArkUI-X has no ScanKit, CameraKit or MediaLibraryKit, so these interactions use standard
 * Android intents.
 *
 * Each flow is **start/poll**, not blocking. A picker keeps the user in another activity for
 * as long as they like, which is far longer than one bridge call may block; waiting inline
 * would time out at the bridge while the user is still choosing. The page therefore calls
 * `…Start`, then polls `…Result` until the outcome is no longer `running`.
 *
 * Cancellation reports `cancelled` rather than an error: the page treats "user dismissed" as
 * a no-op and a failure toast there would be wrong.
 */
class CodexPickers(private val activity: Activity) {

    @Volatile private var captureStatus: String = "idle"
    @Volatile private var capturePath: String = ""
    @Volatile private var captureFile: File? = null

    @Volatile private var pickStatus: String = "idle"
    @Volatile private var pickPath: String = ""

    private var pendingExport: Uri? = null
    private var exportStatus: String = "idle"

    /** True while one of our pickers owns the activity result. */
    fun handles(requestCode: Int): Boolean =
        requestCode == REQUEST_CAMERA || requestCode == REQUEST_IMAGE

    /**
     * QR scanning is unavailable.
     *
     * Android has no system-wide scan intent, and bundling a scanner would add a camera
     * permission the pairing screen does not otherwise need. The page falls back to importing
     * the pairing file or pasting the text, which are both fully supported.
     */
    fun scanPairingCode(): String = ""

    /** Opens the camera. Poll [takePhotoResult] for the outcome. */
    fun takePhotoStart(cacheDir: String): Boolean {
        // Reuse the narrow capture provider so only camera-cache UUID jpg files are granted.
        val directory = File(cacheDir, "chat-camera").apply { mkdirs() }
        val file = File(directory, "${java.util.UUID.randomUUID()}.jpg")
        // The provider only serves a file that already exists, so create it here. Without
        // this the camera is handed a URI it cannot open and the capture silently produces
        // nothing, which the page then reports as a cancellation.
        if (!runCatching { file.createNewFile() }.getOrDefault(false) && !file.isFile) {
            captureStatus = "failed"
            return false
        }
        val uri = Uri.parse("content://${activity.packageName}.capture/${file.name}")
        captureFile = file
        capturePath = ""
        captureStatus = "running"
        return try {
            activity.startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = android.content.ClipData.newRawUri("camera", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }, REQUEST_CAMERA)
            true
        } catch (failure: Exception) {
            captureFile = null
            captureStatus = "failed"
            false
        }
    }

    /** `{"v":1,"status":"running|done|cancelled|failed","path":"…"}`. */
    fun takePhotoResult(): String = result(captureStatus, capturePath)

    /** Opens the system photo picker. Poll [pickImageResult] for the outcome. */
    fun pickImageStart(): Boolean {
        pickPath = ""
        pickStatus = "running"
        return try {
            activity.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                },
                REQUEST_IMAGE,
            )
            true
        } catch (failure: Exception) {
            pickStatus = "failed"
            false
        }
    }

    fun pickImageResult(): String = result(pickStatus, pickPath)

    /** Called from [SharedActivity.onActivityResult]; returns true when handled. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (!handles(requestCode)) return false
        when (requestCode) {
            REQUEST_CAMERA -> {
                val file = captureFile
                captureStatus = if (resultCode == Activity.RESULT_OK && file != null &&
                    file.isFile && file.length() > 0
                ) {
                    capturePath = file.absolutePath
                    "done"
                } else {
                    file?.delete()
                    "cancelled"
                }
                captureFile = null
            }
            REQUEST_IMAGE -> {
                val uri = data?.data
                val copied = if (resultCode == Activity.RESULT_OK && uri != null) copyToCache(uri) else ""

                if (copied.isEmpty()) {
                    pickStatus = if (resultCode != Activity.RESULT_OK) "cancelled" else "failed"
                } else {
                    pickPath = copied
                    pickStatus = "done"
                }
            }
        }
        return true
    }

    private fun result(status: String, path: String): String = JSONObject()
        .put("v", 1)
        .put("status", status)
        .put("path", if (status == "done") path else "")
        .toString()

    /** Copies a picked content URI into the cache so ArkTS can read a plain path. */
    /**
     * Copies a picked content URI into app storage and returns a real path.
     *
     * The cache directory is not durable enough here: the system may reclaim it between the
     * picker returning and ArkTS reopening the path, and the caller then fails to open a file
     * that was just reported as ready. This uses the app's own files directory and clears
     * older picks so it cannot grow without bound.
     */
    private fun copyToCache(uri: Uri): String {
        return try {
            val directory = File(activity.filesDir, "codex-pick").apply { mkdirs() }
            val target = File(directory, "pick-${System.currentTimeMillis()}.img")
            val input = activity.contentResolver.openInputStream(uri) ?: run {
                android.util.Log.i("ClawMateCodex", "picker copy: no stream for the picked uri")
                return ""
            }
            input.use { source ->
                target.outputStream().use { output -> source.copyTo(output) }
            }
            if (!target.isFile || target.length() <= 0L) return ""
            // Keep only the most recent picks; a failed upload otherwise accumulates files.
            directory.listFiles()?.sortedByDescending { it.name }?.drop(6)?.forEach { it.delete() }
            target.absolutePath
        } catch (e: Exception) {
            // The path alone is useless to the caller, so record why the copy failed.
            android.util.Log.i("ClawMateCodex", "picker copy failed: " + e.javaClass.simpleName + " " + e.message)
            ""
        }
    }

    /** Opens a delivered file in whichever app handles its type. */
    fun openArtifact(path: String, mimeType: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        val uri = CodexArtifactProvider.uriFor(activity, file.absolutePath) ?: return false
        return try {
            activity.startActivity(
                Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType.ifEmpty { "*/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, file.name),
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Asks where to save a delivered file. Poll [exportResult] for the outcome. */
    fun exportStart(path: String, name: String): Boolean {
        val source = File(path)
        if (!source.isFile) {
            exportStatus = "failed"
            return false
        }
        exportSource = source
        pendingExport = null
        exportStatus = "running"
        return try {
            activity.startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_TITLE, name)
                },
                REQUEST_EXPORT,
            )
            true
        } catch (e: Exception) {
            exportStatus = "failed"
            false
        }
    }

    /** `{"v":1,"status":"running|done|cancelled|failed"}`. */
    fun exportResult(): String = JSONObject()
        .put("v", 1)
        .put("status", exportStatus)
        .toString()

    @Volatile private var exportSource: File? = null

    /** Records the destination the user chose and copies the file there. */
    fun onExportPicked(uri: Uri?) {
        val source = exportSource
        if (uri == null || source == null || !source.isFile) {
            exportStatus = "cancelled"
            return
        }
        exportStatus = try {
            activity.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } != null
        } catch (e: Exception) {
            false
        }.let { if (it) "done" else "failed" }
    }

    companion object {
        const val REQUEST_CAMERA = 7401
        const val REQUEST_IMAGE = 7402
        const val REQUEST_EXPORT = 7403
    }
}
