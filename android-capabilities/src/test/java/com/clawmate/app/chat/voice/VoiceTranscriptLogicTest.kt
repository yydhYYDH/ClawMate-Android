package com.clawmate.app.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the ASR transcript normalization ported from
 * `HarmonySpeechRecognizerAsrProvider.ets` (pure, device-free).
 */
class VoiceTranscriptLogicTest {

    @Test
    fun `cumulative final transcript replaces committed text`() {
        // Simulates an ASR backend that returns the full cumulative transcript.
        assertEquals("今天天气很好", VoiceTranscriptLogic.joinAsrText("今天", "今天天气很好"))
    }

    @Test
    fun `new clause appends to committed text`() {
        assertEquals("今天天气很好。明天会下雨", VoiceTranscriptLogic.joinAsrText("今天天气很好。", "明天会下雨"))
    }

    @Test
    fun `empty committed returns next alone`() {
        assertEquals("你好", VoiceTranscriptLogic.joinAsrText("", "你好"))
    }

    @Test
    fun `empty next returns committed alone`() {
        assertEquals("你好", VoiceTranscriptLogic.joinAsrText("你好", "  "))
    }

    @Test
    fun `compact strips punctuation for cumulative comparison`() {
        assertEquals("今天天气很好", VoiceTranscriptLogic.compactAsrText("今天，天气很好。"))
    }

    @Test
    fun `merge partial not cumulative concatenates committed and next`() {
        val merged = VoiceTranscriptLogic.mergeAsrPartialTranscript("帮我查", "查明天")
        // Mirrors the source: when `next` is not a longer cumulative superset of
        // `committed`, the two are concatenated verbatim.
        assertEquals("帮我查查明天", merged)
    }

    @Test
    fun `duplicate final callback is skipped when identical and same isLast`() {
        assertTrue(
            VoiceTranscriptLogic.shouldSkipDuplicateAsrFinalCallback(
                "你好", true, " 你好 ", true,
            )
        )
        // Different isLast -> not a duplicate.
        assertFalse(
            VoiceTranscriptLogic.shouldSkipDuplicateAsrFinalCallback(
                "你好", true, "你好", false,
            )
        )
    }
}
