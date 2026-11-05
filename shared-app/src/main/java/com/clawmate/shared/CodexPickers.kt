package com.clawmate.shared

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * Native pickers used by the Codex page.
 *
 * ArkUI-X has no ScanKit, CameraKit or MediaLibraryKit, so these interactions use standard
 * Android intents. The bridge call blocks until [onActivityResult] stores the outcome and
 * the waiting call observes it, mirroring the existing capture/photo-picker flow in
 * SharedActivity rather than introducing a second activity-result mechanism.
 *
 * Cancellation returns an empty value: the page treats "user dismissed" as a no-op, and an
 * error toast there would be misleading.
 */
class CodexPickers(private val activity: Activity) {

    @Volatile
    private var pendingRequest: Int = 0

    @Volatile
    private var resultPath: String = ""

    @Volatile
    private var resultReady: Boolean = false

    private var captureFile: File? = null

    /** True while one of our pickers owns the activity result. */
    fun handles(requestCode: Int): Boolean = requestCode == REQUEST_CAMERA || requestCode == REQUEST_IMAGE

    /**
     * QR scanning is unavailable.
     *
     * Android has no system-wide scan intent, and bundling a scanner would add a camera
     * permission the pairing screen does not otherwise need. The page falls back to
     * importing the pairing file or pasting the text, which are both fully supported.
     */
    fun scanPairingCode(): String = ""

    /** Captures a photo into the app cache; returns its path, or "" when cancelled. */
    fun takePhoto(cacheDir: String): String {
        // Reuse the narrow capture provider so only camera-cache UUID jpg files are granted.
        val root = File(cacheDir, "chat-camera").also { it.mkdirs() }
        val file = File(root, "${java.util.UUID.randomUUID()}.jpg").also { it.createNewFile() }
        captureFile = file
        val uri = Uri.parse("content://${activity.packageName}.capture/${file.name}")
        begin(REQUEST_CAMERA)
        return try {
            activity.startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = android.content.ClipData.newRawUri("camera", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }, REQUEST_CAMERA)
            awaitResult()
        } catch (failure: Exception) {
            file.delete()
            ""
        } finally {
            captureFile = null
        }
    }

    /** Opens the system photo picker; returns a cache path, or "" when cancelled. */
    fun pickImage(): String {
        begin(REQUEST_IMAGE)
        return try {
            activity.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                },
                REQUEST_IMAGE,
            )
            awaitResult()
        } catch (failure: Exception) {
            ""
        }
    }

    /** Called from [SharedActivity.onActivityResult]; returns true when handled. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (!handles(requestCode) || requestCode != pendingRequest) return false
        val value = when {
            resultCode != Activity.RESULT_OK -> ""
            requestCode == REQUEST_CAMERA -> captureFile?.takeIf { it.isFile && it.length() > 0 }?.absolutePath ?: ""
            else -> data?.data?.let { copyToCache(it) }.orEmpty()
        }
        complete(value)
        return true
    }

    private fun begin(requestCode: Int) {
        resultReady = false
        resultPath = ""
        pendingRequest = requestCode
    }

    private fun complete(value: String) {
        synchronized(this) {
            resultPath = value
            resultReady = true
            (this as Object).notifyAll()
        }
    }

    /** Blocks the calling bridge thread until the activity result arrives. */
    private fun awaitResult(): String = synchronized(this) {
        val deadline = System.currentTimeMillis() + PICKER_TIMEOUT_MS
        while (!resultReady) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return ""
            (this as Object).wait(remaining)
        }
        pendingRequest = 0
        resultPath
    }

    /** Copies a picked content URI into the cache so ArkTS can read a plain path. */
    private fun copyToCache(uri: Uri): String {
        return try {
            val target = File(File(activity.cacheDir, "codex-pick").apply { mkdirs() },
                "pick-${System.currentTimeMillis()}.img")
            val input = activity.contentResolver.openInputStream(uri) ?: return ""
            input.use { source ->
                target.outputStream().use { output -> source.copyTo(output) }
            }
            target.absolutePath
        } catch (e: Exception) {
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

    /** Asks where to save the file, then copies it there. */
    fun exportArtifact(path: String, name: String): Boolean {
        val source = File(path)
        if (!source.isFile) return false
        begin(REQUEST_EXPORT)
        return try {
            activity.startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_TITLE, name)
                },
                REQUEST_EXPORT,
            )
            val destination = awaitExport()
            if (destination == null) return false
            activity.contentResolver.openOutputStream(destination)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } != null
        } catch (e: Exception) {
            false
        }
    }

    @Volatile
    private var exportUri: Uri? = null

    private fun awaitExport(): Uri? = synchronized(this) {
        val deadline = System.currentTimeMillis() + PICKER_TIMEOUT_MS
        while (pendingRequest == REQUEST_EXPORT) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            (this as Object).wait(remaining)
        }
        exportUri
    }

    /** Records an export destination chosen by the user. */
    fun onExportPicked(uri: Uri?) {
        synchronized(this) {
            exportUri = uri
            pendingRequest = 0
            (this as Object).notifyAll()
        }
    }

    companion object {
        const val REQUEST_CAMERA = 7401
        const val REQUEST_IMAGE = 7402
        const val REQUEST_EXPORT = 7403
        private const val PICKER_TIMEOUT_MS = 300_000L
    }
}

