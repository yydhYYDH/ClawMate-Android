package com.clawmate.app.gallery.analysis

/**
 * Typed OCR outcome. Only [Text] ever carries real recognized text; every other
 * state is an honest non-text result (empty, cancelled, model-download, error,
 * unavailable). No state fabricates or substitutes text.
 */
sealed class OcrResult {
    /** Real recognized text (trimmed, never fabricated). */
    data class Text(val value: String) : OcrResult()

    /** The image decoded but no text was found (a real, honest empty). */
    data object Empty : OcrResult()

    /** The user/caller cancelled the recognition before a result. */
    data object Cancelled : OcrResult()

    /** The on-device model is not yet present and must be downloaded first. */
    data class ModelDownload(val message: String) : OcrResult()

    /** A typed failure; [kind] classifies the cause. */
    data class Failed(
        val reason: String,
        val kind: OcrErrorKind = OcrErrorKind.UNKNOWN,
    ) : OcrResult()

    /** The capability/engine is not available in this environment. */
    data class Unavailable(val reason: String) : OcrResult()
}

/** Stable taxonomy for why on-device OCR failed (device-free, testable). */
enum class OcrErrorKind {
    /** No more specific cause known. */
    UNKNOWN,

    /** The content URI is malformed or could not be opened/read. */
    URI_ACCESS,

    /** The image stream could not be decoded into a bitmap. */
    DECODE,

    /** The ML Kit engine itself reported a processing error (non-download). */
    MLKIT_ENGINE,

    /** The underlying ML Kit service is unavailable on this device. */
    UNAVAILABLE,

    /** The user cancelled the recognizer between start and completion. */
    CANCELLED,
}
