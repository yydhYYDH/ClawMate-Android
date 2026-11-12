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
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.detector.Detector

/** Reads the desktop pairing QR from both camera luminance and album images. */
class CodexPairingQrReader : Reader {
    private val decoder = Decoder()

    override fun decode(image: BinaryBitmap): Result = decode(image, null)

    override fun decode(image: BinaryBitmap, hints: Map<DecodeHintType, *>?): Result {
        val pixels = image.blackMatrix
        val options = if (hints == null) tryHarder else hints + tryHarder
        return try {
            read(pixels, options)
        } catch (failure: ReaderException) {
            if (hints?.get(DecodeHintType.ALSO_INVERTED) != true) throw failure
            val inverted = pixels.clone().apply { flip() }
            read(inverted, options)
        }
    }

    override fun reset() = Unit

    private fun read(pixels: BitMatrix, hints: Map<DecodeHintType, *>): Result {
        val detection = Detector(pixels).detect(hints)
        val points = detection.points
        return try {
            // Decoding the detector's grid mutates it. Alternate grids sample the
            // original pixels and reuse the same finder positions.
            val decoded = decoder.decode(detection.bits, hints)
            Result(decoded.text, decoded.rawBytes, points, BarcodeFormat.QR_CODE)
        } catch (failure: ReaderException) {
            readNearbyVersions(pixels, detection.bits.width, points, hints) ?: throw failure
        }
    }

    private fun readNearbyVersions(
        pixels: BitMatrix,
        detectedDimension: Int,
        points: Array<ResultPoint>,
        hints: Map<DecodeHintType, *>,
    ): Result? {
        val alignment = points.getOrNull(3)
        // On a fractional pixel grid the finder estimate can be one QR version off.
        // Keep the detected geometry and retry only the estimated and adjacent versions.
        for (dimension in intArrayOf(detectedDimension - 4, detectedDimension + 4)) {
            if (dimension !in MIN_DIMENSION..MAX_DIMENSION) continue
            if (alignment != null) {
                readGrid(pixels, dimension, points, alignment, hints)?.let { return it }
            }
            readGrid(pixels, dimension, points, null, hints)?.let { return it }
        }
        return if (alignment == null) null else readGrid(pixels, detectedDimension, points, null, hints)
    }

    private fun readGrid(
        pixels: BitMatrix,
        dimension: Int,
        points: Array<ResultPoint>,
        alignment: ResultPoint?,
        hints: Map<DecodeHintType, *>,
    ): Result? {
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
            decoder.decode(grid, hints)
        } catch (_: ReaderException) {
            return null
        }
        if (!decoded.text.startsWith(CodexTypes.PAIRING_PREFIX)) return null
        return Result(decoded.text, decoded.rawBytes, points, BarcodeFormat.QR_CODE)
    }

    private companion object {
        val tryHarder = mapOf(DecodeHintType.TRY_HARDER to true)
        const val MIN_DIMENSION = 21
        const val MAX_DIMENSION = 177
    }
}
