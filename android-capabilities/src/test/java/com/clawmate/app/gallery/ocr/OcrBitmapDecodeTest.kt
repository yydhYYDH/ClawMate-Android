package com.clawmate.app.gallery.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the bounded-bitmap decode planning. Pure, device-free.
 * Guarantees OCR never decodes an unbounded full-resolution image blindly.
 */
class OcrBitmapDecodeTest {

    @Test
    fun smallImageNeedsNoDownscale() {
        assertEquals(1, OcrBitmapDecode.sampleFactor(640, 480))
        assertEquals(1, OcrBitmapDecode.sampleFactor(2048, 2048)) // exactly at bound
    }

    @Test
    fun largeImageIsDownscaledToPowerOfTwo() {
        // Largest side 4000: factor 2 brings it to 2000 <= 2048.
        assertEquals(2, OcrBitmapDecode.sampleFactor(4000, 3000))
        // 4096 / 2048 = 2 => factor 2.
        assertEquals(2, OcrBitmapDecode.sampleFactor(4096, 2160))
        // 8000 -> 2 (4000) -> 4 (2000) => factor 4.
        assertEquals(4, OcrBitmapDecode.sampleFactor(8000, 6000))
    }

    @Test
    fun degenerateOrUnknownDimStaysAtNoDownscale() {
        assertEquals(1, OcrBitmapDecode.sampleFactor(0, 0))
        assertEquals(1, OcrBitmapDecode.sampleFactor(-1, 500))
        assertEquals(1, OcrBitmapDecode.sampleFactor(500, -1))
    }

    @Test
    fun customBoundIsHonored() {
        assertEquals(1, OcrBitmapDecode.sampleFactor(1500, 1200, maxDimension = 1600))
        // 3000 with bound 1024: 3000 -> 2 (1500) -> 4 (750) => factor 4.
        assertEquals(4, OcrBitmapDecode.sampleFactor(3000, 2000, maxDimension = 1024))
    }

    @Test
    fun disabledBoundAlwaysNoDownscale() {
        assertEquals(1, OcrBitmapDecode.sampleFactor(12000, 9000, maxDimension = 0))
        assertEquals(1, OcrBitmapDecode.sampleFactor(12000, 9000, maxDimension = -5))
    }

    @Test
    fun downscaledDecodeIsWithinBudget() {
        // A 12000x9000 source would be huge; after sampleFactor it fits memory.
        val factor = OcrBitmapDecode.sampleFactor(12000, 9000, 2048)
        assertTrue(OcrBitmapDecode.fitsMemoryBudget(12000, 9000, factor, 64L * 1024 * 1024))
    }

    @Test
    fun oversizedDecodeAtInsufficientScaleIsRefusedHonestly() {
        // Decoding a 16000x16000 source at no downscale (factor 1) exceeds a
        // small budget — the guard honestly refuses instead of OOM-ing.
        assertFalse(OcrBitmapDecode.fitsMemoryBudget(16000, 16000, 1, 64L * 1024 * 1024))
        // At the correct downscale it fits.
        val factor = OcrBitmapDecode.sampleFactor(16000, 16000, 2048)
        assertTrue(OcrBitmapDecode.fitsMemoryBudget(16000, 16000, factor, 64L * 1024 * 1024))
    }

    @Test
    fun degenerateDimNeverFitsBudget() {
        assertFalse(OcrBitmapDecode.fitsMemoryBudget(0, 100, 1, 1_000_000))
        assertFalse(OcrBitmapDecode.fitsMemoryBudget(100, 0, 1, 1_000_000))
    }
}
