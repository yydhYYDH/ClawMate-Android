package com.clawmate.shared

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.clawmate.platform.codex.CodexTypes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
class CodexPickers(private val activity: SharedActivity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private data class Pick(val status: String, val value: String = "", val name: String = "", val error: String = "") {
        fun toJson(): String = JSONObject().put("v", 1).put("status", status)
            .put("value", value).put("name", name).put("error", error).toString()
    }
    private var scan = Pick("idle")
    private var document = Pick("idle")
    private var documentKind = ""

    @Volatile private var captureStatus: String = "idle"
    @Volatile private var capturePath: String = ""
    @Volatile private var captureFile: File? = null

    private var pendingExport: Uri? = null
    private var exportStatus: String = "idle"

    /** True while one of our pickers owns the activity result. */
    fun handles(requestCode: Int): Boolean =
        requestCode == REQUEST_CAMERA || requestCode == REQUEST_SCAN || requestCode == REQUEST_DOCUMENT

    fun scanStart(): Boolean {
        if (scan.status == "running") return false
        scan = Pick("running")
        try {
            activity.startActivityForResult(Intent(activity, CodexPairingScanActivity::class.java), REQUEST_SCAN)
        } catch (_: Exception) {
            scan = Pick("failed", error = "无法打开扫码界面，请导入配对文件或粘贴配对信息")
        }
        return true
    }

    fun scanResult(): String = scan.toJson().also { if (scan.status != "running") scan = Pick("idle") }

    fun close() { scope.cancel() }

    /** Opens the camera. Poll [takePhotoResult] for the outcome. */
    fun takePhotoStart(cacheDir: String): Boolean {
        if (captureStatus == "running") return false
        capturePath = ""
        captureStatus = "running"
        if (!activity.withCameraPermission { granted ->
                if (granted) launchCamera(cacheDir) else captureStatus = "permission"
            }) captureStatus = "failed"
        return true
    }

    private fun launchCamera(cacheDir: String) {
        // Reuse the narrow capture provider so only camera-cache UUID jpg files are granted.
        val directory = File(cacheDir, "chat-camera").apply { mkdirs() }
        val file = File(directory, "${java.util.UUID.randomUUID()}.jpg")
        // The provider only serves a file that already exists, so create it here. Without
        // this the camera is handed a URI it cannot open and the capture silently produces
        // nothing, which the page then reports as a cancellation.
        if (!runCatching { file.createNewFile() }.getOrDefault(false) && !file.isFile) {
            captureStatus = "failed"
            return
        }
        val uri = Uri.parse("content://${activity.packageName}.capture/${file.name}")
        captureFile = file
        capturePath = ""
        captureStatus = "running"
        try {
            activity.startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = android.content.ClipData.newRawUri("camera", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }, REQUEST_CAMERA)
        } catch (failure: Exception) {
            file.delete()
            captureFile = null
            captureStatus = "failed"
        }
    }

    /** `{"v":1,"status":"running|done|cancelled|failed","path":"…"}`. */
    fun takePhotoResult(): String = result(captureStatus, capturePath)

    fun documentStart(kind: String): Boolean {
        if (document.status == "running" || kind !in setOf("pairing", "image", "file")) return false
        document = Pick("running")
        documentKind = kind
        try {
            activity.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (kind == "image") "image/*" else "*/*"
                },
                REQUEST_DOCUMENT,
            )
        } catch (_: Exception) {
            document = Pick("failed", error = "无法打开系统文件选择器")
        }
        return true
    }

    fun documentResult(): String =
        document.toJson().also { if (document.status != "running") document = Pick("idle") }

    /** Called from [SharedActivity.onActivityResult]; returns true when handled. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (!handles(requestCode)) return false
        when (requestCode) {
            REQUEST_CAMERA -> {
                val file = captureFile
                if (file != null) activity.revokeUriPermission(
                    Uri.parse("content://${activity.packageName}.capture/${file.name}"),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                captureStatus = if (resultCode == Activity.RESULT_OK && file != null &&
                    file.isFile && file.length() > 0
                ) {
                    capturePath = file.absolutePath
                    "done"
                } else {
                    file?.delete()
                    if (resultCode == Activity.RESULT_OK) "failed" else "cancelled"
                }
                captureFile = null
            }
            REQUEST_SCAN -> {
                if (scan.status != "running") return true
                val code = data?.getStringExtra(CodexPairingScanActivity.EXTRA_CODE)
                scan = when {
                    resultCode != Activity.RESULT_OK -> Pick("cancelled")
                    code.isNullOrEmpty() -> Pick("failed", error = "未读取到二维码")
                    else -> Pick("done", code)
                }
            }
            REQUEST_DOCUMENT -> {
                if (document.status != "running") return true
                val uri = data?.data
                val kind = documentKind
                when {
                    resultCode != Activity.RESULT_OK -> document = Pick("cancelled")
                    uri?.scheme != "content" -> document = Pick("failed", error = "系统未提供可读取的文件")
                    else -> scope.launch {
                        document = withContext(Dispatchers.IO) { readDocument(uri, kind) }
                    }
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

    private fun readDocument(uri: Uri, kind: String): Pick {
        val pairing = kind == "pairing"
        val limit = if (pairing) 8192 else CodexTypes.MAX_FILE_BYTES.toInt()
        val invalidSize = if (pairing) "配对文件无效，文件上限为 8 KiB" else "单个附件需大于 0 且不超过 8 MiB"
        var target: File? = null
        return try {
            val resolver = activity.contentResolver
            val input = resolver.openInputStream(uri) ?: return Pick("failed", error = "无法读取所选文件")
            input.use { source ->
                if (pairing) {
                    val bytes = ByteArrayOutputStream()
                    if (!copyBounded(source, bytes, limit)) return Pick("failed", error = invalidSize)
                    return Pick("done", bytes.toString("UTF-8").removePrefix("\uFEFF"))
                }
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }.orEmpty().substringAfterLast('/').substringAfterLast('\\')
                    .filterNot { it.isISOControl() }.trim().take(120)
                    .takeUnless { it.isEmpty() || it == "." || it == ".." }
                    ?: if (kind == "image") "image" else "file"
                val directory = File(activity.filesDir, "codex-pick").apply { mkdirs() }
                val file = File.createTempFile("codex-", ".upload", directory)
                target = file
                if (!file.outputStream().use { copyBounded(source, it, limit) }) {
                    file.delete()
                    return Pick("failed", error = invalidSize)
                }
                directory.listFiles()?.filter { it.isFile && it != file }?.sortedByDescending { it.lastModified() }
                    ?.drop(5)?.forEach { it.delete() }
                Pick("done", file.absolutePath, name)
            }
        } catch (_: Exception) {
            target?.delete()
            Pick("failed", error = "无法读取所选文件，请重新选择")
        }
    }

    private fun copyBounded(source: InputStream, output: java.io.OutputStream, limit: Int): Boolean {
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (total <= limit) {
            val count = source.read(buffer, 0, minOf(buffer.size, limit - total + 1))
            if (count < 0) return total > 0
            total += count
            if (total > limit) return false
            output.write(buffer, 0, count)
        }
        return false
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
        const val REQUEST_EXPORT = 7403
        private const val REQUEST_SCAN = 7404
        private const val REQUEST_DOCUMENT = 7405
    }
}
