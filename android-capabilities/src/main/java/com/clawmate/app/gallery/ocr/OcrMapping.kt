package com.clawmate.app.gallery.ocr

import com.clawmate.app.gallery.analysis.OcrErrorKind
import com.clawmate.app.gallery.analysis.OcrResult

/**
 * Pure, Android-free mapping rules for on-device OCR outcomes.
 *
 * The thick platform work (ContentResolver open, bitmap decode, ML Kit Chinese
 * recognition, resource close) lives in [AndroidOcrEngine] and cannot run in a
 * JVM unit test. All *decision* logic — turning a raw recognized string or a
 * classified engine failure into the typed [OcrResult] states — is concentrated
 * here so it is deterministically testable without a device. Nothing here can
 * fabricate text: [textFromRecognized] yields an empty state for a blank/null
 * result, and only genuinely non-blank recognized text becomes [OcrResult.Text].
 */
object OcrMapping {

    /**
     * Map the raw text value the ML Kit recognizer produced. A null/blank raw
     * value (real empty image) maps to [OcrResult.Empty] — never to fake text.
     * A non-blank value is trimmed and wrapped as real [OcrResult.Text].
     */
    fun textFromRecognized(raw: String?): OcrResult {
        val v = raw?.trim().orEmpty()
        return if (v.isEmpty()) OcrResult.Empty else OcrResult.Text(v)
    }

    /** Build a typed [OcrResult.Failed] with a human reason and a stable kind. */
    fun failure(kind: OcrErrorKind, reason: String): OcrResult =
        OcrResult.Failed(reason, kind)

    /** Model-download state — no text is claimed until a later successful run. */
    fun modelDownload(message: String): OcrResult = OcrResult.ModelDownload(message)

    /**
     * No-fake-output guarantee: true only when [result] carries real, non-blank
     * text. This is the single predicate callers use before persisting OCR
     * output, so a blank/error/unavailable outcome can never be stored as text.
     */
    fun isRealText(result: OcrResult): Boolean =
        result is OcrResult.Text && result.value.isNotBlank()

    /**
     * Classify a thrown platform exception into a typed [OcrResult].
     *
     * [classifier] maps a platform exception to a pure descriptor so this
     * function stays Android-free; the caller (AndroidOcrEngine) bridges ML
     * Kit exceptions into [OcrErrorKind] + a message. Unknown/misclassified
     * exceptions never yield text — they are honest typed failures.
     */
    fun classifyException(
        classifier: () -> Pair<OcrErrorKind, String>?,
    ): OcrResult {
        val mapped = runCatching { classifier() }.getOrNull()
        return if (mapped == null) {
            failure(OcrErrorKind.UNKNOWN, "OCR 引擎异常")
        } else {
            failure(mapped.first, mapped.second)
        }
    }
}
