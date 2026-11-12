package com.clawmate.platform.codex

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.Reader
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.GridSampler
import com.google.zxing.common.PerspectiveTransform
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.detector.Detector

/** Reads the desktop pairing QR from both camera luminance and album images. */
class CodexPairingQrReader : Reader {
    private val standard = QRCodeReader()
    private val decoder = Decoder()

    override fun decode(image: BinaryBitmap): Result = decode(image, null)

    override fun decode(image: BinaryBitmap, hints: Map<DecodeHintType, *>?): Result {
        try {
            return standard.decode(image, hints)
        } catch (failure: ReaderException) {
            return readNearbyVersions(image) ?: throw failure
        }
    }

    override fun reset() = standard.reset()

    private fun readNearbyVersions(image: BinaryBitmap): Result? {
        val pixels = image.blackMatrix
        val detection = try {
            Detector(pixels).detect(tryHarder)
        } catch (_: ReaderException) {
            return null
        }
        val points = detection.points
        val alignment = points.getOrNull(3)
        // On a fractional pixel grid the finder estimate can be one QR version off.
        // Keep the detected geometry and retry only the estimated and adjacent versions.
        for (offset in versionOffsets) {
            val dimension = detection.bits.width + offset
            if (dimension !in 21..177) continue
            if (alignment != null) {
                readGrid(pixels, dimension, points, alignment)?.let { return it }
            }
            readGrid(pixels, dimension, points, null)?.let { return it }
        }
        return null
    }

    private fun readGrid(pixels: BitMatrix, dimension: Int, points: Array<ResultPoint>, alignment: ResultPoint?): Result? {
        val bottomLeft = points[0]
        val topLeft = points[1]
        val topRight = points[2]
        val outer = dimension - 3.5f
        val bottomRight = alignment ?: ResultPoint(
            topRight.x - topLeft.x + bottomLeft.x,
            topRight.y - topLeft.y + bottomLeft.y,
        )
        val inner = if (alignment == null) outer else outer - 3
        val transform = PerspectiveTransform.quadrilateralToQuadrilateral(
            3.5f, 3.5f, outer, 3.5f, inner, inner, 3.5f, outer,
            topLeft.x, topLeft.y, topRight.x, topRight.y,
            bottomRight.x, bottomRight.y, bottomLeft.x, bottomLeft.y,
        )
        val decoded = try {
            val grid = GridSampler.getInstance().sampleGrid(pixels, dimension, dimension, transform)
            decoder.decode(grid)
        } catch (_: ReaderException) {
            return null
        }
        if (!decoded.text.startsWith(CodexTypes.PAIRING_PREFIX)) return null
        return Result(decoded.text, decoded.rawBytes, points, BarcodeFormat.QR_CODE)
    }

    private companion object {
        val tryHarder = mapOf(DecodeHintType.TRY_HARDER to true)
        val versionOffsets = intArrayOf(0, -4, 4)
    }
}
