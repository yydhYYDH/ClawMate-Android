package com.clawmate.platform.codex

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.floor

class CodexPairingQrReaderTest {
    @Test fun readsDesktopFractionalRasterInAlbumAndCamera() {
        val prefix = CodexTypes.PAIRING_PREFIX
        val payload = prefix + "x".repeat(1134 - prefix.length)
        val modules = QRCodeWriter().encode(
            payload, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.QR_VERSION to 24, EncodeHintType.MARGIN to 0),
        )
        assertEquals(113, modules.width)
        val scale = 480.0 / (modules.width + 4)
        val size = floor((modules.width + 4) * scale).toInt()
        val luma = ByteArray(size * size) { i ->
            val x = floor((i % size) / scale - 2).toInt()
            val y = floor((i / size) / scale - 2).toInt()
            if (x in 0 until modules.width && y in 0 until modules.height && modules[x, y]) 0 else -1
        }
        val albumPixels = IntArray(luma.size) { if (luma[it] == 0.toByte()) 0xff000000.toInt() else -1 }
        val album = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(size, size, albumPixels)))
        val camera = BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(luma, size, size, 0, 0, size, size, false)))

        assertThrows(ReaderException::class.java) { QRCodeReader().decode(album) }
        assertEquals(payload, CodexPairingQrReader().decode(album).text)
        assertEquals(payload, CodexPairingQrReader().decode(camera).text)
    }
}
