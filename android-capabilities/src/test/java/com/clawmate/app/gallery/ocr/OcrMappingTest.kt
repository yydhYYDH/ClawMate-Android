package com.clawmate.app.gallery.ocr

import com.clawmate.app.gallery.analysis.OcrErrorKind
import com.clawmate.app.gallery.analysis.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the pure OCR result mapping: real-text/empty/error/
 * unavailable/model-download/no-fake-output guarantees. No device or ML Kit
 * runtime is required.
 */
class OcrMappingTest {

    // ---- real text vs empty ----

    @Test
    fun recognizedNonBlankTextIsRealText() {
        val r = OcrMapping.textFromRecognized("  你好世界  ")
        assertTrue(r is OcrResult.Text)
        assertEquals("你好世界", (r as OcrResult.Text).value) // trimmed
        assertTrue(OcrMapping.isRealText(r))
    }

    @Test
    fun blankOrNullRecognizedTextIsHonestEmpty() {
        assertTrue(OcrMapping.textFromRecognized("") is OcrResult.Empty)
        assertTrue(OcrMapping.textFromRecognized(null) is OcrResult.Empty)
        assertTrue(OcrMapping.textFromRecognized("   ") is OcrResult.Empty)
    }

    @Test
    fun emptyResultIsNeverRealText() {
        val r: OcrResult = OcrResult.Empty
        assertFalse(OcrMapping.isRealText(r))
    }

    // ---- failure classification ----

    @Test
    fun uriAccessFailureIsTypedAndNeverText() {
        val r = OcrMapping.failure(OcrErrorKind.URI_ACCESS, "无法打开内容流")
        assertTrue(r is OcrResult.Failed)
        val f = r as OcrResult.Failed
        assertEquals(OcrErrorKind.URI_ACCESS, f.kind)
        assertEquals("无法打开内容流", f.reason)
        assertFalse(OcrMapping.isRealText(r))
    }

    @Test
    fun decodeFailureIsTypedAndNeverText() {
        val r = OcrMapping.failure(OcrErrorKind.DECODE, "无法解码位图")
        assertTrue(r is OcrResult.Failed)
        assertEquals(OcrErrorKind.DECODE, (r as OcrResult.Failed).kind)
        assertFalse(OcrMapping.isRealText(r))
    }

    @Test
    fun engineFailureIsTypedAndNeverText() {
        val r = OcrMapping.failure(OcrErrorKind.MLKIT_ENGINE, "ML Kit 处理失败")
        assertTrue(r is OcrResult.Failed)
        assertEquals(OcrErrorKind.MLKIT_ENGINE, (r as OcrResult.Failed).kind)
        assertFalse(OcrMapping.isRealText(r))
    }

    @Test
    fun modelDownloadIsTypedAndNeverText() {
        val r = OcrMapping.modelDownload("中文识别模型不可用，可能需要下载")
        assertTrue(r is OcrResult.ModelDownload)
        assertFalse(OcrMapping.isRealText(r))
    }

    // ---- no-fake-output guarantees via classifyException ----

    @Test
    fun unknownExceptionBecomesTypedFailureNeverText() {
        val r = OcrMapping.classifyException {
            throw IllegalStateException("boom")
        }
        assertTrue(r is OcrResult.Failed)
        assertFalse(OcrMapping.isRealText(r))
    }

    @Test
    fun nullClassifierBecomesTypedFailureNeverText() {
        val r = OcrMapping.classifyException { null }
        assertTrue(r is OcrResult.Failed)
        // A null classifier result yields an honest UNKNOWN failure.
        assertEquals(OcrErrorKind.UNKNOWN, (r as OcrResult.Failed).kind)
        assertFalse(OcrMapping.isRealText(r))
    }

    @Test
    fun cancelledClassifierMapsToCancelledKind() {
        val r = OcrMapping.classifyException {
            OcrErrorKind.CANCELLED to "识别已取消"
        }
        assertTrue(r is OcrResult.Failed)
        assertEquals(OcrErrorKind.CANCELLED, (r as OcrResult.Failed).kind)
        assertFalse(OcrMapping.isRealText(r))
    }

    // ---- distinct typed states are not conflated ----

    @Test
    fun allNonTextResultsAreMutuallyDistinctAndNotText() {
        val results: List<OcrResult> = listOf(
            OcrResult.Empty,
            OcrResult.Cancelled,
            OcrResult.ModelDownload("m"),
            OcrResult.Failed("e", OcrErrorKind.URI_ACCESS),
            OcrResult.Unavailable("u"),
        )
        assertTrue(results.none { it is OcrResult.Text })
        assertTrue(results.all { !OcrMapping.isRealText(it) })
    }

    @Test
    fun unavailableIsDistinctAndNeverText() {
        val r: OcrResult = OcrResult.Unavailable("on-device OCR 尚未配置")
        assertTrue(r is OcrResult.Unavailable)
        assertFalse(OcrMapping.isRealText(r))
    }
}
