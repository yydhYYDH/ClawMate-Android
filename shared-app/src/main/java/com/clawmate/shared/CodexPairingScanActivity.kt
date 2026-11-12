package com.clawmate.shared

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.clawmate.platform.codex.CodexPairingQrReader
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Decoder
import com.journeyapps.barcodescanner.DecoderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CodexPairingScanActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var status: TextView
    private lateinit var camera: Button
    private lateinit var album: Button
    private var albumOpen = false
    private var readingAlbum = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        albumOpen = state?.getBoolean("albumOpen") ?: false
        scanner = DecoratedBarcodeView(this).apply {
            barcodeView.decoderFactory = DecoderFactory { Decoder(CodexPairingQrReader()) }
            setStatusText("")
            barcodeView.addStateListener(object : CameraPreview.StateListener {
                override fun previewSized() = Unit
                override fun previewStarted() = Unit
                override fun previewStopped() = Unit
                override fun cameraClosed() = Unit
                override fun cameraError(error: Exception) {
                    pause()
                    status.text = "相机无法启动，请重试或从相册识别"
                    camera.isEnabled = true
                }
            })
            decodeContinuous { result -> if (!isFinishing && !albumOpen && !readingAlbum) finishWithCode(result.text) }
        }
        status = TextView(this).apply {
            setPadding(24, 20, 24, 20)
            text = "对准电脑端配对码，让整个二维码占满取景框"
        }
        camera = Button(this).apply {
            text = "开启相机"
            setOnClickListener { openCamera() }
        }
        album = Button(this).apply {
            text = "从相册识别"
            setOnClickListener { openAlbum() }
        }
        val cancel = Button(this).apply { text = "取消"; setOnClickListener { finish() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(scanner, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(this@CodexPairingScanActivity).apply {
                for (button in listOf(camera, album, cancel)) {
                    addView(button, LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
        })
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission() && !albumOpen && !readingAlbum) resumeCamera()
    }

    override fun onPause() {
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
        if (hasCameraPermission()) resumeCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
    }

    private fun resumeCamera() {
        camera.isEnabled = false
        scanner.resume()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != CAMERA_PERMISSION || isFinishing) return
        if (hasCameraPermission()) resumeCamera()
        else status.text = "相机权限未开启，可从相册识别，或在系统设置允许相机权限"
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
            status.text = "无法打开系统相册，请使用相机扫码"
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
        status.text = "正在识别二维码"
        scope.launch {
            val code = withContext(Dispatchers.IO) { runCatching { decodeImage(uri) }.getOrNull() }
            readingAlbum = false
            if (code != null) finishWithCode(code)
            else {
                album.isEnabled = true
                camera.isEnabled = true
                status.text = "这张图片中未识别出二维码，请选择清晰的配对码截图"
            }
        }
    }

    private fun decodeImage(uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return CodexPairingQrReader().decode(BinaryBitmap(
                HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)),
            )).text
        } finally {
            bitmap.recycle()
        }
    }

    private fun finishWithCode(code: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, code))
        finish()
    }

    companion object {
        internal const val EXTRA_CODE = "pairingCode"
        private const val CAMERA_PERMISSION = 1
        private const val ALBUM = 2
    }
}
