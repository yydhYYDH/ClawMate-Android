package com.clawmate.shared

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.clawmate.platform.codex.CodexPairingQrReader
import com.clawmate.platform.codex.CodexTypes
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.Decoder
import com.journeyapps.barcodescanner.DecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CodexPairingScanActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var scanner: CodexPairingPreview
    private lateinit var status: TextView
    private lateinit var camera: Button
    private lateinit var album: Button
    private lateinit var torch: Button
    private var albumOpen = false
    private var readingAlbum = false
    private var resumed = false
    private var cameraRequested = false
    private var torchOn = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = SCAN_CHROME
        window.navigationBarColor = SCAN_CHROME
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        albumOpen = state?.getBoolean("albumOpen") ?: false
        setContentView(R.layout.codex_pairing_scan)
        status = findViewById(R.id.codex_scan_status)
        camera = findViewById<Button>(R.id.codex_scan_camera).apply { setOnClickListener { openCamera() } }
        album = findViewById<Button>(R.id.codex_scan_album).apply { setOnClickListener { openAlbum() } }
        torch = findViewById<Button>(R.id.codex_scan_torch).apply {
            setOnClickListener { if (scanner.isPreviewActive) setTorch(!torchOn) }
        }
        findViewById<View>(R.id.codex_scan_back).setOnClickListener { finish() }
        scanner = findViewById<CodexPairingPreview>(R.id.codex_scan_preview).apply {
            cameraSettings = CameraSettings().apply { focusMode = CameraSettings.FocusMode.CONTINUOUS }
            decoderFactory = DecoderFactory { PreviewDecoder() }
            addStateListener(object : CameraPreview.StateListener {
                override fun previewSized() = Unit
                override fun previewStarted() {
                    invalidate()
                    if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                        torch.visibility = View.VISIBLE
                    }
                }
                override fun previewStopped() { torch.visibility = View.GONE }
                override fun cameraClosed() = Unit
                override fun cameraError(error: Exception) {
                    pause()
                    cameraRequested = false
                    camera.visibility = View.VISIBLE
                    showStatus(R.string.codex_scan_camera_failed)
                }
            })
            decodeContinuous { result ->
                if (resumed && !albumOpen && !readingAlbum) consumeCode(result.text)
            }
        }
        camera.visibility = if (hasCameraPermission()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (albumOpen || readingAlbum) return
        if (hasCameraPermission()) {
            resumeCamera()
        } else if (!getPreferences(MODE_PRIVATE).getBoolean("camera_permission_requested", false)) {
            openCamera()
        } else {
            showStatus(R.string.codex_scan_permission)
        }
    }

    override fun onPause() {
        resumed = false
        cameraRequested = false
        if (torchOn) setTorch(false)
        scanner.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("albumOpen", albumOpen)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun openCamera() {
        if (hasCameraPermission()) {
            showStatus(R.string.codex_scan_hint)
            resumeCamera()
        } else {
            getPreferences(MODE_PRIVATE).edit().putBoolean("camera_permission_requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
    }

    private fun resumeCamera() {
        if (!resumed || isFinishing || albumOpen || readingAlbum || cameraRequested || !hasCameraPermission()) return
        cameraRequested = true
        camera.visibility = View.GONE
        scanner.resume()
    }

    private fun setTorch(on: Boolean) {
        scanner.setTorch(on)
        torchOn = on
        torch.isSelected = on
        torch.setText(if (on) R.string.codex_scan_torch_off else R.string.codex_scan_torch_on)
    }

    private fun showStatus(message: Int) {
        val text = getString(message)
        if (status.text.toString() != text) status.text = text
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != CAMERA_PERMISSION || isFinishing) return
        if (hasCameraPermission()) {
            showStatus(R.string.codex_scan_hint)
            resumeCamera()
        } else {
            showStatus(R.string.codex_scan_permission_settings)
        }
    }

    private fun openAlbum() {
        albumOpen = true
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }, ALBUM)
        } catch (_: Exception) {
            albumOpen = false
            showStatus(R.string.codex_scan_album_failed)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ALBUM) return
        albumOpen = false
        val uri = data?.data
        if (resultCode != RESULT_OK || uri?.scheme != "content") return
        readingAlbum = true
        album.isEnabled = false
        camera.isEnabled = false
        showStatus(R.string.codex_scan_reading)
        scope.launch {
            val code = withContext(Dispatchers.IO) { runCatching { decodeImage(uri) }.getOrNull() }
            readingAlbum = false
            if (isFinishing) return@launch
            if (code != null && consumeCode(code)) return@launch
            if (code == null) showStatus(R.string.codex_scan_no_code)
            album.isEnabled = true
            camera.isEnabled = true
            resumeCamera()
        }
    }

    private fun decodeImage(uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 4096 ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) > 5_000_000L
        ) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return CodexPairingQrReader().decode(BinaryBitmap(
                HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)),
            ), albumHints).text
        } finally {
            bitmap.recycle()
        }
    }

    private fun consumeCode(code: String): Boolean {
        if (isFinishing) return true
        if (!code.startsWith(CodexTypes.PAIRING_PREFIX)) {
            showStatus(R.string.codex_scan_wrong_code)
            return false
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
        finish()
        return true
    }

    /** Give dark-background codes a second polarity without decoding every frame twice. */
    private class PreviewDecoder : Decoder(CodexPairingQrReader()) {
        private var misses = 0

        override fun decode(source: LuminanceSource): Result? {
            val result = super.decode(source)
            if (result == null && ++misses < 4) return null
            misses = 0
            return result ?: super.decode(source.invert())
        }
    }

    companion object {
        internal const val EXTRA_CODE = "pairingCode"
        private const val CAMERA_PERMISSION = 1
        private const val ALBUM = 2
        private const val SCAN_CHROME = 0xFF10141C.toInt()
        private val albumHints = mapOf(DecodeHintType.ALSO_INVERTED to true)
    }
}
