package com.clawmate.platform.codex

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.floor

class CodexPairingQrReaderTest {
    private val payload = CodexTypes.PAIRING_PREFIX + "x".repeat(1134 - CodexTypes.PAIRING_PREFIX.length)
    private val modules = QRCodeWriter().encode(
        payload, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.QR_VERSION to 24, EncodeHintType.MARGIN to 0),
    )

    @Test fun readsDesktopFractionalRasterInAlbumAndCamera() {
        val scale = 480.0 / (modules.width + 4)
        val size = floor((modules.width + 4) * scale).toInt()
        val luma = ByteArray(size * size) { i -> luminance((i % size) / scale, (i / size) / scale) }
        val albumPixels = IntArray(luma.size) { if (luma[it] == 0.toByte()) 0xff000000.toInt() else -1 }
        val album = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, albumPixels)))

        assertEquals(113, modules.width)
        assertThrows(ReaderException::class.java) { QRCodeReader().decode(album) }
        assertEquals(payload, CodexPairingQrReader().decode(album).text)
        assertEquals(payload, CodexPairingQrReader().decode(cameraImage(luma, size)).text)
    }

    @Test fun readsDenseCodeTiltedInCameraFrame() {
        val edge = (modules.width + 4).toFloat()
        val transform = PerspectiveTransform.quadrilateralToQuadrilateral(
            67f, 58f, 515f, 81f, 503f, 532f, 51f, 506f,
            0f, 0f, edge, 0f, edge, edge, 0f, edge,
        )
        val size = 576
        val row = FloatArray(size * 2)
        val luma = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                row[x * 2] = x.toFloat()
                row[x * 2 + 1] = y.toFloat()
            }
            transform.transformPoints(row)
            for (x in 0 until size) {
                luma[y * size + x] = luminance(row[x * 2].toDouble(), row[x * 2 + 1].toDouble())
            }
        }
        assertEquals(payload, CodexPairingQrReader().decode(cameraImage(luma, size)).text)
    }

    @Test fun readsInvertedDesktopCodeWithAlbumHint() {
        val size = (modules.width + 4) * 4
        val pixels = IntArray(size * size) { i ->
            if (luminance((i % size) / 4.0, (i / size) / 4.0) == 0.toByte()) -1 else 0xff000000.toInt()
        }
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, pixels)))
        assertEquals(payload, CodexPairingQrReader().decode(image, mapOf(DecodeHintType.ALSO_INVERTED to true)).text)
    }

    private fun luminance(moduleX: Double, moduleY: Double): Byte {
        val x = floor(moduleX - 2).toInt()
        val y = floor(moduleY - 2).toInt()
        return if (x in 0 until modules.width && y in 0 until modules.height && modules[x, y]) 0 else -1
    }

    private fun cameraImage(luma: ByteArray, size: Int) = BinaryBitmap(
        HybridBinarizer(PlanarYUVLuminanceSource(luma, size, size, 0, 0, size, size, false)),
    )
}
