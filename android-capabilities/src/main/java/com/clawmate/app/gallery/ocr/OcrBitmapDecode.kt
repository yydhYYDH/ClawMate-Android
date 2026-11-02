package com.clawmate.app.gallery.ocr

/**
 * Pure, Android-free logic for bounding the decode size of a picked photo
 * before feeding it to on-device OCR.
 *
 * ML Kit works well on reasonably-sized bitmaps; decoding a full-resolution
 * gallery image is wasteful and risks OOM on a bounded device. This object
 * computes the inSampleSize / downscale decision deterministically so it can be
 * unit-tested; the actual BitmapFactory call lives in [AndroidOcrEngine].
 *
 * Rules (honesty first — we never fabricate a dimension):
 *  - a source whose width/height is unknown or degenerate returns a "no
 *    downscale" decision (1) — the caller still guards against pathological
 *    values separately;
 *  - otherwise the sample factor is the smallest integer power of two that
 *    brings both dimensions at or below [maxDimension];
 *  - the result is always >= 1.
 */
object OcrBitmapDecode {

    /**
     * The maximum pixel side we allow into the recognizer. 0 means "no bound".
     */
    const val DEFAULT_MAX_DIMENSION = 2048

    /** Downscale factor (inSampleSize) for a source of [width]x[height]. */
    fun sampleFactor(width: Int, height: Int, maxDimension: Int = DEFAULT_MAX_DIMENSION): Int {
        if (maxDimension <= 0) return 1
        if (width <= 0 || height <= 0) return 1
        if (width <= maxDimension && height <= maxDimension) return 1
        val largest = maxOf(width, height)
        var factor = 1
        while (largest / factor > maxDimension) {
            factor = factor shl 1
        }
        return factor.coerceAtLeast(1)
    }

    /** Estimated decoded pixel area after applying [sampleFactor], for sanity tests. */
    fun decodedArea(width: Int, height: Int, factor: Int): Long =
        (width.toLong() / maxOf(1, factor)) * (height.toLong() / maxOf(1, factor))

    /**
     * A conservative pixel budget check used before allocating: refused when the
     * (downscaled) decode would exceed a rough in-memory budget (width*height*4
     * bytes) — an honest "too large to decode here" rather than an OOM.
     */
    fun fitsMemoryBudget(width: Int, height: Int, factor: Int, maxBytes: Long): Boolean {
        if (width <= 0 || height <= 0) return false
        val area = decodedArea(width, height, factor)
        return area * 4L <= maxBytes
    }
}
